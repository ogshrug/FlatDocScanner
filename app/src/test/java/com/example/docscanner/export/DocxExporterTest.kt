package com.example.docscanner.export

import com.example.docscanner.ocr.OcrBlock
import com.example.docscanner.ocr.OcrLine
import com.example.docscanner.ocr.PageText
import com.example.docscanner.ocr.Script
import com.example.docscanner.ocr.TextExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxExporterTest {

    private fun page(vararg blocks: String) =
        PageText(Script.LATIN, blocks.map { OcrBlock(listOf(OcrLine(it, 0.0, 0.0, 1.0, 0.1))) })

    @Test
    fun packageHasTheThreeRequiredPartsAndValidXml() {
        val out = ByteArrayOutputStream()
        DocxExporter.write("Notes & <ideas>", listOf(page("First", "Tom & \"Jerry\" <3"), null), out)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), entries.keys)
        // Every part must parse as XML.
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        entries.values.forEach { parser.parse(ByteArrayInputStream(it.toByteArray())) }
        val doc = entries.getValue("word/document.xml")
        assertTrue(doc.contains("Notes &amp; &lt;ideas&gt;"))
        assertTrue(doc.contains("Tom &amp; &quot;Jerry&quot; &lt;3"))
        assertTrue(doc.contains("""<w:br w:type="page"/>"""))
        assertTrue(doc.contains(TextExport.NOT_RECOGNIZED))
    }

    @Test
    fun escapeDropsControlCharacters() {
        assertEquals("ab\tc", DocxExporter.escape("a\u0001b\tc\u0007"))
    }

    @Test
    fun zipContainsEveryFile() {
        val dir = Files.createTempDirectory("zip").toFile()
        val a = File(dir, "a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val b = File(dir, "b.jpg").apply { writeBytes(byteArrayOf(4)) }
        val out = ByteArrayOutputStream()
        ZipExporter.write(listOf("Doc - page 1.jpg" to a, "Doc - page 2.jpg" to b), out)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(listOf("Doc - page 1.jpg", "Doc - page 2.jpg"), names)
        dir.deleteRecursively()
    }
}
