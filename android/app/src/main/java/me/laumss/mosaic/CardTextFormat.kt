package me.laumss.mosaic


object CardTextFormat {
    
    class Metrics(
        val body: Float, val lineMul: Float, val padX: Float, val padTop: Float, val padBottom: Float,
        val h1: Float, val h2: Float, val h3: Float,
        val headingLineMul: Float = 1.2f, val headingGapEm: Float = 0.4f, val paraGapEm: Float = 0.5f,
    )
    val WEB = Metrics(18f, 1.45f, 14f, 8f, 12f, 28.8f, 24.3f, 20.7f)
    val DEMO = Metrics(16f, 1.28f, 16f, 12f, 10f, 25.6f, 21.6f, 18.4f)
    fun metricsFor(cardId: String?): Metrics = if (cardId != null && cardId.startsWith("demo-")) DEMO else WEB

    const val BODY_SIZE = 18f
    fun textSize(heading: Int, m: Metrics = WEB): Float = when (heading) { 1 -> m.h1; 2 -> m.h2; 3, 4, 5, 6 -> m.h3; else -> m.body }

    data class Style(val heading: Int = 0, val bold: Boolean = false, val italic: Boolean = false,
                     val black: Boolean = false, val indent: String = "") {
        fun toggle(kind: String): Style = when (kind) {
            "heading" -> copy(heading = if (heading > 0) 0 else 1)
            "bold" -> copy(bold = !bold)
            "italic" -> copy(italic = !italic)
            "black" -> copy(black = !black)
            "indent" -> copy(indent = indent + "    ")
            "outdent" -> copy(indent = indent.dropLast(minOf(4, indent.length)))
            else -> this
        }
        fun active(kind: String): Boolean = when (kind) {
            "heading" -> heading > 0; "bold" -> bold; "italic" -> italic; "black" -> black; else -> false
        }
    }
    data class Line(val text: String, val style: Style, val original: String? = null) {
        fun markdown(): String {
            original?.let { if (parse(it).let { p -> p.text == text && p.style == style }) return it }
            val marks = if (style.bold && style.italic) "***" else if (style.bold) "**" else if (style.italic) "*" else ""
            return style.indent + (if (style.black) "> " else "") +
                (if (style.heading > 0) "#".repeat(style.heading) + " " else "") + marks + text + marks
        }
    }
    fun parse(raw: String): Line {
        val indent = raw.takeWhile { it == ' ' || it == '\t' }
        var body = raw.drop(indent.length)
        val black = body.startsWith("> ")
        if (black) body = body.drop(2)
        val heading = Regex("^(#{1,6}) ").find(body)?.groupValues?.get(1)?.length ?: 0
        if (heading > 0) body = body.drop(heading + 1)
        var bold = false
        var italic = false
        
        repeat(2) {
            val n = when {
                body.length >= 6 && body.startsWith("***") && body.endsWith("***") -> 3
                body.length >= 4 && body.startsWith("**") && body.endsWith("**") -> 2
                body.length >= 2 && body.startsWith("*") && body.endsWith("*") -> 1
                else -> 0
            }
            if (n >= 2) bold = true
            if (n == 1 || n == 3) italic = true
            if (n > 0) body = body.substring(n, body.length - n)
        }
        return Line(body, Style(heading, bold, italic, black, indent), raw)
    }

    class Document(markdown: String) {
        val lines = markdown.replace("\r\n", "\n").split('\n').map(::parse).toMutableList()
        fun text(): String = lines.joinToString("\n") { it.text }
        fun markdown(): String = lines.joinToString("\n") { it.markdown() }
        fun rowAt(cursor: Int, text: String = text()): Int {
            var row = 0
            for (i in 0 until cursor.coerceIn(0, text.length)) if (text[i] == '\n') row++
            return row.coerceAtMost(lines.lastIndex)
        }
        fun toggle(cursor: Int, kind: String) {
            val row = rowAt(cursor)
            lines[row] = lines[row].copy(style = lines[row].style.toggle(kind))
        }
        
        fun replace(start: Int, removed: Int, inserted: String): Triple<Int, Int, Int> {
            val old = text()
            val from = start.coerceIn(0, old.length)
            val to = (from + removed).coerceIn(from, old.length)
            val first = rowAt(from, old)
            val last = rowAt(to, old)
            val firstStart = old.lastIndexOf('\n', from - 1) + 1
            val lastStart = old.lastIndexOf('\n', to - 1) + 1
            val joined = lines[first].text.take(from - firstStart) + inserted + lines[last].text.drop(to - lastStart)
            val parts = joined.split('\n')
            val replacement = parts.mapIndexed { i, value ->
                val template = if (i == parts.lastIndex && i > 0 && last > first) lines[last] else lines[first]
                template.copy(text = value)
            }
            repeat(last - first + 1) { lines.removeAt(first) }
            lines.addAll(first, replacement)
            return Triple(first, last - first + 1, replacement.size)
        }
    }
}
