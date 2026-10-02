package me.laumss.mosaic

import android.os.Handler
import android.os.Looper


object InputRouter {

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3
    const val ACTION_POINTER_DOWN = 5
    const val ACTION_POINTER_UP = 6

    
    class TouchFrame(
        val action: Int,
        
        val actionIndex: Int,
        val generation: Int,
        val ids: IntArray,
        
        val xs: FloatArray,
        val ys: FloatArray,
        val penPriority: Boolean,
        val uptimeMs: Long,
    ) {
        val count: Int get() = ids.size
    }

    enum class PenState { HOVER, RUBBER, STYLUS }

    interface Sink {
        fun onTouch(frame: TouchFrame)
        fun onPenState(state: PenState, value: Boolean)
        fun onSlider(gesture: String, side: Int)
        
        fun onManualRefresh() {}
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var sink: Sink? = null

    
    private class QueuedTouch(val target: Sink, val frame: TouchFrame)

    private val touchLock = Any()
    private val touchQueue = ArrayList<QueuedTouch>(8)
    private var touchDrainPosted = false
    private val drainTouch = Runnable {
        val batch: List<QueuedTouch>
        synchronized(touchLock) {
            batch = ArrayList(touchQueue)
            touchQueue.clear()
            touchDrainPosted = false
        }
        for (q in batch) if (sink === q.target) q.target.onTouch(q.frame)
    }

    fun postTouch(frame: TouchFrame) {
        val target = sink ?: return
        synchronized(touchLock) {
            val last = touchQueue.lastOrNull()
            if (frame.action == ACTION_MOVE && last != null && last.frame.action == ACTION_MOVE && last.target === target) {
                touchQueue[touchQueue.size - 1] = QueuedTouch(target, frame)
            } else {
                touchQueue.add(QueuedTouch(target, frame))
            }
            if (touchDrainPosted) return
            touchDrainPosted = true
        }
        main.post(drainTouch)
    }

    fun postPenState(state: PenState, value: Boolean) {
        val target = sink ?: return
        main.post { if (sink === target) target.onPenState(state, value) }
    }

    fun postSlider(gesture: String, side: Int) {
        val target = sink ?: return
        main.post { if (sink === target) target.onSlider(gesture, side) }
    }

    fun postManualRefresh() {
        val target = sink ?: return
        main.post { if (sink === target) target.onManualRefresh() }
    }
}
