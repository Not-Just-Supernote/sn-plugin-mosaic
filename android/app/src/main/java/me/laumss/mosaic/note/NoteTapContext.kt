package me.laumss.mosaic.note

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.engine.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.render.*
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
