package com.example.docscanner.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.docscanner.R
import com.example.docscanner.data.PageEntity
import com.example.docscanner.ocr.OcrSettings
import com.example.docscanner.ocr.Script
import com.example.docscanner.repository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Text recognition is opt-in: nothing here runs unless the user asks for one page, a
 * selection, or the whole document.
 */
object TextUi {

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
