package me.laumss.mosaic

import android.app.ActivityManager
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
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
import kotlin.math.hypot


class MosaicNoteShotModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "MosaicNoteShotNative"
        private const val MODULE_NAME = "MosaicNoteShot"
        private const val RESTORE_EVENT = "MosaicRestoreTarget"
        private const val REGISTRY_FILENAME = "mosaic-note-shot-links.json"
        private const val USERDATA_PREFIX = "MOSAIC:"
        private const val TAP_MAX_DURATION_MS = 450L
        private const val TAP_MAX_DISTANCE_PX = 32f
        private const val HIT_PADDING_PX = 8f
        private const val DEFAULT_HOTSPOT_WIDTH = 0.36f
        private const val DEFAULT_HOTSPOT_HEIGHT = 0.08f
        private const val QUERY_TIMEOUT_MS = 8_000L
        private const val TOOL_TYPE_FINGER = 1

        @Volatile private var boardVisible = false
        @Volatile private var activeInstance: MosaicNoteShotModule? = null

        @JvmStatic
        fun updateBoardVisibility(visible: Boolean) {
            boardVisible = visible
            activeInstance?.onBoardVisibilityChanged(visible)
        }
    }

    private data class TapDown(
        val x: Float,
        val y: Float,
        val toolType: Int,
        val at: Long,
        var maxDistance: Float = 0f,
    )

    private data class PictureRecord(
        val element: Element,
        val key: String,
        val basename: String,
    )

    private data class ResolvedMeta(
        val json: String,
        val justBound: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queryBusy = AtomicBoolean(false)
    private val querySequence = AtomicLong(0L)
    private val tapLock = Any()
    private val pendingLock = Any()
    @Volatile private var activeQueryToken = 0L
    private var tapDown: TapDown? = null
    private var pendingRestoreJson: String? = null
    private var inputReader: InputReader? = null

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

    private fun onBoardVisibilityChanged(visible: Boolean) {
        synchronized(tapLock) { tapDown = null }
        Log.i(TAG, "board visibility=$visible")
        if (visible) {
            cancelActiveQuery("board-visible")
            return
        }
        for (delay in longArrayOf(700L, 1800L, 3400L, 5200L)) {
            mainHandler.postDelayed({
                if (!boardVisible) inspectCurrentNote(null, null, allowShow = false, reason = "warm-$delay")
            }, delay)
        }
    }

    private fun onRawInput(action: Int, x: Float, y: Float, toolType: Int, pointerCount: Int) {
        if (
            boardVisible ||
            !isNotePageForeground() ||
            currentNotePath() == null ||
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
                        Log.i(TAG, "note tap tool=$toolType at=(${x.toInt()},${y.toInt()}) duration=${duration}ms move=${distance.toInt()}px")
                        inspectCurrentNote(x, y, allowShow = true, reason = "tap")
                    }
                }
            }
        }
    }

    private fun pluginApp(): PluginAppAPI? =
        reactApplicationContext.getNativeModule(PluginModule::class.java)?.pluginApp

    private fun currentNotePath(): String? = try {
        HostDataCacheAPI.getInstance()
            ?.currentFilePath
            ?.takeIf { it.endsWith(".note", ignoreCase = true) }
    } catch (error: Throwable) {
        Log.w(TAG, "note context read failed: $error")
        null
    }

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

    private fun inspectCurrentNote(x: Float?, y: Float?, allowShow: Boolean, reason: String) {
        if (boardVisible) return
        if (!isNotePageForeground()) {
            Log.i(TAG, "inspect gated context=note-page-background reason=$reason")
            return
        }
        val filePath = currentNotePath()
        if (filePath == null) {
            Log.i(TAG, "inspect skipped context=outside-note reason=$reason")
            return
        }
        val token = beginQuery(allowShow, reason) ?: return
        val app = pluginApp()
        val host = HostCommonAPI.getInstance()
        if (app == null || host == null) {
            finishQuery(token, "skipped app=${app != null} host=${host != null} reason=$reason")
            return
        }

        host.getCurrentPageNum(app) pageCallback@{ pageResponse ->
            if (!queryIsActive(token)) return@pageCallback
            val page = if (pageResponse.isSuccess) pageResponse.getResult(Integer::class.java)?.toInt() else null
            if (page == null) {
                finishQuery(token, "page unavailable reason=$reason")
                return@pageCallback
            }
            host.getPageSize(app, filePath, page) pageSizeCallback@{ pageSizeResponse ->
                if (!queryIsActive(token)) return@pageSizeCallback
                val pageSize = if (pageSizeResponse.isSuccess) {
                    pageSizeResponse.getResult(SizeF::class.java)
                } else null
                if (pageSize == null || pageSize.width <= 0f || pageSize.height <= 0f) {
                    finishQuery(token, "page size unavailable page=$page reason=$reason")
                    return@pageSizeCallback
                }
                val displaySize = currentDisplaySize()
                host.getElements(app, page, filePath) elementsCallback@{ elementsResponse ->
                if (!queryIsActive(token)) return@elementsCallback
                val cachePath = if (elementsResponse.isSuccess) elementsResponse.getResult(String::class.java) else null
                if (cachePath.isNullOrEmpty()) {
                    finishQuery(token, "elements unavailable page=$page reason=$reason")
                    return@elementsCallback
                }
                val elements = try {
                    app.readElementFromFile(cachePath)
                } catch (error: Throwable) {
                    Log.w(TAG, "read elements failed: $error")
                    emptyList()
                }
                val pictures = elements
                    .filter { it.type == Element.TRAIL_TYPE_PICTURE && it.picture != null }
                    .map { element ->
                        val path = element.picture.picturePath.orEmpty()
                        PictureRecord(element, pictureKey(element), File(path).name)
                    }
                Log.i(
                    TAG,
                    "scan page=$page pageSize=${pageSize.width}x${pageSize.height} display=${displaySize.x}x${displaySize.y} reason=$reason pictures=${pictures.joinToString(prefix = "[", postfix = "]") { "${it.key}@${it.element.picture.rect}:${it.basename}" }}",
                )
                val resolved = resolveRegistry(app, filePath, page, pictures)
                if (!queryIsActive(token)) {
                    recycleElements(elements)
                    return@elementsCallback
                }
                if (!allowShow || x == null || y == null || pictures.isEmpty()) {
                    recycleElements(elements)
                    finishQuery(token, "bound=${resolved.count { it.value.justBound }} pictures=${pictures.size} reason=$reason")
                    return@elementsCallback
                }
                var hit: ResolvedMeta? = null
                var hitKey: String? = null
                for (picture in pictures) {
                    val legacy = decodeLegacyMeta(picture.element.userData)
                    val meta = legacy?.let { ResolvedMeta(it, false) } ?: resolved[picture.key]
                    val rect = picture.element.picture.rect
                    if (meta == null || rect == null) continue
                    val screenRect = pictureRectToScreen(rect, picture.element, pageSize, displaySize)
                    if (screenRect == null) continue
                    val screen = titleHotspotRect(
                        screenRect.left, screenRect.top, screenRect.right, screenRect.bottom, meta.json,
                    )
                    Log.i(
                        TAG,
                        "hit-test key=${picture.key} hotspot=$screen tap=(${x.toInt()},${y.toInt()}) newlyBound=${meta.justBound}",
                    )
                    if (!meta.justBound && contains(screen, x, y, HIT_PADDING_PX)) {
                        hit = meta
                        hitKey = picture.key
                        break
                    }
                }
                recycleElements(elements)
                if (hit == null) {
                    finishQuery(token, "miss page=$page pictures=${pictures.size} links=${resolved.size} tap=(${x.toInt()},${y.toInt()})")
                    return@elementsCallback
                }
                Log.i(TAG, "hit key=$hitKey page=$page tap=(${x.toInt()},${y.toInt()})")
                synchronized(pendingLock) { pendingRestoreJson = hit.json }
                if (!isNotePageForeground()) {
                    finishQuery(token, "show gated context=note-page-background key=$hitKey page=$page")
                    return@elementsCallback
                }
                if (!finishQuery(token, "hit key=$hitKey page=$page")) return@elementsCallback
                UiThreadUtil.runOnUiThread {
                    if (!boardVisible && isNotePageForeground()) {
                        app.showPluginView()
                        mainHandler.postDelayed({ emitRestoreSignal() }, 450L)
                    } else {
                        Log.i(TAG, "show gated visible=$boardVisible foreground=${isNotePageForeground()}")
                    }
                }
                }
            }
        }
    }

    private fun currentDisplaySize(): Point {
        val point = Point()
        try {
            val manager = reactApplicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
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

    private fun emrPointToPage(
        x: Float,
        y: Float,
        pageSize: SizeF,
        maxX: Int,
        maxY: Int,
    ): Pair<Float, Float>? {
        if (pageSize.width <= 1f || pageSize.height <= 1f || maxX <= 0 || maxY <= 0) return null
        val sourceX = x / (maxX.toFloat() / (pageSize.height - 1f))
        val sourceY = y / (maxY.toFloat() / (pageSize.width - 1f))
        return pageSize.width - 1f - sourceY to sourceX
    }

    private fun pictureRectToScreen(
        rect: Rect,
        element: Element,
        pageSize: SizeF,
        displaySize: Point,
    ): RectF? {
        val corners = listOf(
            emrPointToPage(rect.left.toFloat(), rect.top.toFloat(), pageSize, element.maxX, element.maxY),
            emrPointToPage(rect.right.toFloat(), rect.top.toFloat(), pageSize, element.maxX, element.maxY),
            emrPointToPage(rect.left.toFloat(), rect.bottom.toFloat(), pageSize, element.maxX, element.maxY),
            emrPointToPage(rect.right.toFloat(), rect.bottom.toFloat(), pageSize, element.maxX, element.maxY),
        )
        if (corners.any { it == null } || displaySize.x <= 0 || displaySize.y <= 0) return null
        val points = corners.filterNotNull()
        val left = points.minOf { it.first } * displaySize.x / pageSize.width
        val top = points.minOf { it.second } * displaySize.y / pageSize.height
        val right = points.maxOf { it.first } * displaySize.x / pageSize.width
        val bottom = points.maxOf { it.second } * displaySize.y / pageSize.height
        return RectF(left, top, right, bottom)
    }

    private fun beginQuery(allowShow: Boolean, reason: String): Long? {
        if (allowShow && queryBusy.get()) cancelActiveQuery("tap-supersedes-background")
        if (!queryBusy.compareAndSet(false, true)) {
            Log.i(TAG, "inspect deferred busy token=$activeQueryToken reason=$reason")
            return null
        }
        val token = querySequence.incrementAndGet()
        activeQueryToken = token
        Log.i(TAG, "inspect start token=$token reason=$reason allowShow=$allowShow path=${currentNotePath()}")
        mainHandler.postDelayed({
            if (activeQueryToken == token && queryBusy.compareAndSet(true, false)) {
                activeQueryToken = 0L
                Log.w(TAG, "inspect timeout token=$token reason=$reason")
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

    private fun resolveRegistry(
        app: PluginAppAPI,
        filePath: String,
        page: Int,
        pictures: List<PictureRecord>,
    ): Map<String, ResolvedMeta> {
        val registryFile = stableRegistryFile(app)
        val root = try {
            if (registryFile.exists()) JSONObject(registryFile.readText(Charsets.UTF_8))
            else JSONObject().put("v", 1).put("links", JSONArray())
        } catch (error: Throwable) {
            Log.w(TAG, "registry read failed: $error")
            JSONObject().put("v", 1).put("links", JSONArray())
        }
        val linksArray = root.optJSONArray("links") ?: JSONArray().also { root.put("links", it) }
        val links = (0 until linksArray.length())
            .mapNotNull { linksArray.optJSONObject(it) }
            .filter { it.optString("notePath") == filePath && it.optInt("page", -1) == page }
            .sortedByDescending { it.optString("createdAt") }
        val byKey = pictures.associateBy { it.key }
        val claimed = mutableSetOf<String>()
        val result = mutableMapOf<String, ResolvedMeta>()
        var changed = false

        for (link in links) {
            val key = link.optString("elementKey")
            val picture = byKey[key] ?: continue
            val meta = link.optJSONObject("meta") ?: continue
            result[picture.key] = ResolvedMeta(meta.toString(), false)
            claimed.add(picture.key)
        }

        for (link in links) {
            if (link.optString("elementKey").isNotEmpty()) continue
            val baselineArray = link.optJSONArray("baselinePictureKeys")
            val baseline = mutableSetOf<String>()
            if (baselineArray != null) {
                for (index in 0 until baselineArray.length()) baseline.add(baselineArray.optString(index))
            }
            val available = pictures.filter { it.key !in claimed && it.key !in baseline }
            val basename = link.optString("pngBasename")
            val basenameMatches = available.filter { it.basename == basename }
            val match = when {
                basenameMatches.size == 1 -> basenameMatches.first()
                available.size == 1 -> available.first()
                else -> null
            } ?: continue
            val meta = link.optJSONObject("meta") ?: continue
            link.put("elementKey", match.key)
            result[match.key] = ResolvedMeta(meta.toString(), true)
            claimed.add(match.key)
            changed = true
            Log.i(TAG, "registry bound key=${match.key} page=$page")
        }

        if (changed) {
            try {
                val temp = File(registryFile.parentFile, "${registryFile.name}.native.tmp")
                temp.writeText(root.toString(), Charsets.UTF_8)
                if (registryFile.exists()) registryFile.delete()
                if (!temp.renameTo(registryFile)) {
                    registryFile.writeText(root.toString(), Charsets.UTF_8)
                    temp.delete()
                }
            } catch (error: Throwable) {
                Log.w(TAG, "registry bind write failed: $error")
            }
        }
        return result
    }

    private fun pictureKey(element: Element): String = "n:${element.trailNumInPage}"

    private fun stableRegistryFile(app: PluginAppAPI): File {
        val stable = File(reactApplicationContext.filesDir, REGISTRY_FILENAME)
        if (!stable.exists()) {
            val legacy = File(app.pluginPath, REGISTRY_FILENAME)
            if (legacy.exists()) {
                try {
                    legacy.copyTo(stable, overwrite = false)
                    Log.i(TAG, "registry migrated to stable app storage")
                } catch (error: Throwable) {
                    Log.w(TAG, "registry migration skipped: $error")
                }
            }
        }
        return stable
    }

    private fun decodeLegacyMeta(userData: String?): String? {
        if (userData.isNullOrEmpty() || !userData.startsWith(USERDATA_PREFIX)) return null
        return try {
            JSONObject(userData.removePrefix(USERDATA_PREFIX)).toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun titleHotspotRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        metaJson: String,
    ): android.graphics.RectF {
        val hotspot = try {
            JSONObject(metaJson).optJSONObject("hotspot")
        } catch (_: Throwable) {
            null
        }
        val normalizedX = (hotspot?.optDouble("x", 0.0)?.toFloat() ?: 0f).coerceIn(0f, 1f)
        val normalizedY = (hotspot?.optDouble("y", 0.0)?.toFloat() ?: 0f).coerceIn(0f, 1f)
        val normalizedWidth = (
            hotspot?.optDouble("w", DEFAULT_HOTSPOT_WIDTH.toDouble())?.toFloat()
                ?: DEFAULT_HOTSPOT_WIDTH
            ).coerceIn(0f, 1f - normalizedX)
        val normalizedHeight = (
            hotspot?.optDouble("h", DEFAULT_HOTSPOT_HEIGHT.toDouble())?.toFloat()
                ?: DEFAULT_HOTSPOT_HEIGHT
            ).coerceIn(0f, 1f - normalizedY)
        val width = right - left
        val height = bottom - top
        return android.graphics.RectF(
            left + width * normalizedX,
            top + height * normalizedY,
            left + width * (normalizedX + normalizedWidth),
            top + height * (normalizedY + normalizedHeight),
        )
    }

    private fun contains(rect: android.graphics.RectF, x: Float, y: Float, padding: Float): Boolean =
        x >= rect.left - padding && x <= rect.right + padding &&
            y >= rect.top - padding && y <= rect.bottom + padding

    private fun recycleElements(elements: List<Element>) {
        for (element in elements) {
            try {
                element.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun finishQuery(token: Long, message: String): Boolean {
        if (!queryIsActive(token)) return false
        activeQueryToken = 0L
        queryBusy.set(false)
        Log.i(TAG, "inspect done $message")
        return true
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
