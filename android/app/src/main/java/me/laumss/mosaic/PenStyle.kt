
package me.laumss.mosaic


enum class PenStyle(
    
    val objType: Int,
    
    val ppMap: IntArray?,
    
    val speedMap: IntArray?,
    
    val constantScale: Float,
) {
    
    PEN(0, PenTables.PEN_PP, PenTables.PEN_SPEED, 0f),

    
    PENCIL(14, PenTables.PENCIL_PP, null, 0f),

    
    BRUSH(15, PenTables.BRUSH_PP, PenTables.BRUSH_SPEED, 0f),

    
    MARKER(17, null, null, 1.2f),

    
    FIXED(18, null, null, 1f),
    ;

    
    val isConstantWidth: Boolean get() = ppMap == null

    companion object {
        
        fun fromObjType(objType: Int): PenStyle =
            entries.first { it.objType == objType }
    }
}


internal object PenTables {

    
    val PEN_PP = intArrayOf(10, 58, 120, 68, 270, 138, 320, 170, 350, 235, 420, 250)

    
    val PEN_SPEED = intArrayOf(20, 130, 80, 128, 120, 110, 500, 98, 1200, 90, 4000, 86)

    
    val PENCIL_PP = intArrayOf(10, 80, 120, 80, 270, 160, 320, 240, 350, 288, 420, 300)

    
    val BRUSH_PP = intArrayOf(10, 68, 120, 80, 270, 160, 320, 226, 350, 272, 420, 280)

    
    val BRUSH_SPEED = intArrayOf(20, 132, 50, 130, 100, 108, 400, 90, 120, 84, 4000, 70)
}
