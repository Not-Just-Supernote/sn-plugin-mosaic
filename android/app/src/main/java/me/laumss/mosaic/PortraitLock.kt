package me.laumss.mosaic

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager


object PortraitLock {
    private const val TAG = "MosaicPortraitLock"

    private var lockedView: View? = null
    private var lockedParams: WindowManager.LayoutParams? = null
    private var previousOrientation: Int? = null
    private var overlayView: View? = null
    private var overlayManager: WindowManager? = null

    fun activate(anchor: View) {
        if (lockedParams != null || overlayView != null) return

        val found = findWindow(anchor)
        if (found != null) {
            val (view, lp) = found
            if (lp.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                Log.i(TAG, "host window already portrait")
                return
            }
            previousOrientation = lp.screenOrientation
            lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            try {
                windowManager(anchor.context).updateViewLayout(view, lp)
                lockedView = view
                lockedParams = lp
                Log.i(TAG, "locked host window to portrait (was $previousOrientation)")
                return
            } catch (error: Throwable) {
                Log.w(TAG, "host window update failed: ${error.message}")
                lp.screenOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                previousOrientation = null
            }
        }

        addOverlay(anchor.context)
    }

    fun release() {
        val lp = lockedParams
        val previous = previousOrientation
        val view = lockedView
        lockedParams = null
        previousOrientation = null
        lockedView = null
        if (lp != null) {
            lp.screenOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (view != null) {
                try {
                    windowManager(view.context).updateViewLayout(view, lp)
                    Log.i(TAG, "restored host window orientation=$previous")
                } catch (error: Throwable) {
                    Log.w(TAG, "restore host window failed: ${error.message}")
                }
            }
        }
        removeOverlay()
    }

    private fun findWindow(start: View): Pair<View, WindowManager.LayoutParams>? {
        var current: View? = start
        while (current != null) {
            val lp = current.layoutParams
            if (lp is WindowManager.LayoutParams) return current to lp
            current = current.parent as? View
        }
        val root = start.rootView
        val lp = root.layoutParams
        return if (lp is WindowManager.LayoutParams) root to lp else null
    }

    private fun addOverlay(context: Context) {
        val manager = windowManager(context)
        val view = View(context)
        val lp = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            title = "MosaicPortraitLock"
        }
        try {
            @Suppress("DEPRECATION")
            manager.addView(view, lp)
            overlayView = view
            overlayManager = manager
            Log.i(TAG, "locked overlay window to portrait")
        } catch (error: Throwable) {
            Log.w(TAG, "overlay lock failed: ${error.message}")
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        val manager = overlayManager
        overlayView = null
        overlayManager = null
        try {
            manager?.removeViewImmediate(view)
            Log.i(TAG, "removed portrait overlay")
        } catch (error: Throwable) {
            Log.w(TAG, "remove overlay failed: ${error.message}")
        }
    }

    private fun windowManager(context: Context): WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
}
