package me.laumss.mosaic

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import android.view.WindowManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.ratta.supernote.plugincommon.data.common.trail.Element
import com.ratta.supernote.pluginlib.api.HostCommonAPI
import com.ratta.supernote.pluginlib.api.HostDataCacheAPI
import com.ratta.supernote.pluginlib.core.PluginAppAPI
import com.ratta.supernote.pluginlib.modules.PluginModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min


class MosaicNoteShotModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "MosaicNoteShotNative"
        private const val MODULE_NAME = "MosaicNoteShot"
        private const val RESTORE_EVENT = "MosaicRestoreTarget"
        
        private const val USERDATA_MARKER = "mosaicShot"
        
        private const val SHOT_STORE_FILENAME = "note-shots.json"
        
        private const val MAX_SIGNATURE_DISTANCE = 24
        
        private const val MAX_ASPECT_DIFF = 0.04f
        private const val TAP_MAX_DURATION_MS = 450L
        private const val TAP_MAX_DISTANCE_PX = 32f
        private const val HIT_PADDING_PX = 10f
        
        private const val MIN_HIT_PX = 72f
        private const val DEFAULT_HOTSPOT_WIDTH = 0.3f
        private const val DEFAULT_HOTSPOT_HEIGHT = 0.08f
        private const val QUERY_TIMEOUT_MS = 8_000L
        private const val RESTORE_EMIT_DELAY_MS = 450L
        private const val TOOL_TYPE_FINGER = 1

        @Volatile private var boardVisible = false
        @Volatile private var activeInstance: MosaicNoteShotModule? = null

        @JvmStatic
        fun updateBoardVisibility(visible: Boolean) {
            boardVisible = visible
            activeInstance?.onBoardVisibilityChanged(visible)
        }

        
        @JvmStatic
        fun currentHostNotePath(): String? = try {
            HostDataCacheAPI.getInstance()
                ?.currentFilePath
                ?.takeIf { it.endsWith(".note", ignoreCase = true) }
        } catch (error: Throwable) {
            Log.w(TAG, "note context read failed: $error")
            null
        }

        
        @JvmStatic
        fun parseShotUserData(raw: String?): JSONObject? {
            if (raw.isNullOrBlank()) return null
            val parsed = try {
                JSONObject(raw)
            } catch (_: Throwable) {
                return null
            }
            if (parsed.has(USERDATA_MARKER)) return parsed.takeIf { it.optString("id").isNotEmpty() }
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val inner = parsed.optString(keys.next())
                if (!inner.contains(USERDATA_MARKER)) continue
                val shot = try {
                    JSONObject(inner)
                } catch (_: Throwable) {
                    continue
                }
                if (shot.has(USERDATA_MARKER) && shot.optString("id").isNotEmpty()) return shot
            }
            return null
        }
    }

    private data class TapDown(
        val x: Float,
        val y: Float,
        val toolType: Int,
        val at: Long,
        var maxDistance: Float = 0f,
    )

    
    private class Hit(val id: String, val num: Int, val region: JSONArray?, val fingerprint: String, val adopt: Boolean)

    
    private class ShotVersion(
        val id: String,
        val updatedAt: String,
        val width: Int,
        val height: Int,
        val signature: String,
        val region: JSONArray?,
        val fingerprint: String,
        val hotspot: JSONArray?,
    )

    
    private class CopyMatch(val version: ShotVersion, val distance: Int)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queryBusy = AtomicBoolean(false)
    private val querySequence = AtomicLong(0L)
    private val tapLock = Any()
    private val pendingLock = Any()
    @Volatile private var activeQueryToken = 0L
    private var tapDown: TapDown? = null
    private var pendingRestoreJson: String? = null
    private var inputReader: InputReader? = null
    private val versionsLock = Any()
    
    private var versionsIndex: List<ShotVersion> = emptyList()
    private var versionsStamp = 0L to -1L

    override fun getName(): String = MODULE_NAME

    override fun initialize() {
        super.initialize()
        activeInstance = this
        inputReader = InputReader(
            reactApplicationContext,
            routeToBoard = false,
            readPenEvents = false,
            rawEventListener = ::onRawInput,
        ).also { it.start() }
        Log.i(TAG, "background note tap monitor started")
    }

    override fun invalidate() {
        inputReader?.stop()
        inputReader = null
        if (activeInstance === this) activeInstance = null
        super.invalidate()
        Log.i(TAG, "background note tap monitor stopped")
    }

    
    @ReactMethod
    fun takePendingRestore(promise: Promise) {
        val value = synchronized(pendingLock) {
            pendingRestoreJson.also { pendingRestoreJson = null }
        }
        promise.resolve(value)
    }

    
    @ReactMethod
    fun pictureSignature(path: String, aspect: Double, promise: Promise) {
        Thread({
            val signature = PictureSignature.of(path, aspect.toFloat())
            promise.resolve(signature?.let {
                Arguments.createMap().apply {
                    putInt("width", it.width)
                    putInt("height", it.height)
                    putString("sig", it.hash)
                }
            })
        }, "MosaicShotSignature").start()
    }

    private fun onBoardVisibilityChanged(visible: Boolean) {
        synchronized(tapLock) { tapDown = null }
        Log.i(TAG, "board visibility=$visible")
        if (visible) cancelActiveQuery("board-visible")
    }

    private fun onRawInput(action: Int, x: Float, y: Float, toolType: Int, pointerCount: Int) {
        if (
            boardVisible ||
            !isNotePageForeground() ||
            currentHostNotePath() == null ||
            toolType != TOOL_TYPE_FINGER
        ) {
            synchronized(tapLock) { tapDown = null }
            return
        }
        val now = SystemClock.uptimeMillis()
        synchronized(tapLock) {
            when (action) {
                0 -> tapDown = if (pointerCount == 1) TapDown(x, y, toolType, now) else null
                2 -> tapDown?.let { down ->
                    if (pointerCount != 1 || down.toolType != toolType) {
                        tapDown = null
                    } else {
                        down.maxDistance = maxOf(down.maxDistance, hypot(x - down.x, y - down.y))
                    }
                }
                3 -> tapDown = null
                1 -> {
                    val down = tapDown
                    tapDown = null
                    if (down == null || pointerCount != 1 || down.toolType != toolType) return
                    val distance = maxOf(down.maxDistance, hypot(x - down.x, y - down.y))
                    val duration = now - down.at
                    if (duration <= TAP_MAX_DURATION_MS && distance <= TAP_MAX_DISTANCE_PX) {
                        Log.i(TAG, "note tap at=(${x.toInt()},${y.toInt()}) duration=${duration}ms move=${distance.toInt()}px")
                        inspectTap(x, y)
                    }
                }
            }
        }
    }

    private fun pluginApp(): PluginAppAPI? =
        reactApplicationContext.getNativeModule(PluginModule::class.java)?.pluginApp

    @Suppress("DEPRECATION")
    private fun isNotePageForeground(): Boolean = try {
        val manager = reactApplicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val top = manager?.getRunningTasks(1)?.firstOrNull()?.topActivity
        top != null &&
            top.packageName in setOf("com.ratta.supernote.note", "com.supernote.note") &&
            top.className.endsWith("NoteInsidePagesActivity")
    } catch (error: Throwable) {
        Log.w(TAG, "foreground note context read failed: $error")
        false
    }

    
    private fun inspectTap(x: Float, y: Float) {
        if (boardVisible) return
        val filePath = currentHostNotePath() ?: return
        val token = beginQuery() ?: return
        val app = pluginApp()
        val host = HostCommonAPI.getInstance()
        if (app == null || host == null) {
            finishQuery(token, "skipped app=${app != null} host=${host != null}")
            return
        }
        host.getCurrentPageNum(app) pageCallback@{ pageResponse ->
            if (!queryIsActive(token)) return@pageCallback
            val page = if (pageResponse.isSuccess) pageResponse.getResult(Integer::class.java)?.toInt() else null
            if (page == null) {
                finishQuery(token, "page unavailable")
                return@pageCallback
            }
            host.getPageSize(app, filePath, page) pageSizeCallback@{ pageSizeResponse ->
                if (!queryIsActive(token)) return@pageSizeCallback
                val pageSize = if (pageSizeResponse.isSuccess) pageSizeResponse.getResult(SizeF::class.java) else null
                if (pageSize == null || pageSize.width <= 0f || pageSize.height <= 0f) {
                    finishQuery(token, "page size unavailable page=$page")
                    return@pageSizeCallback
                }
                host.getElements(app, page, filePath) elementsCallback@{ elementsResponse ->
                    if (!queryIsActive(token)) return@elementsCallback
                    val cachePath = if (elementsResponse.isSuccess) elementsResponse.getResult(String::class.java) else null
                    if (cachePath.isNullOrEmpty()) {
                        finishQuery(token, "elements unavailable page=$page")
                        return@elementsCallback
                    }
                    val elements = try {
                        app.readElementFromFile(cachePath)
                    } catch (error: Throwable) {
                        Log.w(TAG, "read elements failed: $error")
                        emptyList()
                    }
                    val hit = try {
                        hitTest(app, elements, pageSize, currentDisplaySize(), x, y, page)
                    } finally {
                        recycleElements(elements)
                    }
                    if (hit == null) {
                        finishQuery(token, "miss page=$page tap=(${x.toInt()},${y.toInt()})")
                        return@elementsCallback
                    }
                    val target = JSONObject()
                        .put("id", hit.id)
                        .put("notePath", filePath)
                        .put("page", page)
                        .put("num", hit.num)
                        .put("adopt", hit.adopt)
                    if (hit.fingerprint.isNotEmpty()) target.put("f", hit.fingerprint)
                    hit.region?.let { target.put("r", it) }
                    synchronized(pendingLock) { pendingRestoreJson = target.toString() }
                    if (!finishQuery(token, "hit id=${hit.id} page=$page num=${hit.num} adopt=${hit.adopt}")) return@elementsCallback
                    UiThreadUtil.runOnUiThread {
                        if (!boardVisible && isNotePageForeground()) {
                            app.showPluginView()
                            mainHandler.postDelayed({ emitRestoreSignal() }, RESTORE_EMIT_DELAY_MS)
                        } else {
                            Log.i(TAG, "show gated visible=$boardVisible foreground=${isNotePageForeground()}")
                        }
                    }
                }
            }
        }
    }

    
    private fun hitTest(
        app: PluginAppAPI,
        elements: List<Element>,
        pageSize: SizeF,
        display: Point,
        x: Float,
        y: Float,
        page: Int,
    ): Hit? {
        val sx = display.x / pageSize.width
        val sy = display.y / pageSize.height
        for (element in elements.asReversed()) {
            if (element.type != Element.TRAIL_TYPE_PICTURE) continue
            val rect = element.picture?.rect ?: continue
            val screen = RectF(rect.left * sx, rect.top * sy, rect.right * sx, rect.bottom * sy)
            if (!contains(screen, x, y)) continue
            val num = element.trailNumInPage
            val tap = "(${x.toInt()},${y.toInt()})"
            val shot = parseShotUserData(element.userData)
            if (shot != null) {
                val id = shot.optString("id")
                val hotspot = hotspotRect(screen, shot.optJSONArray("hs"))
                val inside = contains(hotspot, x, y)
                Log.i(TAG, "hit-test page=$page num=$num id=$id pageRect=$rect screen=$screen hotspot=$hotspot tap=$tap inside=$inside")
                return if (inside) Hit(id, num, shot.optJSONArray("r"), shot.optString("f"), adopt = false) else null
            }
            val copy = matchCopy(app, element.picture?.picturePath, rect.width().toFloat() / max(1, rect.height()))
            if (copy == null) {
                Log.i(TAG, "hit-test page=$page num=$num picture without Mosaic data pageRect=$rect tap=$tap")
                return null
            }
            val version = copy.version
            val hotspot = hotspotRect(screen, version.hotspot)
            val inside = contains(hotspot, x, y)
            Log.i(
                TAG,
                "hit-test page=$page num=$num copy-of=${version.id} distance=${copy.distance} pageRect=$rect " +
                    "screen=$screen hotspot=$hotspot tap=$tap inside=$inside",
            )
            return if (inside) Hit(version.id, num, version.region, version.fingerprint, adopt = true) else null
        }
        Log.i(TAG, "hit-test page=$page pageSize=${pageSize.width}x${pageSize.height} display=${display.x}x${display.y} no picture under tap")
        return null
    }

    
    private fun matchCopy(app: PluginAppAPI, picturePath: String?, aspect: Float): CopyMatch? {
        val signature = PictureSignature.of(picturePath, aspect)
        if (signature == null) {
            Log.i(TAG, "copy match skipped: picture unreadable path=$picturePath")
            return null
        }
        var best: CopyMatch? = null
        
        var closestId: String? = null
        var closestDistance = Int.MAX_VALUE
        val aspect = signature.width.toFloat() / max(1, signature.height)
        for (version in loadVersions(app)) {
            val versionAspect = version.width.toFloat() / max(1, version.height)
            if (abs(versionAspect - aspect) > versionAspect * MAX_ASPECT_DIFF) continue
            val distance = PictureSignature.distance(version.signature, signature.hash)
            if (distance < closestDistance) {
                closestDistance = distance
                closestId = version.id
            }
            if (distance > MAX_SIGNATURE_DISTANCE) continue
            val current = best
            if (current == null || distance < current.distance ||
                (distance == current.distance && version.updatedAt > current.version.updatedAt)
            ) {
                best = CopyMatch(version, distance)
            }
        }
        Log.i(
            TAG,
            "copy match content=${signature.width}x${signature.height} best=${best?.version?.id} distance=${best?.distance} " +
                "closest=$closestId/${if (closestId == null) "-" else closestDistance}",
        )
        return best
    }

    
    private fun loadVersions(app: PluginAppAPI): List<ShotVersion> = synchronized(versionsLock) {
        val file = File(app.pluginPath, SHOT_STORE_FILENAME)
        val stamp = file.lastModified() to file.length()
        if (stamp == versionsStamp) return@synchronized versionsIndex
        val index = ArrayList<ShotVersion>()
        try {
            val shots = if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)).optJSONObject("shots") else null
            if (shots != null) {
                val ids = shots.keys()
                while (ids.hasNext()) {
                    val id = ids.next()
                    val shot = shots.optJSONObject(id) ?: continue
                    val versions = shot.optJSONArray("versions") ?: continue
                    val updatedAt = shot.optString("updatedAt")
                    for (i in 0 until versions.length()) {
                        val version = versions.optJSONObject(i) ?: continue
                        index.add(
                            ShotVersion(
                                id, updatedAt, version.optInt("w"), version.optInt("h"), version.optString("sig"),
                                version.optJSONArray("r"), version.optString("f"), version.optJSONArray("hs"),
                            ),
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "shot store read failed: $error")
        }
        versionsIndex = index
        versionsStamp = stamp
        Log.i(TAG, "shot versions indexed count=${index.size}")
        index
    }

    private fun contains(rect: RectF, x: Float, y: Float): Boolean =
        x >= rect.left - HIT_PADDING_PX && x <= rect.right + HIT_PADDING_PX &&
            y >= rect.top - HIT_PADDING_PX && y <= rect.bottom + HIT_PADDING_PX

    
    private fun hotspotRect(picture: RectF, hs: JSONArray?): RectF {
        val nx = (hs?.optDouble(0, 0.0)?.toFloat() ?: 0f).coerceIn(0f, 1f)
        val ny = (hs?.optDouble(1, 0.0)?.toFloat() ?: 0f).coerceIn(0f, 1f)
        val nw = (hs?.optDouble(2, DEFAULT_HOTSPOT_WIDTH.toDouble())?.toFloat() ?: DEFAULT_HOTSPOT_WIDTH).coerceIn(0f, 1f - nx)
        val nh = (hs?.optDouble(3, DEFAULT_HOTSPOT_HEIGHT.toDouble())?.toFloat() ?: DEFAULT_HOTSPOT_HEIGHT).coerceIn(0f, 1f - ny)
        val left = picture.left + picture.width() * nx
        val top = picture.top + picture.height() * ny
        val right = min(picture.right, left + max(picture.width() * nw, MIN_HIT_PX))
        val bottom = min(picture.bottom, top + max(picture.height() * nh, MIN_HIT_PX))
        return RectF(left, top, right, bottom)
    }

    private fun currentDisplaySize(): Point {
        val point = Point()
        try {
            val manager = reactApplicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            manager?.defaultDisplay?.getRealSize(point)
        } catch (error: Throwable) {
            Log.w(TAG, "display size read failed: $error")
        }
        if (point.x <= 0 || point.y <= 0) {
            point.x = reactApplicationContext.resources.displayMetrics.widthPixels
            point.y = reactApplicationContext.resources.displayMetrics.heightPixels
        }
        return point
    }

    private fun beginQuery(): Long? {
        
        if (queryBusy.get()) cancelActiveQuery("tap-supersedes")
        if (!queryBusy.compareAndSet(false, true)) return null
        val token = querySequence.incrementAndGet()
        activeQueryToken = token
        mainHandler.postDelayed({
            if (activeQueryToken == token && queryBusy.compareAndSet(true, false)) {
                activeQueryToken = 0L
                Log.w(TAG, "inspect timeout token=$token")
            }
        }, QUERY_TIMEOUT_MS)
        return token
    }

    private fun queryIsActive(token: Long): Boolean =
        queryBusy.get() && activeQueryToken == token

    private fun cancelActiveQuery(reason: String) {
        val token = activeQueryToken
        if (token == 0L) return
        activeQueryToken = 0L
        queryBusy.set(false)
        Log.i(TAG, "inspect cancelled token=$token reason=$reason")
    }

    private fun finishQuery(token: Long, message: String): Boolean {
        if (!queryIsActive(token)) return false
        activeQueryToken = 0L
        queryBusy.set(false)
        Log.i(TAG, "inspect done $message")
        return true
    }

    private fun recycleElements(elements: List<Element>) {
        for (element in elements) {
            try {
                element.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun emitRestoreSignal() {
        try {
            val event = Arguments.createMap().apply { putBoolean("native", true) }
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(RESTORE_EVENT, event)
            Log.i(TAG, "restore signal emitted")
        } catch (error: Throwable) {
            Log.w(TAG, "restore signal delayed: $error")
        }
    }
}


internal object PictureSignature {
    private const val TAG = "MosaicNoteShotNative"
    private const val GRID = 16
    
    private const val CONTENT_LUMA = 235
    
    private const val EDGE_IGNORE_PX = 8
    
    private const val HASH_DELTA = 0.75
    
    private const val FULL_DECODE_PIXELS = 4_000_000L
    
    private const val EXTRACT_LOOKBACK_MS = 5_000L
    
    private const val MAX_CANDIDATES = 6
    
    private const val ASPECT_TOLERANCE = 0.05f

    
    class Signature(val width: Int, val height: Int, val hash: String)

    
    fun of(path: String?, aspect: Float = 0f): Signature? {
        if (path.isNullOrEmpty()) return null
        val file = existingFile(path, aspect) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > FULL_DECODE_PIXELS) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return null
            try {
                signatureOf(bitmap, sample)
            } finally {
                bitmap.recycle()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "picture signature failed path=$path: $error")
            null
        }
    }

    private fun signatureOf(bitmap: Bitmap, sample: Int): Signature {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        
        val edge = (EDGE_IGNORE_PX / sample).coerceAtLeast(1).takeIf { width > it * 4 && height > it * 4 } ?: 0
        var left = width
        var right = -1
        var top = -1
        var bottom = -1
        for (y in edge until height - edge) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var first = -1
            var last = -1
            for (x in edge until width - edge) {
                if (luma(row[x]) < CONTENT_LUMA) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            if (top < 0) top = y
            bottom = y
            if (first < left) left = first
            if (last > right) right = last
        }
        if (top < 0) {
            left = 0
            right = width - 1
            top = 0
            bottom = height - 1
        }
        val contentWidth = right - left + 1
        val contentHeight = bottom - top + 1
        
        val columns = GRID + 1
        val sums = DoubleArray(columns * GRID)
        val counts = IntArray(columns * GRID)
        for (y in top..bottom) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val cellRow = ((y - top).toLong() * GRID / contentHeight).toInt() * columns
            for (x in left..right) {
                val cell = cellRow + ((x - left).toLong() * columns / contentWidth).toInt()
                sums[cell] += luma(row[x]).toDouble()
                counts[cell]++
            }
        }
        val averages = DoubleArray(sums.size) { if (counts[it] > 0) sums[it] / counts[it] else 255.0 }
        val hex = StringBuilder(GRID * GRID / 4)
        for (gy in 0 until GRID) {
            var nibble = 0
            for (gx in 0 until GRID) {
                val here = averages[gy * columns + gx]
                val next = averages[gy * columns + gx + 1]
                nibble = (nibble shl 1) or (if (next > here + HASH_DELTA) 1 else 0)
                if (gx % 4 == 3) {
                    hex.append(Character.forDigit(nibble, 16))
                    nibble = 0
                }
            }
        }
        return Signature(contentWidth * sample, contentHeight * sample, hex.toString())
    }

    
    private fun existingFile(path: String, aspect: Float): File? {
        val reported = File(path)
        val candidates = ArrayList<File>(MAX_CANDIDATES + 1)
        if (reported.exists()) candidates.add(reported)
        val stamp = reported.nameWithoutExtension.toLongOrNull()
        val parent = reported.parentFile
        if (stamp != null && parent != null) {
            parent.listFiles()
                ?.mapNotNull { file ->
                    val time = file.nameWithoutExtension.toLongOrNull()
                    if (time != null && time < stamp && time >= stamp - EXTRACT_LOOKBACK_MS &&
                        file.extension.equals(reported.extension, ignoreCase = true)
                    ) time to file else null
                }
                ?.sortedByDescending { it.first }
                ?.take(MAX_CANDIDATES)
                ?.forEach { candidates.add(it.second) }
        }
        for (candidate in candidates) {
            if (aspect > 0f) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(candidate.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                val candidateAspect = bounds.outWidth.toFloat() / bounds.outHeight
                if (abs(candidateAspect - aspect) > aspect * ASPECT_TOLERANCE) continue
            }
            if (candidate !== reported) Log.i(TAG, "picture path resolved $path -> ${candidate.name}")
            return candidate
        }
        Log.i(TAG, "picture file unresolved path=$path candidates=${candidates.size} aspect=$aspect")
        return null
    }

    
    fun distance(a: String, b: String): Int {
        if (a.isEmpty() || a.length != b.length) return Int.MAX_VALUE
        var bits = 0
        for (i in a.indices) {
            val x = Character.digit(a[i], 16)
            val y = Character.digit(b[i], 16)
            if (x < 0 || y < 0) return Int.MAX_VALUE
            bits += Integer.bitCount(x xor y)
        }
        return bits
    }

    
    private fun luma(argb: Int): Int {
        val a = (argb ushr 24) and 0xff
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        val y = (r * 299 + g * 587 + b * 114) / 1000
        return (y * a + 255 * (255 - a)) / 255
    }
}
