package me.laumss.mosaic

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Shader

/** Shared by the board and the drag preview; the pattern stays one device pixel per sample. */
object CardGrayPattern {
    private val ranks10: IntArray by lazy {
        val ordered = (0 until 100).sortedBy { index ->
            var value = 0
            for (bit in 0..3) {
                val x = ((index % 10) shr bit) and 1
                val y = ((index / 10) shr bit) and 1
                value = value or (((x xor y) * 2 + y) shl (2 * (3 - bit)))
            }
            value
        }
        IntArray(100).also { ranks -> ordered.forEachIndexed { rank, index -> ranks[index] = rank } }
    }
    private class Scratch {
        val shaders = LinkedHashMap<CardGrayRecipe, BitmapShader>()
        val transform = Matrix()
        val inverse = Matrix()
    }
    private val scratch = ThreadLocal.withInitial { Scratch() }

    fun rank(x: Int, y: Int): Int {
        var rank = 0
        for (bit in 0..2) {
            val xb = (x shr bit) and 1
            val yb = (y shr bit) and 1
            rank = rank or (((xb xor yb) * 2 + yb) shl (2 * (2 - bit)))
        }
        return rank
    }

    @Suppress("DEPRECATION")
    fun shader(canvas: Canvas, recipe: CardGrayRecipe): Shader? {
        if (recipe.brightPixels == 0 || recipe.low == recipe.high) return null
        val state = checkNotNull(scratch.get())
        val shader = state.shaders.getOrPut(recipe) {
            if (state.shaders.size >= 32) state.shaders.remove(state.shaders.keys.first())
            val size = recipe.tileSize
            val pixels = IntArray(size * size) { i ->
                val rank = if (size == 10) ranks10[i] else rank(i % 8, i / 8)
                if (rank < recipe.brightPixels) recipe.high else recipe.low
            }
            BitmapShader(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.getMatrix(state.transform)
        if (!state.transform.invert(state.inverse)) state.inverse.reset()
        shader.setLocalMatrix(state.inverse)
        return shader
    }
}
