package me.laumss.mosaic

/** One recipe policy for the board, previews and the lab. */
data class CardGrayRecipe(val low: Int, val high: Int, val brightPixels: Int, val tileSize: Int = 8) {
    init { require(tileSize in listOf(8, 10)); require(brightPixels in 0..tileSize * tileSize) }
    companion object {
        const val IDLE_LOW = 0xFFDDDDDD.toInt()
        const val IDLE_BRIGHT_PIXELS = 76
        val DEFAULT = CardGrayRecipe(IDLE_LOW, 0xFFE0E0E0.toInt(), IDLE_BRIGHT_PIXELS, 10)
        val MOVING = CardGrayRecipe(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 56)

        fun forMotion(moving: Boolean, idle: CardGrayRecipe = DEFAULT) = if (moving) MOVING else idle

        fun trial(color: Int, brightPixels: Int): CardGrayRecipe =
            if (color == IDLE_LOW && brightPixels == IDLE_BRIGHT_PIXELS) DEFAULT
            else CardGrayRecipe(color, 0xFFE0E0E0.toInt(), brightPixels)
    }
}
