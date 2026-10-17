package me.laumss.mosaic.engine

import me.laumss.mosaic.*
import me.laumss.mosaic.board.*
import me.laumss.mosaic.drawpath.*
import me.laumss.mosaic.eink.*
import me.laumss.mosaic.inkling.*
import me.laumss.mosaic.input.*
import me.laumss.mosaic.note.*
import me.laumss.mosaic.render.*
import android.graphics.RectF

/** Selected cards and strokes; [rect] is the frame shown around them in world units. */
class LassoSelection(val rect: RectF, val cardIds: List<String>, val strokeIds: List<String>, val fromLasso: Boolean = false)
