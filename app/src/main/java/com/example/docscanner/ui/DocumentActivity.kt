package com.example.docscanner.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.docscanner.R
import com.example.docscanner.data.PageEntity
import com.example.docscanner.databinding.ActivityDocumentBinding
import com.example.docscanner.databinding.ItemPageBinding
import com.example.docscanner.repository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Pages of one document (S03): reorder, edit, delete, add more, share as PDF or images.
 * Tapping a page opens it full screen; "Select pages" switches to a selection mode for
 * sharing or deleting some pages only.
 */
class DocumentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentBinding
    private val documentId by lazy { intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L) }
    private var title = ""
    private val adapter = PageAdapter()
    private var dragging = false

    private var selecting = false
    private val selected = linkedSetOf<Long>()
    private val exitSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSelecting(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.grid.padForSystemBars(bottom = true)
        onBackPressedDispatcher.addCallback(this, exitSelection)

        binding.toolbar.setNavigationOnClickListener { if (selecting) setSelecting(false) else finish() }
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_share_pdf -> sharePdf(currentPagesForAction())
                R.id.action_share_images -> shareImages(currentPagesForAction())
                R.id.action_select -> setSelecting(true)
                R.id.action_select_all -> selectAll()
                R.id.action_recognize_all -> TextUi.recognizePages(this, adapter.pages.toList())
                R.id.action_recognize_selected -> {
                    val pages = currentPagesForAction()
                    setSelecting(false)
                    TextUi.recognizePages(this, pages)
                }
                R.id.action_remove_text_all -> TextUi.confirmRemoveAll(this, documentId)
                R.id.action_text_language -> TextUi.chooseScript(this)
                R.id.action_share_text -> shareText(markdown = false)
                R.id.action_share_markdown -> shareText(markdown = true)
                R.id.action_delete_selected -> confirmDeleteSelected()
                R.id.action_rename -> rename()
                R.id.action_delete -> confirmDeleteDocument()
            }
            true
        }
        binding.addPageButton.setOnClickListener { startActivity(CameraActivity.intent(this, documentId)) }

        val span = if (resources.configuration.screenWidthDp >= 600) 4 else 2
        binding.grid.layoutManager = GridLayoutManager(this, span)
        binding.grid.adapter = adapter
        ItemTouchHelper(DragCallback()).attachToRecyclerView(binding.grid)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observeDocument(documentId).collect { doc ->
                        if (doc == null) finish() else {
                            title = doc.title
                            if (!selecting) binding.toolbar.title = doc.title
                        }
                    }
                }
                launch {
                    repository.observePages(documentId).collect { if (!dragging) adapter.submit(it) }
                }
            }
        }
    }

    // --- selection ---------------------------------------------------------------------

    private fun setSelecting(on: Boolean) {
        selecting = on
        exitSelection.isEnabled = on
        if (!on) selected.clear()
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(if (on) R.menu.document_selection else R.menu.document)
        binding.toolbar.setNavigationIcon(if (on) R.drawable.ic_close else R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationContentDescription(if (on) R.string.action_cancel_selection else R.string.action_back)
        binding.addPageButton.isVisible = !on
        updateSelectionTitle()
        refreshChecks()
    }

    private fun toggle(page: PageEntity) {
        if (!selected.add(page.id)) selected.remove(page.id)
        refreshChecks()
        updateSelectionTitle()
    }

    private fun selectAll() {
        if (selected.size == adapter.pages.size) selected.clear() else adapter.pages.forEach { selected += it.id }
        refreshChecks()
        updateSelectionTitle()
    }

    /**
     * Updates the tick on the visible pages without rebinding them, so a page being held
     * for dragging isn't interrupted.
     */
    private fun refreshChecks() {
        for (i in 0 until binding.grid.childCount) {
            val holder = binding.grid.getChildViewHolder(binding.grid.getChildAt(i)) as? PageAdapter.Holder ?: continue
            val page = adapter.pages.getOrNull(holder.bindingAdapterPosition) ?: continue
            holder.b.root.isChecked = selecting && page.id in selected
        }
    }

    /** Holding a page selects it (and starts selection mode); holding and dragging moves it. */
    private fun selectByHold(position: Int) {
        val page = adapter.pages.getOrNull(position) ?: return
        if (!selecting) setSelecting(true)
        selected += page.id
        refreshChecks()
        updateSelectionTitle()
    }

    private fun updateSelectionTitle() {
        binding.toolbar.title = if (selecting) {
            if (selected.isEmpty()) getString(R.string.select_pages_hint)
            else resources.getQuantityString(R.plurals.selected_count, selected.size, selected.size)
        } else title
        val any = selected.isNotEmpty()
        listOf(R.id.action_share_images, R.id.action_share_pdf, R.id.action_delete_selected).forEach {
            if (selecting) binding.toolbar.menu.findItem(it)?.isEnabled = any
        }
    }

    /** Selected pages in document order while selecting, otherwise every page. */
    private fun currentPagesForAction(): List<PageEntity> =
        if (selecting) adapter.pages.filter { it.id in selected } else adapter.pages.toList()

    private fun confirmDeleteSelected() {
        val pages = currentPagesForAction()
        if (pages.isEmpty()) return
        val all = pages.size == adapter.pages.size
        MaterialAlertDialogBuilder(this)
            .setTitle(resources.getQuantityString(R.plurals.delete_pages_title, pages.size, pages.size))
            .setMessage(if (all) R.string.delete_page_last_body else R.string.delete_page_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    setSelecting(false)
                    pages.forEach { repository.deletePage(it) }
                }
            }
            .show()
    }

    // --- document actions --------------------------------------------------------------

    private fun confirmDeleteDocument() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_document_title)
            .setMessage(R.string.delete_document_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    repository.deleteDocument(documentId)
                    finish()
                }
            }
            .show()
    }

    private fun rename() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(title)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_rename) { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) lifecycleScope.launch { repository.renameDocument(documentId, newTitle) }
            }
            .show()
        input.requestFocus()
    }

    // --- sharing -----------------------------------------------------------------------

    private fun sharePdf(pages: List<PageEntity>) {
        if (pages.isEmpty()) return
        export(R.string.export_failed) { Sharing.pdf(this, pages, title) }
    }

    /** Shares the recognised text; offers to recognise it first if no page has any yet. */
    private fun shareText(markdown: Boolean) {
        val pages = adapter.pages.toList()
        if (pages.isEmpty()) return
        val share = { export(R.string.export_failed) { Sharing.text(this, pagesNow(pages), title, markdown) } }
        if (pages.any { it.ocr != null }) {
            share()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.no_text_yet_title)
            .setMessage(R.string.no_text_yet_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_recognize) { _, _ -> TextUi.recognizePages(this, pages) { share() } }
            .show()
    }

    /** The latest stored version of [pages], so text recognised a moment ago is included. */
    private suspend fun pagesNow(pages: List<PageEntity>): List<PageEntity> {
        val ids = pages.map { it.id }.toSet()
        return repository.getPages(documentId).filter { it.id in ids }
    }

    private fun shareImages(pages: List<PageEntity>) {
        if (pages.isEmpty()) return
        val numbers = pages.map { p -> adapter.pages.indexOfFirst { it.id == p.id } + 1 }
        export(R.string.export_images_failed) { Sharing.images(this, pages, numbers, title) }
    }

    /** Runs [build] off the main thread with a spinner, then launches the share sheet it returns. */
    private fun export(errorText: Int, build: suspend () -> Intent) {
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val chooser = runCatching { withContext(Dispatchers.IO) { build() } }.getOrNull()
            binding.progress.isVisible = false
            if (chooser == null) {
                Toast.makeText(this@DocumentActivity, errorText, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (selecting) setSelecting(false)
            startActivity(chooser)
        }
    }

    // --- grid ----------------------------------------------------------------------------

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        val pages = mutableListOf<PageEntity>()

        inner class Holder(val b: ItemPageBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<PageEntity>) {
            pages.clear()
            pages.addAll(list)
            selected.retainAll(list.map { it.id }.toSet())
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
            if (selecting) updateSelectionTitle()
        }

        fun move(from: Int, to: Int) {
            Collections.swap(pages, from, to)
            notifyItemMoved(from, to)
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages[position]
            val label = getString(R.string.page_number, position + 1)
            holder.b.number.text = if (page.ocr != null) getString(R.string.text_page_label, label) else label
            holder.b.root.isChecked = selecting && page.id in selected
            Thumbnails.load(holder.b.image, page.processedPath, 512, lifecycleScope)
            holder.b.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (selecting) toggle(pages[pos])
                else startActivity(PageViewerActivity.intent(this@DocumentActivity, documentId, pages[pos].id))
            }
        }
    }

    /**
     * Long-press selects the page; long-press and drag reorders. The new order is saved when
     * the page is dropped.
     */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0,
    ) {
        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                selectByHold(viewHolder.bindingAdapterPosition)
            }
        }

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            dragging = true
            adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            if (!dragging) return
            val order = adapter.pages.map { it.id }
            lifecycleScope.launch {
                repository.reorder(documentId, order)
                dragging = false
                adapter.submit(repository.getPages(documentId))
            }
        }
    }

    companion object {
        private const val EXTRA_DOCUMENT_ID = "document_id"

        fun intent(context: Context, documentId: Long) =
            Intent(context, DocumentActivity::class.java).putExtra(EXTRA_DOCUMENT_ID, documentId)
    }
}
