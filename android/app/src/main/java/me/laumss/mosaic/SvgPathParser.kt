package me.laumss.mosaic

import android.graphics.Path


object SvgPathParser {

    fun parse(data: String): Path {
        val path = Path()
        var index = 0
        val length = data.length
        var command = ' '
        var currentX = 0f
        var currentY = 0f
        var startX = 0f
        var startY = 0f
        

        fun skipSeparators() {
            while (index < length) {
                val ch = data[index]
                if (ch == ' ' || ch == ',' || ch == '\n' || ch == '\t' || ch == '\r') index += 1
                else break
            }
        }

        fun readNumber(): Float {
            skipSeparators()
            val begin = index
            var seenDigit = false
            var seenDot = false
            var seenExp = false
            while (index < length) {
                val ch = data[index]
                when {
                    ch == '-' || ch == '+' -> {
                        
                        if (index != begin && !(seenExp && (data[index - 1] == 'e' || data[index - 1] == 'E'))) break
                        index += 1
                    }
                    ch in '0'..'9' -> {
                        seenDigit = true
                        index += 1
                    }
                    ch == '.' && !seenDot && !seenExp -> {
                        seenDot = true
                        index += 1
                    }
                    (ch == 'e' || ch == 'E') && seenDigit && !seenExp -> {
                        seenExp = true
                        index += 1
                    }
                    else -> break
                }
            }
            if (index == begin || !seenDigit) {
                throw IllegalArgumentException("svg path: expected number at $begin in \"$data\"")
            }
            return data.substring(begin, index).toFloat()
        }

        while (index < length) {
            skipSeparators()
            if (index >= length) break
            val ch = data[index]
            if (ch.isLetter()) {
                command = ch
                index += 1
            }
            
            when (command) {
                'M', 'm' -> {
                    val relative = command == 'm'
                    val x = readNumber()
                    val y = readNumber()
                    currentX = if (relative) currentX + x else x
                    currentY = if (relative) currentY + y else y
                    path.moveTo(currentX, currentY)
                    startX = currentX
                    startY = currentY
                    command = if (relative) 'l' else 'L'
                }
                'L', 'l' -> {
                    val relative = command == 'l'
                    val x = readNumber()
                    val y = readNumber()
                    currentX = if (relative) currentX + x else x
                    currentY = if (relative) currentY + y else y
                    path.lineTo(currentX, currentY)
                }
                'H', 'h' -> {
                    val x = readNumber()
                    currentX = if (command == 'h') currentX + x else x
                    path.lineTo(currentX, currentY)
                }
                'V', 'v' -> {
                    val y = readNumber()
                    currentY = if (command == 'v') currentY + y else y
                    path.lineTo(currentX, currentY)
                }
                'Q', 'q' -> {
                    val relative = command == 'q'
                    val cx = readNumber()
                    val cy = readNumber()
                    val x = readNumber()
                    val y = readNumber()
                    val controlX = if (relative) currentX + cx else cx
                    val controlY = if (relative) currentY + cy else cy
                    currentX = if (relative) currentX + x else x
                    currentY = if (relative) currentY + y else y
                    path.quadTo(controlX, controlY, currentX, currentY)
                }
                'C', 'c' -> {
                    val relative = command == 'c'
                    val c1x = readNumber()
                    val c1y = readNumber()
                    val c2x = readNumber()
                    val c2y = readNumber()
                    val x = readNumber()
                    val y = readNumber()
                    val control1X = if (relative) currentX + c1x else c1x
                    val control1Y = if (relative) currentY + c1y else c1y
                    val control2X = if (relative) currentX + c2x else c2x
                    val control2Y = if (relative) currentY + c2y else c2y
                    currentX = if (relative) currentX + x else x
                    currentY = if (relative) currentY + y else y
                    path.cubicTo(control1X, control1Y, control2X, control2Y, currentX, currentY)
                }
                'Z', 'z' -> {
                    path.close()
                    currentX = startX
                    currentY = startY
                }
                else -> throw IllegalArgumentException("svg path: unsupported command '$command' in \"$data\"")
            }
        }
        return path
    }
}
