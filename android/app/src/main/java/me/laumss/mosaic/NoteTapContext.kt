package me.laumss.mosaic

/** The complete context must remain a visible, exposed note throughout a tap/query. */
internal data class NoteTapContext(
    val path: String?,
    val noteForeground: Boolean,
    val boardVisible: Boolean,
    val pluginUiShowing: Boolean,
) {
    fun permits(expectedPath: String? = path): Boolean =
        path != null && path == expectedPath && noteForeground && !boardVisible && !pluginUiShowing
}
