package com.github.borgand.marginalia.core

/** ATX-heading extraction for markdown docs — anchoring context per PRD F1/§6. */
object MarkdownHeadings {

    data class Heading(val level: Int, val text: String, val offset: Int)

    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*$""")

    fun headings(text: String): List<Heading> {
        val result = mutableListOf<Heading>()
        var offset = 0
        for (line in text.lineSequence()) {
            HEADING.matchEntire(line)?.let { m ->
                result += Heading(m.groupValues[1].length, m.groupValues[2], offset)
            }
            offset += line.length + 1
        }
        return result
    }

    /** Heading breadcrumb (outermost first) covering [offset], e.g. ["Top", "Section"]. */
    fun pathAt(text: String, offset: Int): List<String> {
        val stack = ArrayDeque<Heading>()
        for (h in headings(text)) {
            if (h.offset > offset) break
            while (stack.isNotEmpty() && stack.last().level >= h.level) stack.removeLast()
            stack.addLast(h)
        }
        return stack.map { it.text }
    }
}
