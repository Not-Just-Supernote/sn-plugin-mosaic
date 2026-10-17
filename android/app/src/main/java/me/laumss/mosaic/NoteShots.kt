package me.laumss.mosaic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Selections inserted into the host Note as images, and the links they leave on the board. */
internal class NoteShots(private val board: BoardInteractionController) {
    companion object {
        private const val NOTE_LINK_TAP_SLOP_DP = 8f
        private const val NOTE_LINK_MIN_HIT_DP = 40f
        const val NOTE_SHOT_DIR = "/sdcard/EXPORT/mosaic/shots"
        private const val TAG_SHOT = "MosaicNoteShot"
        private const val NOTE_SHOT_FOCUS_RETRY_MS = 120L
        private const val NOTE_SHOT_FOCUS_RETRIES = 20
        private const val NOTE_SHOT_MAX_SCREEN_FRACTION = 0.9f
    }

    fun linkAt(wx: Float, wy: Float): String? {
        if (!board.surface.showsBoardChrome) return null
        val scale = BoardEngine.scale
        return NoteLinks.badgeAt(wx, wy, NOTE_LINK_TAP_SLOP_DP / scale, NOTE_LINK_MIN_HIT_DP / scale)
    }

    fun openLink(shotId: String) {
        val link = NoteLinks.list().firstOrNull { it.id == shotId } ?: return
        if (board.surfaceSwitching || board.suspendedBoard != null) return
        val current = MosaicNoteShotModule.currentHostNotePath()
        val sameNote = current != null && current == link.notePath
        val name = link.noteName.ifBlank { MosaicStrings.t(MosaicStrings.Key.noteLinkFallback) }
        Log.i(TAG_SHOT, "open note link id=$shotId note=${link.notePath} page=${link.page} current=$current same=$sameNote")
        MosaicNoteShotModule.logHostApisOnce()
        board.cancelFingerGestures("note-link")
        if (!sameNote) {
            Toast.makeText(board.host.context, MosaicStrings.noteLinkElsewhere(name, link.page), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(board.host.context, MosaicStrings.noteLinkArrived(name, link.page), Toast.LENGTH_LONG).show()
        board.surface.onCloseRequested(BoardSurface.CloseSource.TOOLBAR)
    }

    fun locateLink(shotId: String) {
        val region = NoteLinks.regionOf(shotId) ?: return
        board.cancelFingerGestures("locate-note-link")
        val top = board.chrome.toolbarHeightPx() / board.density
        val w = board.host.width / board.density
        val h = max(1f, board.host.height / board.density - top)
        val fit = min(w / max(region.width(), 1f), h / max(region.height(), 1f))
        val scale = BoardGeometry.clampZoom(min(BoardEngine.scale, fit))
        val centered = SparseNavigation.panToCenterRect(region, w, h, scale)
        board.chrome.hideSwitcher()
        board.refreshHostToolFlags()
        board.commitViewport(centered[0], centered[1] + top, scale)
        Log.i(TAG_SHOT, "locate link id=$shotId region=$region scale=$scale")
    }

    fun deleteLink(shotId: String) {
        board.emitter.begin()
        board.emitter.action("deleteNoteShot") { putString("shotId", shotId) }
        board.emitter.commit()
    }

    private var busy = false
    private var focusToken = 0

    var sessionActive = false
        private set

    private class NoteShotPng(val path: String, val width: Int, val height: Int, val hotspot: NoteShotExport.Hotspot)

    fun canInsert(sel: LassoSelection): Boolean =
        board.surface.showsBoardChrome && MosaicNoteShotModule.currentHostNotePath() != null &&
            synchronized(BoardEngine.lock) {
                SelectionActions.canInsertNoteImage(sel.fromLasso, sel.cardIds, sel.strokeIds, BoardEngine.strokes.values)
            }

    fun insertSelection() {
        val sel = board.lasso ?: return
        if (!canInsert(sel)) {
            Log.i(TAG_SHOT, "insert requires a lasso region containing handwriting and a host note; surface=${board.surface.name}")
            return
        }
        if (busy) {
            Log.i(TAG_SHOT, "insert ignored: previous snapshot still rendering")
            return
        }
        val anchored = synchronized(BoardEngine.lock) { NoteShotExport.capture(sel.cardIds, sel.strokeIds) } ?: return
        val layout = NoteShotExport.layout(
            anchored.region, BoardEngine.scale * board.density, board.density,
            board.host.width * NOTE_SHOT_MAX_SCREEN_FRACTION, board.host.height * NOTE_SHOT_MAX_SCREEN_FRACTION,
        )
        val fingerprint = NoteShotExport.fingerprint(anchored.region)
        val shotId = board.newId("shot-")
        val screen = realDisplaySize()
        Log.i(
            TAG_SHOT,
            "insert render id=$shotId region=${anchored.region} cards=${anchored.cards.size} " +
                "strokes=${anchored.strokes.size} pxPerWorld=${layout.pxPerWorld} out=${layout.widthPx}x${layout.heightPx}",
        )
        renderPng(shotId, layout) { png ->
            if (png == null) return@renderPng

            board.setLasso(null)
            board.emitter.begin()
            board.emitter.action("noteShotReady") {
                putString("shotId", shotId)
                putPng(this, png, anchored, fingerprint, layout.pxPerWorld)
                putInt("screenW", screen.x)
                putInt("screenH", screen.y)
            }
            board.emitter.commit()
        }
    }

    /** Forgets a pending focus request; the plugin is closing. */
    fun reset() {
        sessionActive = false
        focusToken++
    }

    fun focus(json: String) {
        val shot = NoteShotExport.Shot.parse(json)
        if (shot == null) {
            Log.w(TAG_SHOT, "focus skipped: unreadable shot")
            return
        }
        sessionActive = true
        val token = ++focusToken
        focusWhenReady(shot, token, NOTE_SHOT_FOCUS_RETRIES)
    }

    private fun focusWhenReady(shot: NoteShotExport.Shot, token: Int, retries: Int) {
        if (token != focusToken) return
        if (board.host.width == 0 || board.host.height == 0 || board.surfaceSwitching || !board.surface.showsBoardChrome) {
            if (retries > 0) {
                board.handler.postDelayed({ focusWhenReady(shot, token, retries - 1) }, NOTE_SHOT_FOCUS_RETRY_MS)
            } else {
                Log.w(TAG_SHOT, "focus gave up id=${shot.id} width=${board.host.width} surface=${board.surface.name}")
            }
            return
        }
        val region = synchronized(BoardEngine.lock) { NoteShotExport.resolve(shot) }?.region ?: return
        board.cancelFingerGestures("note-shot")
        board.setLasso(null)
        board.chrome.hideSwitcher()
        board.refreshHostToolFlags()

        val top = board.chrome.toolbarHeightPx() / board.density
        val w = board.host.width / board.density
        val h = max(1f, board.host.height / board.density - top)
        val fit = min(w / max(region.width(), 1f), h / max(region.height(), 1f))
        val captured = if (shot.pxPerWorld > 0f) shot.pxPerWorld / board.density else BoardEngine.scale
        val scale = BoardGeometry.clampZoom(min(captured, fit))
        val centered = SparseNavigation.panToCenterRect(region, w, h, scale)
        board.commitViewport(centered[0], centered[1] + top, scale)
        Log.i(TAG_SHOT, "focus id=${shot.id} region=$region scale=$scale")
    }

    fun render(json: String, done: (WritableMap?) -> Unit) {
        val shot = NoteShotExport.Shot.parse(json)
        if (shot == null || !board.surface.showsBoardChrome || busy) {
            Log.i(TAG_SHOT, "render skipped id=${shot?.id} surface=${board.surface.name} busy=$busy")
            done(null)
            return
        }
        val anchored = synchronized(BoardEngine.lock) { NoteShotExport.resolve(shot) }
        if (anchored == null) {
            done(null)
            return
        }
        val fingerprint = NoteShotExport.fingerprint(anchored.region)
        if (fingerprint == shot.fingerprint) {
            Log.i(TAG_SHOT, "render id=${shot.id} unchanged")
            done(Arguments.createMap().apply { putBoolean("unchanged", true) })
            return
        }
        val pxPerWorld = if (shot.pxPerWorld > 0f) shot.pxPerWorld else BoardEngine.scale * board.density
        val layout = NoteShotExport.layout(
            anchored.region, pxPerWorld, board.density,
            board.host.width * NOTE_SHOT_MAX_SCREEN_FRACTION, board.host.height * NOTE_SHOT_MAX_SCREEN_FRACTION,
        )
        Log.i(
            TAG_SHOT,
            "render id=${shot.id} region=${anchored.region} cards=${anchored.cards.size} " +
                "strokes=${anchored.strokes.size} out=${layout.widthPx}x${layout.heightPx}",
        )
        renderPng(shot.id, layout) { png ->
            if (png == null) {
                done(null)
                return@renderPng
            }
            done(Arguments.createMap().apply {
                putBoolean("unchanged", false)
                putPng(this, png, anchored, fingerprint, layout.pxPerWorld)
            })
        }
    }

    private fun putPng(
        map: WritableMap,
        png: NoteShotPng,
        anchored: NoteShotExport.Anchored,
        fingerprint: String,
        pxPerWorld: Float,
    ) {
        val region = anchored.region
        map.putString("path", png.path)
        map.putInt("width", png.width)
        map.putInt("height", png.height)
        map.putMap("rect", Arguments.createMap().apply {
            putDouble("x", region.left.toDouble())
            putDouble("y", region.top.toDouble())
            putDouble("w", region.width().toDouble())
            putDouble("h", region.height().toDouble())
        })
        map.putMap("hotspot", Arguments.createMap().apply {
            putDouble("x", png.hotspot.x.toDouble())
            putDouble("y", png.hotspot.y.toDouble())
            putDouble("w", png.hotspot.w.toDouble())
            putDouble("h", png.hotspot.h.toDouble())
        })
        map.putString("anchors", anchored.anchorsJson())
        map.putString("fingerprint", fingerprint)
        map.putDouble("pxPerWorld", pxPerWorld.toDouble())
    }

    private fun renderPng(shotId: String, layout: NoteShotExport.Layout, done: (NoteShotPng?) -> Unit) {
        busy = true
        val densityValue = board.density
        val outPath = "$NOTE_SHOT_DIR/$shotId-${System.currentTimeMillis()}.png"
        val startedAt = SystemClock.uptimeMillis()
        board.content.renderExport(layout.region, layout.pxPerWorld, layout.stripPx) { bitmap ->
            if (bitmap == null) {
                Log.w(TAG_SHOT, "raster failed id=$shotId")
                board.handler.post {
                    busy = false
                    done(null)
                }
                return@renderExport
            }
            val hotspot = NoteShotExport.drawTag(Canvas(bitmap), densityValue, bitmap.width, bitmap.height)
            Thread({
                var png: NoteShotPng? = null
                try {
                    val file = File(outPath)
                    file.parentFile?.mkdirs()
                    val tmp = File("$outPath.tmp")
                    NoteShotExport.writeGrayPng(bitmap, tmp)
                    if (!tmp.renameTo(file)) throw IllegalStateException("rename failed $tmp -> $file")
                    png = NoteShotPng(outPath, bitmap.width, bitmap.height, hotspot)
                    Log.i(
                        TAG_SHOT,
                        "png ok ${bitmap.width}x${bitmap.height}px ${file.length() / 1024}KB " +
                            "in ${SystemClock.uptimeMillis() - startedAt}ms -> $outPath",
                    )
                } catch (error: Throwable) {
                    Log.e(TAG_SHOT, "png write failed id=$shotId", error)
                } finally {
                    bitmap.recycle()
                }
                board.handler.post {
                    busy = false
                    done(png)
                }
            }, "MosaicNoteShotPng").start()
        }
    }

    @Suppress("DEPRECATION")
    private fun realDisplaySize(): Point {
        val point = Point()
        try {
            (board.host.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealSize(point)
        } catch (error: Throwable) {
            Log.w(TAG_SHOT, "display size read failed: $error")
        }
        if (point.x <= 0 || point.y <= 0) point.set(board.host.width, board.host.height)
        return point
    }
}
