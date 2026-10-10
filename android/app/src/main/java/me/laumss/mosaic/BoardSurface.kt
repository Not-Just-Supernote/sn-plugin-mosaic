package me.laumss.mosaic

import android.content.Context
import android.content.Intent
import android.util.Log


interface BoardSurface {
    enum class CloseSource { TOOLBAR, LAUNCHER_PULL }

    val name: String
    
    val emitsCommands: Boolean
    
    val showsBoardChrome: Boolean
    
    val singleFingerScrolls: Boolean
    
    val allowsCardFrame: Boolean

    
    fun constrainScale(next: Float, start: Float): Float

    
    fun constrainPan(panX: Float, panY: Float, scale: Float, out: FloatArray)

    
    fun onSceneMutated()

    
    fun onViewportCommitted(panX: Float, panY: Float, scale: Float)

    
    fun onCloseRequested(source: CloseSource)
}


class WhiteboardSurface(
    private val emitter: BoardCommandEmitter,
    
    private val onCloseEmitted: () -> Unit = {},
) : BoardSurface {
    override val name = "board"
    override val emitsCommands = true
    override val showsBoardChrome = true
    override val singleFingerScrolls = false
    override val allowsCardFrame = true

    
    
    
    override fun constrainScale(next: Float, start: Float): Float =
        if (start < BoardGeometry.GESTURE_MIN_ZOOM - ZOOM_EPS || start > BoardGeometry.GESTURE_MAX_ZOOM + ZOOM_EPS) start
        else next.coerceIn(BoardGeometry.GESTURE_MIN_ZOOM, BoardGeometry.GESTURE_MAX_ZOOM)

    companion object {
        
        private const val ZOOM_EPS = 1e-3f
    }

    override fun constrainPan(panX: Float, panY: Float, scale: Float, out: FloatArray) {
        out[0] = panX
        out[1] = panY
    }

    override fun onSceneMutated() = Unit

    override fun onViewportCommitted(panX: Float, panY: Float, scale: Float) = emitter.viewport(panX, panY, scale)

    override fun onCloseRequested(source: BoardSurface.CloseSource) {
        Log.i("MosaicInteraction", "close requested source=$source surface=$name")
        emitter.begin()
        
        emitter.action("close") { putDouble("emittedAt", System.currentTimeMillis().toDouble()) }
        emitter.commit()
        
        onCloseEmitted()
    }
}


class NoteSurface(
    private val notes: NoteController,
    private val context: Context,
    
    private val viewportHeightDp: () -> Float,
    private val viewportWidthDp: () -> Float,
    // Scale at which the note width fills the view; that is 100%.
    private val fitScale: () -> Float,
    
    private val topInsetDp: () -> Float,
    
    private val close: () -> Unit,
) : BoardSurface {
    override val name = "note"
    override val emitsCommands = false
    override val showsBoardChrome = false
    
    override val singleFingerScrolls = false
    override val allowsCardFrame = false

    companion object {
        
        private const val NOTE_OVERSCROLL_SCREENS = 6f
        // Pinch zoom inside a note runs from fit-width (100%) to 150%.
        const val MAX_ZOOM = 1.5f
    }

    override fun constrainScale(next: Float, start: Float): Float {
        val fit = fitScale()
        return next.coerceIn(fit, fit * MAX_ZOOM)
    }

    // Horizontal pan only exists while zoomed in; the page never leaves the view edges.
    override fun constrainPan(panX: Float, panY: Float, scale: Float, out: FloatArray) {
        val minPanX = minOf(0f, viewportWidthDp() - ScrollingDocument.WIDTH * scale)
        out[0] = panX.coerceIn(minPanX, 0f)
        out[1] = clampPanY(panY, scale)
    }

    private fun clampPanY(value: Float, scale: Float): Float {
        val viewportH = viewportHeightDp()
        val contentH = notes.document?.contentHeight ?: viewportH
        val maxPan = topInsetDp()
        
        val naturalMin = minOf(viewportH - contentH * scale, maxPan)
        
        
        val minPan = naturalMin - viewportH * NOTE_OVERSCROLL_SCREENS
        return value.coerceIn(minPan, maxPan)
    }

    override fun onSceneMutated() {
        notes.changed(synchronized(BoardEngine.lock) { BoardEngine.strokes.values.toList() })
    }

    override fun onViewportCommitted(panX: Float, panY: Float, scale: Float) {
        notes.document?.scrollY = ((topInsetDp() - panY) / scale).coerceAtLeast(0f)
    }

    override fun onCloseRequested(source: BoardSurface.CloseSource) {
        close()
        if (source == BoardSurface.CloseSource.LAUNCHER_PULL) {
            
            context.sendBroadcast(Intent("com.ratta.supernote.launcher.BroadcastReceiver.slidebarstatusbar").putExtra("lockStatusbar", false))
        }
    }
}
