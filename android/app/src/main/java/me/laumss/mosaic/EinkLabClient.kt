package me.laumss.mosaic

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The privileged plugin host uses HTTP to the desktop instead of embedding a browser. */
class EinkLabClient(
    private val onConfig: (String) -> Unit,
    private val onCommand: (String) -> Unit,
    private val report: () -> String,
    private val onConnection: (Boolean) -> Unit,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "MosaicEinkLabHttp").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var connection: Socket? = null
    private var revision = -1L
    private var commandId = -1L
    private var session = ""
    private var connected: Boolean? = null
    private var lastReport = ""
    private var lastReportAt = 0L

    fun start() { executor.scheduleWithFixedDelay({ poll() }, 0L, 350L, TimeUnit.MILLISECONDS) }
    fun publish(config: String) {
        if (!closed) executor.execute { runCatching { request("/api/config", config) } }
    }
    private fun poll() {
        if (closed) return
        try {
            val state = JSONObject(request("/api/state"))
            if (closed) return
            val nextSession = state.optString("session")
            if (nextSession != session) { session = nextSession; revision = -1L; commandId = -1L }
            val nextRevision = state.getLong("revision")
            if (nextRevision != revision) {
                revision = nextRevision
                onConfig(state.getJSONObject("config").toString())
            }
            val latest = state.getJSONObject("command")
            if (commandId < 0) commandId = latest.getLong("id")
            else {
                val commands = state.optJSONArray("commands")
                if (commands != null) {
                    for (i in 0 until commands.length()) {
                        val command = commands.getJSONObject(i)
                        if (command.getLong("id") > commandId) {
                            commandId = command.getLong("id")
                            onCommand(command.getString("action"))
                        }
                    }
                } else if (latest.getLong("id") > commandId) {
                    commandId = latest.getLong("id")
                    onCommand(latest.getString("action"))
                }
            }
            val snapshot = report()
            val now = android.os.SystemClock.uptimeMillis()
            if (snapshot != lastReport || now - lastReportAt >= 1_000L) {
                request("/api/report", snapshot)
                lastReport = snapshot
                lastReportAt = now
            }
            setConnected(true)
        } catch (error: Exception) {
            if (!closed) {
                if (connected != false) android.util.Log.w("MosaicEinkLab", "local server unavailable", error)
                setConnected(false)
            }
        }
    }
    private fun setConnected(value: Boolean) {
        if (connected == value || closed) return
        connected = value
        android.util.Log.i("MosaicEinkLab", "local server connected=$value")
        onConnection(value)
    }
    private fun request(path: String, body: String? = null): String {
        check(!closed)
        // The control channel is fixed to this device's ADB-reversed loopback port.
        val conn = Socket()
        connection = conn
        try {
            conn.connect(InetSocketAddress("127.0.0.1", 18765), 1_000)
            conn.soTimeout = 1_000
            val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val header = buildString {
                append(if (body == null) "GET" else "POST").append(" $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:18765\r\nConnection: close\r\n")
                if (body != null) append("Content-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            conn.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
            val reader = conn.getInputStream().bufferedReader(Charsets.UTF_8)
            check(reader.readLine()?.split(' ')?.getOrNull(1) == "200")
            var headerBytes = 0
            while (true) {
                val line = reader.readLine() ?: error("Incomplete HTTP response")
                headerBytes += line.length
                check(headerBytes <= 8192)
                if (line.isEmpty()) break
            }
            val result = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                result.append(buffer, 0, read)
                check(result.length <= 262144)
            }
            return result.toString()
        } finally { conn.close(); connection = null }
    }
    fun close() {
        closed = true
        connection?.close()
        executor.shutdownNow()
    }
}
