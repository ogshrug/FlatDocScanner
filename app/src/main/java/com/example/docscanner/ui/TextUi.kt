package com.example.docscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.docscanner.R
import com.example.docscanner.data.PageEntity
import com.example.docscanner.databinding.SheetPageTextBinding
import com.example.docscanner.ocr.OcrSettings
import com.example.docscanner.ocr.PageText
import com.example.docscanner.ocr.PageTextCodec
import com.example.docscanner.ocr.Script
import com.example.docscanner.repository
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Text recognition is opt-in: nothing here runs unless the user asks for one page, a
 * selection, or the whole document.
 */
object TextUi {

    /** Shows the page's text, recognising it first if it hasn't been yet. */
    fun showPageText(activity: AppCompatActivity, page: PageEntity, number: Int) {
        val b = SheetPageTextBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity).apply { setContentView(b.root) }
        b.title.text = activity.getString(R.string.text_sheet_title, number)
        var text: PageText? = PageTextCodec.decode(page.ocr)

        fun show(t: PageText) {
            text = t
            b.progress.isVisible = false
            val plain = t.plain()
            b.text.text = plain.ifBlank { activity.getString(R.string.text_sheet_empty) }
            b.copyButton.isEnabled = plain.isNotBlank()
            b.shareButton.isEnabled = plain.isNotBlank()
            b.removeButton.isEnabled = true
        }

        b.copyButton.setOnClickListener {
            val plain = text?.plain() ?: return@setOnClickListener
            activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(b.title.text, plain))
            Toast.makeText(activity, R.string.text_copied, Toast.LENGTH_SHORT).show()
        }
        b.shareButton.setOnClickListener {
            val plain = text?.plain() ?: return@setOnClickListener
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, plain)
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_text_chooser)))
        }
        b.removeButton.setOnClickListener {
            activity.lifecycleScope.launch { activity.repository.removeText(page.id) }
            dialog.dismiss()
        }

        val existing = text
        if (existing != null) {
            show(existing)
        } else {
            b.progress.isVisible = true
            b.text.setText(R.string.recognizing)
            b.copyButton.isEnabled = false
            b.shareButton.isEnabled = false
            b.removeButton.isEnabled = false
            val job = activity.lifecycleScope.launch {
                runCatching { activity.repository.recognizeText(page, OcrSettings.script(activity)) }
                    .onSuccess { show(it) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        dialog.dismiss()
                        Toast.makeText(activity, R.string.recognize_failed, Toast.LENGTH_LONG).show()
                    }
            }
            dialog.setOnDismissListener { job.cancel() }
        }
        dialog.show()
    }

    /**
     * Recognises the text on [pages] one at a time with a progress dialog; Cancel stops after
     * the current page. Pages that already have text are skipped.
     */
    fun recognizePages(activity: AppCompatActivity, pages: List<PageEntity>, onDone: () -> Unit = {}) {
        val todo = pages.filter { it.ocr == null }
        if (todo.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.recognize_done, pages.size, pages.size), Toast.LENGTH_SHORT).show()
            onDone()
            return
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val bar = LinearProgressIndicator(activity).apply {
            max = todo.size
            setPadding(pad, pad / 2, pad, 0)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.recognizing_progress, 1, todo.size))
            .setView(bar)
            .setCancelable(false)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        val job = activity.lifecycleScope.launch {
            var failed = 0
            todo.forEachIndexed { i, page ->
                dialog.setTitle(activity.getString(R.string.recognizing_progress, i + 1, todo.size))
                runCatching { activity.repository.recognizeText(page, OcrSettings.script(activity)) }
                    .onFailure { if (it is CancellationException) throw it else failed++ }
                bar.setProgressCompat(i + 1, true)
            }
            dialog.dismiss()
            val done = pages.size - failed
            Toast.makeText(activity, activity.getString(R.string.recognize_done, done, pages.size), Toast.LENGTH_SHORT).show()
            onDone()
        }
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                job.cancel()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    fun chooseScript(activity: AppCompatActivity) {
        val scripts = Script.entries
        val labels = arrayOf(activity.getString(R.string.script_latin), activity.getString(R.string.script_devanagari))
        val current = scripts.indexOf(OcrSettings.script(activity))
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_text_language)
            .setSingleChoiceItems(labels, current) { d, which ->
                OcrSettings.setScript(activity, scripts[which])
                d.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun confirmRemoveAll(activity: AppCompatActivity, documentId: Long) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.remove_text_title)
            .setMessage(R.string.remove_text_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_remove_text) { _, _ ->
                activity.lifecycleScope.launch { activity.repository.removeAllText(documentId) }
            }
            .show()
    }
}
