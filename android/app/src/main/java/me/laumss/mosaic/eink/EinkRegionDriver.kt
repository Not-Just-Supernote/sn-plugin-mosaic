package me.laumss.mosaic.eink

/** Read-only panel state for Regal windows and manual host full refreshes. */
internal object EinkRegionDriver {
    private val library = runCatching { System.loadLibrary("mosaic_eink_region") }
    private external fun displayBusyNative(): Int

    /** 0 idle, 1 busy, -1 unsupported/unavailable. */
    fun displayBusy(): Int = try {
        library.getOrThrow()
        displayBusyNative()
    } catch (_: Throwable) { -1 }
}
