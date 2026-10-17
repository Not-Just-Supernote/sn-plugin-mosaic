package me.laumss.mosaic

/** Gray fill for light cards: a dither of [low] and [high] with [brightPixels] high samples per tile. */
data class CardGrayRecipe(val low: Int, val high: Int, val brightPixels: Int, val tileSize: Int = 8) {
    init { require(tileSize in listOf(8, 10)); require(brightPixels in 0..tileSize * tileSize) }
    companion object {
        const val IDLE_LOW = 0xFFDDDDDD.toInt()
        const val IDLE_BRIGHT_PIXELS = 76
        val DEFAULT = CardGrayRecipe(IDLE_LOW, 0xFFE0E0E0.toInt(), IDLE_BRIGHT_PIXELS, 10)
        val MOVING = CardGrayRecipe(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 56)

        fun forMotion(moving: Boolean) = if (moving) MOVING else DEFAULT
    }
}
