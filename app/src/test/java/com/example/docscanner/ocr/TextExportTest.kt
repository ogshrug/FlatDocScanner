package com.example.docscanner.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextExportTest {

    private fun line(t: String) = OcrLine(t, 0.0, 0.0, 1.0, 0.1)

    private val page = PageText(
        Script.LATIN,
        listOf(
            OcrBlock(listOf(line("Chapter 1"))),
            OcrBlock(listOf(line("This is a docu-"), line("ment about scan-"), line("ning. It ends -"), line("Here"))),
        ),
    )

    @Test
    fun plainKeepsLinesAndSeparatesBlocks() {
        assertEquals("Chapter 1\n\nThis is a docu-\nment about scan-\nning. It ends -\nHere", page.plain())
    }

    @Test
    fun paragraphsJoinHyphenatedWords() {
        assertEquals(listOf("Chapter 1", "This is a document about scanning. It ends - Here"), page.paragraphs())
    }

    @Test
    fun hyphenAtLineEndJoinsLowerCaseButNotCapitals() {
        assertEquals("document", PageText.joinLines(listOf("docu-", "ment")))
        assertEquals("Anglo- Saxon", PageText.joinLines(listOf("Anglo-", "Saxon")))
        // Known limitation without a dictionary: a compound split exactly at the line end loses
        // its hyphen ("well-" + "known" reads like "docu-" + "ment").
        assertEquals("wellknown", PageText.joinLines(listOf("well-", "known")))
        assertEquals("", PageText.joinLines(listOf("  ", "")))
    }

    @Test
    fun plainTextExportNumbersPagesAndNotesEmptyOnes() {
        val empty = PageText(Script.LATIN, emptyList())
        val txt = TextExport.plainText("My scan", listOf(page, null, empty))
        assertTrue(txt.startsWith("My scan\n\nPage 1\n------\n\nChapter 1"))
        assertTrue(txt.contains("Page 2\n------\n\n${TextExport.NOT_RECOGNIZED}"))
        assertTrue(txt.contains("Page 3\n------\n\n${TextExport.NO_TEXT}"))
        assertTrue(txt.endsWith("\n"))
    }

    @Test
    fun markdownExportUsesHeadingsAndEscapes() {
        val tricky = PageText(Script.LATIN, listOf(OcrBlock(listOf(line("# not a heading"))), OcrBlock(listOf(line("a*b_c")))))
        val md = TextExport.markdown("Notes", listOf(page, tricky))
        assertTrue(md.startsWith("# Notes\n\n## Page 1\n\nChapter 1\n\nThis is a document"))
        assertTrue(md.contains("## Page 2\n\n\\# not a heading\n\na\\*b\\_c"))
    }

    @Test
    fun emptyDetection() {
        assertTrue(PageText(Script.LATIN, emptyList()).isEmpty)
        assertTrue(PageText(Script.LATIN, listOf(OcrBlock(listOf(line("  "))))).isEmpty)
        assertFalse(page.isEmpty)
    }
}
