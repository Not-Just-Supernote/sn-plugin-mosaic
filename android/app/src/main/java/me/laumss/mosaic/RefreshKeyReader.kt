package me.laumss.mosaic

import android.os.Build
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder


class RefreshKeyReader {

    companion object {
        private const val TAG = "MosaicRefreshKey"
        private const val EV_KEY = 1
        private const val KEY_REFRESH = 173
        private const val INPUT_EVENT_SIZE_64 = 24
        private const val INPUT_EVENT_SIZE_32 = 16
    }

    @Volatile private var running = false
    private val threads = ArrayList<Thread>()

    private val inputEventSize: Int
        get() = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) INPUT_EVENT_SIZE_64 else INPUT_EVENT_SIZE_32

    fun start() {
        if (running) return
        running = true
        Thread({
            val paths = scanRefreshDevices()
            if (paths.isEmpty()) {
                Log.w(TAG, "no input device reports KEY_REFRESH; manual full refresh will not be detected")
                return@Thread
            }
            synchronized(threads) {
                if (!running) return@Thread
                for (path in paths) {
                    threads.add(Thread({ readLoop(path) }, "MosaicRefreshKey").also { it.start() })
                }
            }
        }, "MosaicRefreshKeyScan").start()
    }

    fun stop() {
        running = false
        synchronized(threads) {
            for (t in threads) t.interrupt()
            threads.clear()
        }
    }

    private fun readLoop(path: String) {
        val size = inputEventSize
        val buffer = ByteArray(size)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        try {
            FileInputStream(path).use { fis ->
                Log.i(TAG, "opened $path (eventSize=$size)")
                while (running) {
                    var offset = 0
                    while (offset < size) {
                        val n = fis.read(buffer, offset, size - offset)
                        if (n < 0) {
                            Log.w(TAG, "EOF on $path")
                            return
                        }
                        offset += n
                    }
                    bb.position(size - 8)
                    val type = bb.short.toInt() and 0xFFFF
                    val code = bb.short.toInt() and 0xFFFF
                    val value = bb.int
                    if (type != EV_KEY) continue
                    Log.i(TAG, "key $path code=$code value=$value")
                    if (code == KEY_REFRESH && value == 0) InputRouter.postManualRefresh()
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "reader interrupted: $path")
        } catch (e: Exception) {
            if (running) Log.e(TAG, "reader error on $path", e)
        }
    }

    
    private fun scanRefreshDevices(): List<String> {
        val result = ArrayList<String>()
        try {
            val process = ProcessBuilder("getevent", "-lp").redirectErrorStream(true).start()
            val content = process.inputStream.bufferedReader().readText()
            process.waitFor()
            for (block in content.split("add device")) {
                if (block.isBlank()) continue
                val path = Regex("/dev/input/event\\d+").find(block)?.value ?: continue
                val name = Regex("name:\\s*\"(.+?)\"").find(block)?.groupValues?.get(1) ?: "?"
                if (Regex("\\bKEY_REFRESH\\b").containsMatchIn(block)) {
                    Log.i(TAG, "refresh key device: $name -> $path")
                    result.add(path)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scan failed", e)
        }
        return result
    }
}
