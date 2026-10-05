package io.github.ogshrug.flatdocscanner.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.ogshrug.flatdocscanner.R
import io.github.ogshrug.flatdocscanner.data.PageEntity
import io.github.ogshrug.flatdocscanner.export.DocxExporter
import io.github.ogshrug.flatdocscanner.export.ImageExporter
import io.github.ogshrug.flatdocscanner.export.ZipExporter
import io.github.ogshrug.flatdocscanner.export.PdfExporter
import io.github.ogshrug.flatdocscanner.export.PdfOptions
import io.github.ogshrug.flatdocscanner.ocr.PageTextCodec
import io.github.ogshrug.flatdocscanner.ocr.TextExport
import java.io.File

/** Builds share-sheet intents for pages. Call off the main thread: these write files. */
object Sharing {

    fun pdf(context: Context, pages: List<PageEntity>, title: String, options: PdfOptions = PdfOptions()): Intent {
        val file = File(freshDir(context), "${safe(title)}.pdf").also { PdfExporter.export(pages, it, options) }
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, context.getString(R.string.share_pdf_chooser))
    }

    /** [numbers] are the pages' numbers in the document, used in the file names. */
    fun images(context: Context, pages: List<PageEntity>, numbers: List<Int>, title: String): Intent {
        val uris = ImageExporter.copyForSharing(pages, numbers, freshDir(context), safe(title)).map { uriFor(context, it) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.setType("image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData carries the read permission for every image, not just the first.
        send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return Intent.createChooser(send, context.getString(R.string.share_images_chooser))
    }

    /** The recognised text of [pages] as a .txt or .md file; unrecognised pages are noted. */
    fun text(context: Context, pages: List<PageEntity>, title: String, markdown: Boolean): Intent {
        val texts = pages.map { PageTextCodec.decode(it.ocr) }
        val body = if (markdown) TextExport.markdown(title, texts) else TextExport.plainText(title, texts)
        val file = File(freshDir(context), "${safe(title)}.${if (markdown) "md" else "txt"}").apply { writeText(body) }
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (markdown) "text/markdown" else "text/plain")
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, context.getString(R.string.share_text_chooser))
    }

    /** The recognised text as a Word document. */
    fun word(context: Context, pages: List<PageEntity>, title: String): Intent {
        val file = File(freshDir(context), "${safe(title)}.docx")
        file.outputStream().use { DocxExporter.write(title, pages.map { PageTextCodec.decode(it.ocr) }, it) }
        return fileIntent(context, file, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    }

    /** All page images in one .zip. [numbers] are the pages' numbers in the document. */
    fun zip(context: Context, pages: List<PageEntity>, numbers: List<Int>, title: String): Intent {
        val file = File(freshDir(context), "${safe(title)}.zip")
        val entries = pages.mapIndexed { i, p -> "${safe(title)} - page ${numbers[i]}.jpg" to File(p.processedPath) }
        file.outputStream().use { ZipExporter.write(entries, it) }
        return fileIntent(context, file, "application/zip")
    }

    /** Images from several folders in one .zip; [named] pairs each page with its file name in the zip. */
    fun zipNamed(context: Context, named: List<Pair<String, PageEntity>>, zipTitle: String): Intent {
        val file = File(freshDir(context), "${safe(zipTitle)}.zip")
        file.outputStream().use { out -> ZipExporter.write(named.map { (n, p) -> "${safe(n)}.jpg" to File(p.processedPath) }, out) }
        return fileIntent(context, file, "application/zip")
    }

    private fun fileIntent(context: Context, file: File, type: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, context.getString(R.string.share_file_chooser))
    }

    private fun safe(title: String) = title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifBlank { "scan" }

    /** Only the latest export is kept in the cache. */
    private fun freshDir(context: Context) = File(context.cacheDir, "exports").apply { deleteRecursively(); mkdirs() }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
