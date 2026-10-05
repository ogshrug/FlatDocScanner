package io.github.ogshrug.flatdocscanner.export

import io.github.ogshrug.flatdocscanner.ocr.PageText
import io.github.ogshrug.flatdocscanner.ocr.TextExport
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes recognised text as a Word document (.docx): a title, then one paragraph per text
 * block, with a page break between pages. A minimal Office Open XML package, no library.
 */
object DocxExporter {

    fun write(title: String, pages: List<PageText?>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.entry("[Content_Types].xml", CONTENT_TYPES)
            zip.entry("_rels/.rels", RELS)
            zip.entry("word/document.xml", document(title, pages))
        }
    }

    fun document(title: String, pages: List<PageText?>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        append(paragraph(title, bold = true, size = 36))
        pages.forEachIndexed { i, page ->
            if (i > 0) append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            val paragraphs = page?.paragraphs().orEmpty()
            if (paragraphs.isEmpty()) {
                append(paragraph(if (page == null) TextExport.NOT_RECOGNIZED else TextExport.NO_TEXT, italic = true))
            }
            paragraphs.forEach { append(paragraph(it)) }
        }
        append("</w:body></w:document>")
    }

    private fun paragraph(text: String, bold: Boolean = false, italic: Boolean = false, size: Int? = null): String {
        val props = buildString {
            if (bold) append("<w:b/>")
            if (italic) append("<w:i/>")
            if (size != null) append("""<w:sz w:val="$size"/>""")
        }
        val rPr = if (props.isEmpty()) "" else "<w:rPr>$props</w:rPr>"
        return """<w:p><w:r>$rPr<w:t xml:space="preserve">${escape(text)}</w:t></w:r></w:p>"""
    }

    /** XML-escapes text and drops characters XML 1.0 can't contain. */
    fun escape(s: String): String = buildString {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
                else -> append(c)
            }
        }
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """</Types>"""

    private const val RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            """</Relationships>"""
}

/** Zips page images, named "<title> - page <n>.jpg". */
object ZipExporter {
    fun write(files: List<Pair<String, java.io.File>>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
