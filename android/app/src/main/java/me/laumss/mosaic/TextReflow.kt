package me.laumss.mosaic


object TextReflow {
    private const val VERTICAL_MIN_LINES = 8
    private const val VERTICAL_RATIO = 0.7f
    private const val FULL_LINE_RATIO = 0.85f
    
    private const val FULL_LINE_MIN_WIDTH = 24

    private val LIST_START = Regex("^(?:[-*•·●○◆■□▪]\\s|\\d{1,3}[.、)）]|[（(]\\d{1,3}[)）]|[①-⑳]|第[一二三四五六七八九十百千0-9]+[章节回部篇卷])")

    fun reflow(raw: String): String {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\u2028', '\n').replace('\u2029', '\n')
        val blocks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (line in text.split('\n')) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) { blocks.add(current); current = ArrayList() }
            } else {
                current.add(line.trimEnd())
            }
        }
        if (current.isNotEmpty()) blocks.add(current)
        return blocks.joinToString("\n\n") { reflowBlock(it) }
    }

    private fun reflowBlock(lines: List<String>): String {
        if (lines.size == 1) return lines[0].trim()
        val trimmed = lines.map { it.trim() }
        val short = trimmed.count { it.codePointCount(0, it.length) <= 2 }
        if (lines.size >= VERTICAL_MIN_LINES && short >= lines.size * VERTICAL_RATIO) {
            return trimmed.fold("") { acc, s -> join(acc, s) }
        }
        
        val widths = trimmed.map { visualWidth(it) }
        val maxWidth = (if (lines.size == 2) widths else widths.dropLast(1)).max()
        val out = StringBuilder(trimmed[0])
        for (i in 1 until lines.size) {
            val full = widths[i - 1] >= FULL_LINE_MIN_WIDTH && widths[i - 1] >= maxWidth * FULL_LINE_RATIO
            val indented = lines[i].firstOrNull()?.let { it == ' ' || it == '\u3000' || it == '\t' } == true
            val newItem = LIST_START.containsMatchIn(trimmed[i])
            if (full && !indented && !newItem) {
                val merged = join(out.toString(), trimmed[i])
                out.setLength(0); out.append(merged)
            } else {
                out.append('\n').append(trimmed[i])
            }
        }
        return out.toString()
    }

    
    private fun join(a: String, b: String): String {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val last = a.last()
        val first = b.first()
        if (last == '-' && a.length >= 2 && a[a.length - 2].isLetter() && first.isLowerCase()) return a.dropLast(1) + b
        return if (isLatinWord(last) && isLatinWord(first)) "$a $b" else a + b
    }

    private fun isLatinWord(c: Char): Boolean =
        c.code < 0x3000 && (c.isLetterOrDigit() || c in ".,;:!?)]\"'%")

    
    private fun visualWidth(s: String): Int {
        var w = 0
        for (c in s) w += if (c.code >= 0x2E80) 2 else 1
        return w
    }
}
