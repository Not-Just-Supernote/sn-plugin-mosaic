package me.laumss.mosaic

import android.graphics.RectF

/** Selected cards and strokes; [rect] is the frame shown around them in world units. */
class LassoSelection(val rect: RectF, val cardIds: List<String>, val strokeIds: List<String>, val fromLasso: Boolean = false)
