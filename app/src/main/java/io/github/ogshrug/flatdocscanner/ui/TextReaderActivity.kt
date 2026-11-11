package io.github.ogshrug.flatdocscanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.ogshrug.flatdocscanner.R
import io.github.ogshrug.flatdocscanner.data.PageEntity
import io.github.ogshrug.flatdocscanner.databinding.ActivityTextReaderBinding
import io.github.ogshrug.flatdocscanner.databinding.ItemTextPageBinding
import io.github.ogshrug.flatdocscanner.ocr.OcrSettings
import io.github.ogshrug.flatdocscanner.ocr.PageText
import io.github.ogshrug.flatdocscanner.ocr.PageTextCodec
import io.github.ogshrug.flatdocscanner.ocr.Script
import io.github.ogshrug.flatdocscanner.repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen reader for the recognised text, one page per swipe, with copy, share and read
 * aloud. Reading aloud continues onto the next page. Pages whose text hasn't been recognised
 * offer to recognise it (recognition stays opt-in).
 */
class TextReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextReaderBinding
    private val documentId by lazy { intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L) }
    private val adapter = TextPageAdapter()
    private var positioned = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var reading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableAppEdgeToEdge()
        binding = ActivityTextReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener {
            currentPage()?.let { p -> lifecycleScope.launch { repository.removeText(p.id) } }
            true
        }
        binding.actionCopy.setOnClickListener {
            currentText()?.let { t ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(binding.toolbar.title, t))
                Toast.makeText(this, R.string.text_copied, Toast.LENGTH_SHORT).show()
            }
        }
        binding.actionShare.setOnClickListener {
            currentText()?.let { t ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
                startActivity(Intent.createChooser(send, getString(R.string.share_text_chooser)))
            }
        }
        binding.actionRead.setOnClickListener { if (reading) stopReading() else startReading() }

        // Image | Text: Image goes back to the page image, at the page shown here.
        binding.modeSwitch.group.check(R.id.modeText)
        binding.modeSwitch.group.addOnButtonCheckedListener { _, id, checked ->
            if (checked && id == R.id.modeImage) finish()
        }

        binding.pager.adapter = adapter
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTitle()
                // Swiping by hand while reading reads the new page.
                if (reading) speakCurrent()
            }
        })

        val startPageId = savedInstanceState?.getLong(EXTRA_PAGE_ID) ?: intent.getLongExtra(EXTRA_PAGE_ID, -1L)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repository.observeDocument(documentId).collect { doc -> doc?.let { binding.toolbar.title = it.title } } }
                repository.observePages(documentId).collect { pages ->
                    if (pages.isEmpty()) {
                        finish()
                        return@collect
                    }
                    val keepId = if (positioned) currentPage()?.id else startPageId
                    adapter.submit(pages)
                    val target = pages.indexOfFirst { it.id == keepId }.takeIf { it >= 0 } ?: 0
                    binding.pager.setCurrentItem(target, false)
                    positioned = true
                    updateTitle()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPage()?.let { outState.putLong(EXTRA_PAGE_ID, it.id) }
    }

    override fun onStop() {
        super.onStop()
        stopReading()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }

    private fun currentPage(): PageEntity? = adapter.pages.getOrNull(binding.pager.currentItem)

    private fun currentText(): String? =
        currentPage()?.let { PageTextCodec.decode(it.ocr) }?.paragraphs()?.joinToString("\n\n")?.takeIf { it.isNotBlank() }

    override fun finish() {
        currentPage()?.let { setResult(RESULT_OK, Intent().putExtra(RESULT_PAGE_ID, it.id)) }
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun updateTitle() {
        val n = adapter.pages.size
        if (n > 0) binding.toolbar.subtitle = getString(R.string.viewer_subtitle, binding.pager.currentItem + 1, n)
    }

    // --- read aloud ----------------------------------------------------------------------

    private fun startReading() {
        reading = true
        updateReadButton()
        val engine = tts
        if (engine == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
                        stopReading()
                    }
                    return@TextToSpeech
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) {
                        if (id == LAST_CHUNK) runOnUiThread { nextPageOrStop() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        runOnUiThread { stopReading() }
                    }
                })
                runOnUiThread { if (reading) speakCurrent() }
            }
        } else if (ttsReady) {
            speakCurrent()
        }
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        if (!ttsReady) return
        val text = currentPage()?.let { PageTextCodec.decode(it.ocr) }
        val plain = text?.paragraphs()?.joinToString("\n\n").orEmpty()
        if (plain.isBlank()) {
            nextPageOrStop()
            return
        }
        engine.language = localeFor(text?.script ?: OcrSettings.script(this))
        // Engines limit utterance length; queue the page in chunks, the last one tagged.
        val chunks = plain.chunked(TextToSpeech.getMaxSpeechInputLength() - 1)
        chunks.forEachIndexed { i, chunk ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunk, mode, null, if (i == chunks.lastIndex) LAST_CHUNK else "chunk$i")
        }
    }

    private fun nextPageOrStop() {
        if (!reading) return
        val next = binding.pager.currentItem + 1
        if (next < adapter.pages.size) binding.pager.currentItem = next // onPageSelected speaks it
        else stopReading()
    }

    private fun stopReading() {
        reading = false
        tts?.stop()
        updateReadButton()
    }

    private fun updateReadButton() {
        binding.actionRead.setText(if (reading) R.string.action_stop_reading else R.string.action_read_aloud)
        binding.actionRead.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, if (reading) R.drawable.ic_stop else R.drawable.ic_read_aloud, 0, 0,
        )
    }

    private fun localeFor(script: Script): Locale =
        if (script == Script.DEVANAGARI) Locale.forLanguageTag("hi-IN") else Locale.getDefault()

    // --- pages ---------------------------------------------------------------------------

    private inner class TextPageAdapter : RecyclerView.Adapter<TextPageAdapter.Holder>() {
        val pages = mutableListOf<PageEntity>()

        inner class Holder(val b: ItemTextPageBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<PageEntity>) {
            pages.clear()
            pages.addAll(list)
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTextPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages[position]
            val text: PageText? = PageTextCodec.decode(page.ocr)
            val b = holder.b
            b.scroll.isVisible = text != null && !text.isEmpty
            b.empty.isVisible = !b.scroll.isVisible
            b.recognizing.isVisible = false
            if (text != null && !text.isEmpty) {
                b.text.text = text.paragraphs().joinToString("\n\n")
                return
            }
            b.emptyText.setText(if (text == null) R.string.text_not_recognized_body else R.string.text_sheet_empty)
            b.recognizeButton.isVisible = text == null
            b.recognizeButton.setOnClickListener {
                b.recognizeButton.isVisible = false
                b.recognizing.isVisible = true
                lifecycleScope.launch {
                    // The stored page updates, and the new text arrives through observePages.
                    runCatching { repository.recognizeText(page, OcrSettings.script(this@TextReaderActivity)) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            android.util.Log.w("FlatDocScanner", "Text recognition failed", it)
                            b.recognizing.isVisible = false
                            b.recognizeButton.isVisible = true
                            Toast.makeText(this@TextReaderActivity, R.string.recognize_failed, Toast.LENGTH_LONG).show()
                        }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_DOCUMENT_ID = "document_id"
        private const val EXTRA_PAGE_ID = "page_id"
        private const val LAST_CHUNK = "last"
        const val RESULT_PAGE_ID = "result_page_id"

        fun intent(context: Context, documentId: Long, pageId: Long) =
            Intent(context, TextReaderActivity::class.java)
                .putExtra(EXTRA_DOCUMENT_ID, documentId)
                .putExtra(EXTRA_PAGE_ID, pageId)
    }
}
