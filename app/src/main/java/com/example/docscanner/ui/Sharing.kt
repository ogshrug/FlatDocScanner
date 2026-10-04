package com.example.docscanner.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.docscanner.R
import com.example.docscanner.data.PageEntity
import com.example.docscanner.export.ImageExporter
import com.example.docscanner.export.PdfExporter
import com.example.docscanner.ocr.PageTextCodec
import com.example.docscanner.ocr.TextExport
import java.io.File

/** Builds share-sheet intents for pages. Call off the main thread: these write files. */
object Sharing {

    fun pdf(context: Context, pages: List<PageEntity>, title: String): Intent {
        val file = File(freshDir(context), "${safe(title)}.pdf").also { PdfExporter.export(pages, it) }
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

    private fun safe(title: String) = title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifBlank { "scan" }

    /** Only the latest export is kept in the cache. */
    private fun freshDir(context: Context) = File(context.cacheDir, "exports").apply { deleteRecursively(); mkdirs() }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
