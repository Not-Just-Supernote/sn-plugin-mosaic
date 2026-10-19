package me.laumss.mosaic.eink

/** Read-only panel completion state, shared by Regal windows and manual gray restoration. */
internal object EinkRegionDriver {
    private val library = runCatching { System.loadLibrary("mosaic_eink_region") }
    private external fun displayBusyNative(): Int

    /** 0 idle, 1 busy, -1 unsupported/unavailable. */
    fun displayBusy(): Int = try {
        library.getOrThrow()
        displayBusyNative()
    } catch (_: Throwable) { -1 }
}
