package io.github.ogshrug.flatdocscanner.ocr

/** Which ML Kit recogniser to use. Devanagari also reads Latin letters (Hindi, Marathi, Nepali...). */
enum class Script { LATIN, DEVANAGARI }

/** One recognised line; the box is in 0..1 fractions of the page image. */
data class OcrLine(val text: String, val left: Double, val top: Double, val right: Double, val bottom: Double)

/** A block is a paragraph or column chunk, as ML Kit groups it. */
data class OcrBlock(val lines: List<OcrLine>)

/** Everything recognised on one page, in reading order. */
data class PageText(val script: Script, val blocks: List<OcrBlock>) {

    val isEmpty get() = blocks.all { b -> b.lines.all { it.text.isBlank() } }

    /** Lines as recognised; blocks separated by a blank line. */
    fun plain(): String = blocks
        .map { b -> b.lines.joinToString("\n") { it.text.trim() }.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

    /** Lines containing [query], ignoring case and runs of whitespace; empty for a blank query. */
    fun linesMatching(query: String): List<OcrLine> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return blocks.flatMap { it.lines }.filter { normalize(it.text).contains(q) }
    }

    /** Each block as one flowing paragraph, joining words hyphenated across lines. */
    fun paragraphs(): List<String> = blocks.map { joinLines(it.lines.map { l -> l.text.trim() }) }.filter { it.isNotEmpty() }

    companion object {
        private val SPACES = Regex("\\s+")

        fun normalize(s: String) = s.trim().replace(SPACES, " ").lowercase()

        /**
         * Joins lines into a paragraph. A line ending in a hyphen followed by a line starting in
         * lower case is one word split across lines ("docu-" + "ment" → "document").
         */
        fun joinLines(lines: List<String>): String {
            val sb = StringBuilder()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (sb.isEmpty()) {
                    sb.append(line)
                } else if (sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter() && line[0].isLowerCase()) {
                    sb.setLength(sb.length - 1)
                    sb.append(line)
                } else {
                    sb.append(' ').append(line)
                }
            }
            return sb.toString()
        }
    }
}

/**
 * Builds the text exports for a whole document. A null page was never recognised (OCR is
 * opt-in per page); both that and a page with no text are noted rather than skipped.
 */
object TextExport {

    fun plainText(title: String, pages: List<PageText?>): String = buildString {
        append(title).append("\n\n")
        pages.forEachIndexed { i, page ->
            val header = "Page ${i + 1}"
            append(header).append('\n').append("-".repeat(header.length)).append("\n\n")
            append(if (page == null) NOT_RECOGNIZED else page.plain().ifBlank { NO_TEXT })
            append("\n\n")
        }
    }.trimEnd() + "\n"

    fun markdown(title: String, pages: List<PageText?>): String = buildString {
        append("# ").append(escape(title)).append("\n\n")
        pages.forEachIndexed { i, page ->
            append("## Page ").append(i + 1).append("\n\n")
            val paragraphs = page?.paragraphs().orEmpty()
            if (paragraphs.isEmpty()) append('_').append(if (page == null) NOT_RECOGNIZED else NO_TEXT).append("_\n\n")
            paragraphs.forEach { append(escape(it)).append("\n\n") }
        }
    }.trimEnd() + "\n"

    /** Escapes characters that would otherwise start Markdown formatting at the beginning of a line. */
    private fun escape(s: String): String {
        val t = s.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
        return if (t.matches(Regex("^(#|>|-|\\+|\\d+\\.)\\s.*"))) "\\$t" else t
    }

    const val NO_TEXT = "(no text found)"
    const val NOT_RECOGNIZED = "(text not recognized)"
}
