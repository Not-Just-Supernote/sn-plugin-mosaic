package me.laumss.mosaic

import android.util.Base64
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap


class MosaicRawNetModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    companion object {
        private const val TAG = "MosaicRawNet"
        private const val EVENT = "MosaicRawWs"
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val MAX_MESSAGE_BYTES = 80 * 1024 * 1024
        private const val MAX_HTTP_BODY_BYTES = 8 * 1024 * 1024
        private val random = SecureRandom()
    }

    private class Connection(val socket: Socket) {
        val writeLock = Any()
        @Volatile var closedByJs = false
    }

    private val connections = ConcurrentHashMap<Int, Connection>()

    override fun getName(): String = "MosaicRawNet"

    private fun emit(id: Int, type: String, data: String? = null, message: String? = null, code: Int? = null) {
        val map = Arguments.createMap()
        map.putInt("id", id)
        map.putString("type", type)
        if (data != null) map.putString("data", data)
        if (message != null) map.putString("message", message)
        if (code != null) map.putInt("code", code)
        try {
            context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(EVENT, map)
        } catch (e: Exception) {
            Log.w(TAG, "emit failed id=$id type=$type: ${e.message}")
        }
    }

    @ReactMethod
    fun wsOpen(id: Int, host: String, port: Int, path: String) {
        Thread({ runWebSocket(id, host, port, path) }, "MosaicRawWs-$id").start()
    }

    @ReactMethod
    fun wsSend(id: Int, text: String) {
        val conn = connections[id] ?: return
        try {
            writeFrame(conn, 0x1, text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "wsSend failed id=$id: ${e.javaClass.simpleName}: ${e.message}")
            try { conn.socket.close() } catch (_: Exception) {}
        }
    }

    @ReactMethod
    fun wsClose(id: Int) {
        val conn = connections[id] ?: return
        conn.closedByJs = true
        Thread {
            try { writeFrame(conn, 0x8, byteArrayOf(0x03, 0xE8.toByte())) } catch (_: Exception) {}
            try { conn.socket.close() } catch (_: Exception) {}
        }.start()
    }

    private fun runWebSocket(id: Int, host: String, port: Int, path: String) {
        val socket = Socket()
        val conn = Connection(socket)
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            connections[id] = conn
            val keyBytes = ByteArray(16).also { random.nextBytes(it) }
            val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val request = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.UTF_8)); flush() }
            val input = socket.getInputStream().buffered()
            val head = readHead(input)
            val status = head.first
            if (status != 101) {
                emit(id, "error", message = "握手失败 HTTP $status")
                emit(id, "close", code = 1006)
                return
            }
            val expected = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)), Base64.NO_WRAP)
            if (head.second["sec-websocket-accept"] != expected) {
                emit(id, "error", message = "握手校验失败（Sec-WebSocket-Accept 不符）")
                emit(id, "close", code = 1006)
                return
            }
            Log.i(TAG, "ws open id=$id $host:$port$path")
            emit(id, "open")
            readLoop(id, conn, input)
        } catch (e: Exception) {
            if (!conn.closedByJs) {
                Log.w(TAG, "ws failed id=$id $host:$port: ${e.javaClass.simpleName}: ${e.message}")
                emit(id, "error", message = "${e.javaClass.simpleName}: ${e.message}")
                emit(id, "close", code = 1006)
            } else {
                emit(id, "close", code = 1000)
            }
        } finally {
            connections.remove(id)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readLoop(id: Int, conn: Connection, input: InputStream) {
        val message = ByteArrayOutputStream()
        var messageOpcode = 0
        while (true) {
            val b0 = input.read()
            if (b0 < 0) throw EOFException("连接被对端关闭")
            val b1 = readByte(input)
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = (readByte(input).toLong() shl 8) or readByte(input).toLong()
            else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte(input).toLong() }
            }
            if (length > MAX_MESSAGE_BYTES) throw IOException("帧过大 $length")
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(length.toInt())
            readFully(input, payload)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x8 -> {
                    val code = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                    try { writeFrame(conn, 0x8, payload) } catch (_: Exception) {}
                    emit(id, "close", code = code)
                    conn.closedByJs = true
                    return
                }
                0x9 -> try { writeFrame(conn, 0xA, payload) } catch (_: Exception) {}
                0xA -> {}
                else -> {
                    if (opcode != 0x0) { message.reset(); messageOpcode = opcode }
                    message.write(payload)
                    if (message.size() > MAX_MESSAGE_BYTES) throw IOException("消息过大")
                    if (fin) {
                        if (messageOpcode == 0x1 || messageOpcode == 0x2) {
                            emit(id, "message", data = String(message.toByteArray(), Charsets.UTF_8))
                        }
                        message.reset()
                    }
                }
            }
        }
    }

    private fun writeFrame(conn: Connection, opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write(payload.size shr 8); header.write(payload.size and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        header.write(mask)
        val body = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        synchronized(conn.writeLock) {
            val out = conn.socket.getOutputStream()
            out.write(header.toByteArray())
            out.write(body)
            out.flush()
        }
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException("连接被对端关闭")
        return value
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) throw EOFException("连接被对端关闭")
            offset += n
        }
    }

    private fun readLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("响应头未结束")
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
            if (line.size() > 16 * 1024) throw IOException("响应头过长")
        }
        return line.toString(Charsets.ISO_8859_1.name())
    }

    
    private fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
        val status = readLine(input).split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("状态行无效")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return status to headers
    }

    
    @ReactMethod
    fun httpRequest(method: String, host: String, port: Int, path: String, contentType: String?, bodyBase64: String?, promise: Promise) {
        Thread({
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = 30000
                val body = if (bodyBase64.isNullOrEmpty()) ByteArray(0) else Base64.decode(bodyBase64, Base64.DEFAULT)
                val head = StringBuilder("$method $path HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\nAccept: */*\r\n")
                if (!contentType.isNullOrEmpty()) head.append("Content-Type: $contentType\r\n")
                if (body.isNotEmpty() || method == "PUT" || method == "POST") head.append("Content-Length: ${body.size}\r\n")
                head.append("\r\n")
                val out = socket.getOutputStream()
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
                val input = socket.getInputStream().buffered()
                val (status, headers) = readHead(input)
                val payload = ByteArrayOutputStream()
                val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
                val declared = headers["content-length"]?.toLongOrNull()
                if (chunked) {
                    while (true) {
                        val size = readLine(input).substringBefore(';').trim().toInt(16)
                        if (size == 0) break
                        val chunk = ByteArray(size)
                        readFully(input, chunk)
                        payload.write(chunk)
                        readLine(input)
                        if (payload.size() > MAX_HTTP_BODY_BYTES) throw IOException("响应过大")
                    }
                } else if (declared != null) {
                    if (declared > MAX_HTTP_BODY_BYTES) throw IOException("响应过大")
                    val bytes = ByteArray(declared.toInt())
                    readFully(input, bytes)
                    payload.write(bytes)
                } else {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        payload.write(buffer, 0, n)
                        if (payload.size() > MAX_HTTP_BODY_BYTES) throw IOException("响应过大")
                    }
                }
                val result = Arguments.createMap()
                result.putInt("status", status)
                result.putString("bodyBase64", Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP))
                promise.resolve(result)
            } catch (e: Exception) {
                Log.w(TAG, "http $method $host:$port$path failed: ${e.javaClass.simpleName}: ${e.message}")
                promise.reject("E_RAW_HTTP", "${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }, "MosaicRawHttp").start()
    }
}
