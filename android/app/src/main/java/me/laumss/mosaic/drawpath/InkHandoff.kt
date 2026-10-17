package me.laumss.mosaic.drawpath

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent

/** Handing a finished stroke from the system ink service to Mosaic's own rendering, syncing the service's background, the full refresh that clears gesture trails, and the refresh mode while the pen writes. */
internal class InkHandoff(private val view: MosaicBoardView) {
    companion object {
        private const val BACKGROUND_SYNC_PROMPT_DELAY_MS = 120L
        private const val PEN_MODE_RESET_MAX_MS = 1500L
        private const val INK_HANDOFF_IDLE_MS = 3000L
        private const val INK_HANDOFF_RETRY_MS = 500L
        private const val INK_CONTACT_STALE_MS = 1500L
        private const val BACKGROUND_SYNC_IDLE_DELAY_MS = 2000L
        // 卡片手势后，背景同步等整屏刷新做完再过这么久；刷新迟迟不来时最多等到 MAX。
        private const val SYNC_AFTER_FULL_REFRESH_MS = 1200L
        private const val SYNC_HOLD_FOR_REFRESH_MAX_MS = 10_000L
        // 卡片手势抬笔后多久整屏刷新，清掉系统笔迹。
        private const val TRAIL_CLEAR_REFRESH_DELAY_MS = 600L
        private const val DEVICE_TYPE_A6X2 = 4
    }

    var stylusContact = false

    var penModeApplied = false

    var lastStylusX = 0f

    var lastStylusY = 0f

    var transientStrokeActive = false

    var awaitingStrokeCommit = false

    var fullRefreshPending = false

    private var inkHandoffPending = false

    var plainStrokeCommit = false

    private var plainStrokeCommitted = false

    var lastPenEventAt = 0L

    private val inkHandoffTask: Runnable = Runnable {
        if (!inkHandoffPending) return@Runnable
        val sincePen = SystemClock.uptimeMillis() - lastPenEventAt
        if (stylusContact && sincePen < INK_CONTACT_STALE_MS) {
            Log.i(LogTags.BOARD_VIEW, "ink handoff idle: pen in contact, retry sincePen=$sincePen")
            view.mainHandler.postDelayed(inkHandoffTask, INK_HANDOFF_RETRY_MS)
            return@Runnable
        }
        if (stylusContact) {
            Log.w(LogTags.BOARD_VIEW, "ink handoff idle: stale pen contact cleared sincePen=$sincePen")
            stylusContact = false
            InputArbiter.onPenContact(false)
        }
        Log.i(LogTags.BOARD_VIEW, "ink handoff idle: finalize")
        finalizeInkSession("idle")
    }

    private var trailDiscardedDuringContact = false

    var strokeSceneChangedDuringContact = false

    var hoverRearmDeferred = false

    private var holdPenRefreshUntilSettle = false

    private val penModeResetFallback = Runnable {
        if (!stylusContact && penModeApplied) {
            holdPenRefreshUntilSettle = false
            penModeApplied = false
            MosaicEinkRefreshModule.resetNative("pen")
            Log.i(LogTags.BOARD_VIEW, "pen refresh mode reset by fallback")
        }
    }

    private var backgroundSyncUrgency = SyncUrgency.NONE

    private var backgroundSyncTask: Runnable? = null

    private var discardSyncPending = false

    // 在这之前不同步背景，见 onPenTrailDiscarded。
    var syncHoldUntil = 0L

    private enum class SyncUrgency { NONE, IDLE, PROMPT }

    fun onStylusDown(event: MotionEvent) {
        stylusContact = true
        InputArbiter.onPenContact(true)
        cancelBackgroundSync()
        lastStylusX = event.x
        lastStylusY = event.y
    }

    /** The pen lifted and the scene did not change: no Mosaic stroke will replace the system trail. */
    fun onStrokeEndedWithoutChange() {
        if (awaitingStrokeCommit && !plainStrokeCommitted) finalizeInkSession("no-commit")
    }

    fun noteContentChanged(reason: String, moved: Boolean) {
        val next = if (moved) SyncUrgency.PROMPT else SyncUrgency.IDLE
        if (next.ordinal > backgroundSyncUrgency.ordinal) backgroundSyncUrgency = next

        cancelBackgroundSync()

        if (stylusContact) strokeSceneChangedDuringContact = true
        when {
            plainStrokeCommit -> if (awaitingStrokeCommit || inkHandoffPending) holdInkHandoff()
            awaitingStrokeCommit -> finalizeInkSession("commit")
            inkHandoffPending && !stylusContact -> {
                Log.i(LogTags.BOARD_VIEW, "ink handoff flushed by content change reason=$reason moved=$moved")
                finalizeInkSession("flush")
            }
        }
    }

    fun onContentSettled() {
        view.controller.trailCleanup.resume("content-settled")
        if (holdPenRefreshUntilSettle && !stylusContact) {
            holdPenRefreshUntilSettle = false
            if (penModeApplied) {
                penModeApplied = false
                MosaicEinkRefreshModule.resetNative("pen")
            }
        }
        if (hoverRearmDeferred && !stylusContact && !awaitingStrokeCommit) {
            hoverRearmDeferred = false
            view.drawPath.lastHoverRearmAt = System.currentTimeMillis()
            view.drawPath.rearmDrawPath("hoverEnter:settled")
        }

        if (backgroundSyncUrgency == SyncUrgency.NONE || (stylusContact && view.drawPath.drawPathActive)) return
        if (discardSyncPending) {
            cancelBackgroundSync()
            sendBackgroundSync()
            return
        }
        val delay = if (backgroundSyncUrgency == SyncUrgency.PROMPT) {
            BACKGROUND_SYNC_PROMPT_DELAY_MS
        } else {
            BACKGROUND_SYNC_IDLE_DELAY_MS
        }
        cancelBackgroundSync()
        val task = Runnable {
            backgroundSyncTask = null
            sendBackgroundSync()
        }
        backgroundSyncTask = task
        view.mainHandler.postDelayed(task, delay)
    }

    private fun cancelBackgroundSync() {
        backgroundSyncTask?.let { view.mainHandler.removeCallbacks(it) }
        backgroundSyncTask = null
    }

    private fun scheduleBackgroundSync(delay: Long) {
        cancelBackgroundSync()
        val task = Runnable {
            backgroundSyncTask = null
            sendBackgroundSync()
        }
        backgroundSyncTask = task
        view.mainHandler.postDelayed(task, delay)
    }

    private fun sendBackgroundSync(force: Boolean = false) {
        if (!view.attached || !view.drawPath.configured || (!force && stylusContact && view.drawPath.drawPathActive)) return
        val held = syncHoldUntil - SystemClock.uptimeMillis()
        if (held > 0) {
            if (backgroundSyncUrgency == SyncUrgency.NONE) backgroundSyncUrgency = SyncUrgency.PROMPT
            scheduleBackgroundSync(held)
            return
        }
        val binder = view.drawPath.drawPathBinder ?: return
        val urgency = backgroundSyncUrgency
        val discard = discardSyncPending
        try {
            DrawPathClient.syncBackground(
                binder = binder,
                appName = view.drawPath.drawPathAppName,
                isA6X2 = view.deviceType == DEVICE_TYPE_A6X2,
            )
            backgroundSyncUrgency = SyncUrgency.NONE
            discardSyncPending = false
            Log.i(LogTags.BOARD_VIEW, "drawPath background sync: urgency=$urgency discard=$discard")
        } catch (error: Throwable) {
            Log.w(LogTags.BOARD_VIEW, "drawPath background sync failed: urgency=$urgency", error)
            view.drawPath.drawPathBinder = null
        }
    }

    fun onPenTrailDiscarded(reason: String) {
        if (stylusContact) trailDiscardedDuringContact = true
        if (backgroundSyncUrgency.ordinal < SyncUrgency.PROMPT.ordinal) backgroundSyncUrgency = SyncUrgency.PROMPT
        discardSyncPending = true

        if (stylusContact) {
            cancelBackgroundSync()
            sendBackgroundSync(force = true)
        }

        view.contentView.markSettleRequested()
        if (!view.contentView.isDeferringRefresh) view.contentView.postInvalidateOnAnimation()
        val fullRefresh = reason.startsWith("card-")
        if (fullRefresh) {
            // 书法笔的轨迹抬笔后系统服务还会再画，整屏刷新太早（150ms 内）会清不掉；设备实测 0.6 秒后刷新每次都清掉。
            // 背景同步等刷新做完再发，由 runPendingFullRefresh 安排。
            syncHoldUntil = SystemClock.uptimeMillis() + SYNC_HOLD_FOR_REFRESH_MAX_MS
            view.mainHandler.postDelayed({ if (view.attached) requestFullRefreshWhenSettled(reason) }, TRAIL_CLEAR_REFRESH_DELAY_MS)
        }
        Log.i(LogTags.BOARD_VIEW, "pen trail discarded: reason=$reason sync=${if (stylusContact) "now" else "deferred"} fullRefresh=$fullRefresh stylus=$stylusContact active=${view.drawPath.drawPathActive}")
    }

    private fun requestFullRefreshWhenSettled(reason: String) {
        fullRefreshPending = true
        view.contentView.runWhenSettled(Runnable { runPendingFullRefresh(reason) })
    }

    private fun runPendingFullRefresh(reason: String) {
        if (!fullRefreshPending || !view.attached) return
        if (stylusContact || awaitingStrokeCommit || inkHandoffPending) {
            Log.i(LogTags.BOARD_VIEW, "full refresh waits for ink reason=$reason contact=$stylusContact awaiting=$awaitingStrokeCommit handoff=$inkHandoffPending")
            return
        }
        fullRefreshPending = false
        MosaicEinkRefreshModule.requestFullRefresh(view.contentView, reason)
        syncHoldUntil = SystemClock.uptimeMillis() + SYNC_AFTER_FULL_REFRESH_MS
        if (backgroundSyncUrgency != SyncUrgency.NONE) scheduleBackgroundSync(SYNC_AFTER_FULL_REFRESH_MS)
    }

    fun inkBegin(x: Float, y: Float) = view.inkView.beginStroke(x, y)

    private fun inkAppend(x: Float, y: Float) =
        view.inkView.appendStroke(x, y)

    private fun inkEnd() = view.inkView.endStroke()

    fun inkAbort() {
        view.inkView.abortStroke()
    }

    private fun beginStrokeCommitWait() {
        awaitingStrokeCommit = true
    }

    private fun cancelStrokeCommitWait(): Boolean {
        val was = awaitingStrokeCommit
        awaitingStrokeCommit = false
        return was
    }

    private fun holdInkHandoff() {
        if (fullRefreshPending) {
            finalizeInkSession("refresh-pending")
            return
        }
        awaitingStrokeCommit = false
        inkHandoffPending = true
        view.mainHandler.removeCallbacks(inkHandoffTask)
        view.mainHandler.postDelayed(inkHandoffTask, INK_HANDOFF_IDLE_MS)
        Log.i(LogTags.BOARD_VIEW, "ink handoff hold: ${INK_HANDOFF_IDLE_MS}ms")
    }

    fun clearInkHandoff(): Boolean {
        view.mainHandler.removeCallbacks(inkHandoffTask)
        val was = inkHandoffPending
        inkHandoffPending = false
        return was
    }

    fun <T> commitPlainStroke(block: () -> T): T {
        plainStrokeCommit = true
        try {
            return block()
        } finally {
            plainStrokeCommit = false
            plainStrokeCommitted = true
            if (awaitingStrokeCommit || inkHandoffPending) holdInkHandoff()
        }
    }

    fun flushInkHandoff(reason: String) {
        if (inkHandoffPending && !stylusContact) finalizeInkSession(reason)
    }

    private fun finalizeInkSession(reason: String) {
        cancelStrokeCommitWait()
        clearInkHandoff()
        view.contentView.markSettleRequested()
        view.contentView.refreshAfterRaster()
        view.inkView.clearImmediately()
        if (fullRefreshPending) view.contentView.runWhenSettled(Runnable { runPendingFullRefresh("after-ink:$reason") })
        if (reason == "no-commit") {
            view.contentView.runWhenSettled {
                if (view.attached && !stylusContact) sendBackgroundSync(force = true)
            }
        }
        Log.i(LogTags.BOARD_VIEW, "ink session finalize: reason=$reason")
    }

    fun releaseInkDefer() {
        val waiting = cancelStrokeCommitWait() or clearInkHandoff()
        if (waiting || view.contentView.isDeferringRefresh) {
            view.contentView.markSettleRequested()
            view.contentView.refreshAfterRaster()
        }
    }

    fun applyPenRefreshMode() {
        val mode = when {
            !view.inkEnabled && !view.lassoEnabled -> MosaicEinkRefreshModule.MODE_DEFAULT
            view.lassoEnabled -> MosaicEinkRefreshModule.MODE_DEFAULT
            else -> null
        }
        if (mode == null) {
            if (penModeApplied) {
                penModeApplied = false
                MosaicEinkRefreshModule.resetNative("pen")
            }
            return
        }
        penModeApplied = true
        MosaicEinkRefreshModule.applyNative(mode, "pen")
    }

    fun feedInkView(event: MotionEvent) {
        val writing = view.inkEnabled && !view.lassoEnabled
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                applyPenRefreshMode()
                if (writing) {
                    cancelStrokeCommitWait()
                    view.mainHandler.removeCallbacks(inkHandoffTask)
                    plainStrokeCommitted = false
                    strokeSceneChangedDuringContact = false
                    view.contentView.setDeferRefresh(true)
                } else {
                    releaseInkDefer()
                }

                transientStrokeActive = view.lassoEnabled && !view.shapeDrag
                if (transientStrokeActive) {
                    inkBegin(event.x, event.y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (transientStrokeActive) {
                    for (i in 0 until event.historySize) {
                        inkAppend(event.getHistoricalX(i), event.getHistoricalY(i))
                    }
                    inkAppend(event.x, event.y)
                }
                lastStylusX = event.x
                lastStylusY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastStylusX = event.x
                lastStylusY = event.y
                stylusContact = false
                InputArbiter.onPenContact(false)
                if (penModeApplied) {
                    holdPenRefreshUntilSettle = true
                    if (!writing) {
                        view.contentView.markSettleRequested()
                        view.contentView.postInvalidateOnAnimation()
                    }
                    view.mainHandler.removeCallbacks(penModeResetFallback)
                    view.mainHandler.postDelayed(penModeResetFallback, PEN_MODE_RESET_MAX_MS)
                }
                if (transientStrokeActive) inkEnd()
                transientStrokeActive = false
                if (writing) {
                    if (trailDiscardedDuringContact) {
                        trailDiscardedDuringContact = false
                        finalizeInkSession("discard")
                    } else if (strokeSceneChangedDuringContact) finalizeInkSession("commit-before-up")
                    else beginStrokeCommitWait()
                } else if (backgroundSyncUrgency != SyncUrgency.NONE) {
                    view.contentView.markSettleRequested()
                    view.contentView.postInvalidateOnAnimation()
                }
            }
        }
    }
}
