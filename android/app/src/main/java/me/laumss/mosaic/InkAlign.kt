package me.laumss.mosaic

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import kotlin.math.abs


object InkAlign {
    private const val TAG = "MosaicInkAlign"
    
    private const val PREFS = "mosaic_ink_align_v2"
    private const val KEY_DX = "dx"
    private const val KEY_DY = "dy"
    private const val KEY_N = "n"

    
    private const val PAIR_WINDOW_MS = 600L
    
    private const val SIZE_TOLERANCE_PX = 4f
    
    private const val AMBIGUITY_MARGIN_PX = 1.5f
    
    private const val MAX_DELTA_PX = 15f
    
    private const val OUTLIER_FROM_MEDIAN_PX = 5f
    private const val MIN_SAMPLES_TO_APPLY = 3
    private const val MAX_SAMPLES = 15
    private const val MAX_PENDING = 8
    private const val MAX_RESTARTS = 5

    private val LINE = Regex("""recognition result is \w+,\((-?\d+),(-?\d+)\)\((-?\d+),(-?\d+)\)\((-?\d+),(-?\d+)\)""")
    private val LOGCAT_CMD = arrayOf("logcat", "-T", "1", "-v", "brief", "-s", "drawAPP:*")

    private class Pending(val t: Long, val l: Float, val tp: Float, val r: Float, val b: Float)

    @Volatile var offsetX = 0f
        private set
    @Volatile var offsetY = 0f
        private set

    private val lock = Any()
    private val pending = ArrayDeque<Pending>()
    private val samplesX = ArrayDeque<Float>()
    private val samplesY = ArrayDeque<Float>()
    private var prefs: android.content.SharedPreferences? = null
    private var thread: Thread? = null
    private var process: Process? = null
    @Volatile private var generation = 0

    fun start(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            prefs = p
            offsetX = p.getFloat(KEY_DX, 0f)
            offsetY = p.getFloat(KEY_DY, 0f)
            if (abs(offsetX) > MAX_DELTA_PX || abs(offsetY) > MAX_DELTA_PX) {
                Log.w(TAG, "persisted offset ($offsetX,$offsetY) out of range; discarded")
                offsetX = 0f; offsetY = 0f
                p.edit().clear().apply()
            }
            val n = p.getInt(KEY_N, 0)
            Log.i(TAG, "start offset=($offsetX,$offsetY) persistedSamples=$n")
            if (n == 0 && offsetX == 0f && offsetY == 0f) {
                Log.i(TAG, "no persisted calibration; first 3 strokes will calibrate")
            }
            if (thread == null) {
                val gen = ++generation
                thread = Thread({ readLoop(gen) }, "MosaicInkAlign").also { it.isDaemon = true; it.start() }
            }
        }
    }

    
    fun setBaseline(bx: Float, by: Float) {
        synchronized(lock) {
            val n = prefs?.getInt(KEY_N, 0) ?: 0
            val consistent = n >= MIN_SAMPLES_TO_APPLY &&
                abs(offsetX - bx) <= OUTLIER_FROM_MEDIAN_PX && abs(offsetY - by) <= OUTLIER_FROM_MEDIAN_PX
            if (!consistent) {
                offsetX = bx
                offsetY = by
                samplesX.clear(); samplesY.clear()
                prefs?.edit()?.clear()?.apply()
            }
            Log.i(TAG, "baseline=($bx,$by) persistedSamples=$n keptPersisted=$consistent offset=($offsetX,$offsetY)")
        }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            process?.destroy()
            process = null
            thread = null
            pending.clear()
        }
    }

    
    fun onPenUp(l: Float, t: Float, r: Float, b: Float) {
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            pending.addLast(Pending(now, l, t, r, b))
            while (pending.size > MAX_PENDING) pending.removeFirst()
        }
        Log.i(TAG, "ours up bbox=[${l.toInt()},${t.toInt()}-${r.toInt()},${b.toInt()}] offset=($offsetX,$offsetY)")
    }

    private fun readLoop(gen: Int) {
        var restarts = 0
        while (gen == generation) {
            var proc: Process? = null
            try {
                proc = ProcessBuilder(*LOGCAT_CMD).redirectErrorStream(true).start()
                synchronized(lock) { process = proc }
                Log.i(TAG, "logcat attached restarts=$restarts")
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (gen == generation) {
                        val line = reader.readLine() ?: break
                        onLogLine(line)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "logcat failed: ${e.message}")
            } finally {
                try { proc?.destroy() } catch (_: Throwable) {}
            }
            if (gen != generation) return
            if (++restarts > MAX_RESTARTS) {
                Log.w(TAG, "logcat died $restarts times; alignment stays at ($offsetX,$offsetY)")
                return
            }
            try { Thread.sleep(500L * restarts) } catch (_: InterruptedException) { return }
        }
    }

    private fun onLogLine(line: String) {
        val m = LINE.find(line) ?: return
        val fl = m.groupValues[1].toFloat(); val ft = m.groupValues[2].toFloat()
        val fr = m.groupValues[5].toFloat(); val fb = m.groupValues[6].toFloat()
        val now = SystemClock.uptimeMillis()
        val fw = "fw bbox=[${fl.toInt()},${ft.toInt()}-${fr.toInt()},${fb.toInt()}]"
        val fwW = fr - fl
        val fwH = fb - ft
        val match: Pending?
        var ambiguous = false
        synchronized(lock) {
            while (pending.isNotEmpty() && now - pending.first.t > PAIR_WINDOW_MS) pending.removeFirst()
            
            var best: Pending? = null
            var bestScore = Float.MAX_VALUE
            var secondScore = Float.MAX_VALUE
            for (p in pending) {
                val dw = abs(fwW - (p.r - p.l))
                val dh = abs(fwH - (p.b - p.tp))
                if (dw > SIZE_TOLERANCE_PX || dh > SIZE_TOLERANCE_PX) continue
                val score = dw + dh
                if (score < bestScore) { secondScore = bestScore; bestScore = score; best = p }
                else if (score < secondScore) secondScore = score
            }
            if (best != null && secondScore - bestScore < AMBIGUITY_MARGIN_PX) {
                ambiguous = true
                
                pending.removeAll { p ->
                    abs(fwW - (p.r - p.l)) <= SIZE_TOLERANCE_PX && abs(fwH - (p.b - p.tp)) <= SIZE_TOLERANCE_PX
                }
                match = null
            } else {
                match = best
                if (match != null) pending.remove(match)
            }
        }
        if (match == null) {
            Log.i(TAG, "$fw ${if (ambiguous) "ambiguous" else "unmatched"} pending=${pending.size}")
            return
        }
        val dx = (fl + fr) / 2f - (match.l + match.r) / 2f
        val dy = (ft + fb) / 2f - (match.tp + match.b) / 2f
        val dw = fwW - (match.r - match.l)
        val dh = fwH - (match.b - match.tp)
        val lag = now - match.t
        if (abs(dx) > MAX_DELTA_PX || abs(dy) > MAX_DELTA_PX) {
            Log.i(TAG, "$fw delta=(%.1f,%.1f) lag=%dms rejected:range".format(dx, dy, lag))
            return
        }
        val mx: Float; val my: Float; val n: Int
        var outlier = false
        synchronized(lock) {
            
            
            if (samplesX.size >= MIN_SAMPLES_TO_APPLY) {
                val cmx = median(samplesX); val cmy = median(samplesY)
                if (abs(dx - cmx) > OUTLIER_FROM_MEDIAN_PX || abs(dy - cmy) > OUTLIER_FROM_MEDIAN_PX) outlier = true
            }
            if (!outlier) {
                samplesX.addLast(dx); samplesY.addLast(dy)
                while (samplesX.size > MAX_SAMPLES) { samplesX.removeFirst(); samplesY.removeFirst() }
            }
            n = samplesX.size
            mx = if (n > 0) median(samplesX) else offsetX
            my = if (n > 0) median(samplesY) else offsetY
            if (!outlier && n >= MIN_SAMPLES_TO_APPLY) {
                offsetX = mx; offsetY = my
                prefs?.edit()?.putFloat(KEY_DX, mx)?.putFloat(KEY_DY, my)?.putInt(KEY_N, n)?.apply()
            }
        }
        Log.i(
            TAG,
            "$fw ours=[${match.l.toInt()},${match.tp.toInt()}-${match.r.toInt()},${match.b.toInt()}] " +
                "delta=(%.1f,%.1f) sizeDiff=(%.1f,%.1f) lag=%dms n=%d median=(%.1f,%.1f) %s"
                    .format(dx, dy, dw, dh, lag, n, mx, my,
                        if (outlier) "rejected:outlier" else "applied=${n >= MIN_SAMPLES_TO_APPLY}"),
        )
    }

    private fun median(values: ArrayDeque<Float>): Float {
        val sorted = values.toFloatArray().also { it.sort() }
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
