package com.example.docscanner.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.example.docscanner.R
import com.example.docscanner.data.PageEntity
import com.example.docscanner.databinding.ActivityDocumentBinding
import com.example.docscanner.databinding.ItemPageBinding
import com.example.docscanner.databinding.ItemPageReaderBinding
import com.example.docscanner.ocr.PageTextCodec
import com.example.docscanner.repository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Pages of one document (S03), as a grid or as a continuous reader. Search the recognised
 * text (matches are marked on the pages), share in several formats, reorder by holding and
 * dragging, select by holding. Tapping a page opens it full screen.
 */
class DocumentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentBinding
    private val documentId by lazy { intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L) }
    private var title = ""
    private val adapter = PageAdapter()
    private var dragging = false
    private val prefs by lazy { getSharedPreferences("document", MODE_PRIVATE) }

    private var readerMode = false
    private var query = ""

    private var selecting = false
    private val selected = linkedSetOf<Long>()
    private val exitSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSelecting(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        binding = ActivityDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.grid.padForSystemBars(bottom = true)
        binding.addPageButton.liftAboveNavigationBar()
        onBackPressedDispatcher.addCallback(this, exitSelection)

        binding.toolbar.setNavigationOnClickListener { if (selecting) setSelecting(false) else finish() }
        binding.toolbar.setOnMenuItemClickListener(::onMenuItem)
        binding.addPageButton.setOnClickListener { startActivity(CameraActivity.intent(this, documentId)) }

        readerMode = prefs.getBoolean(PREF_READER, false)
        binding.grid.adapter = adapter
        applyViewMode()
        setUpSearch()
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

    private fun onMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_view_mode -> {
                readerMode = !readerMode
                prefs.edit { putBoolean(PREF_READER, readerMode) }
                applyViewMode()
            }
            R.id.action_share_pdf -> sharePdf(currentPagesForAction())
            R.id.action_share_images -> shareImages(currentPagesForAction())
            R.id.action_share_zip -> shareZip(currentPagesForAction())
            R.id.action_share_text -> shareText(currentPagesForAction(), markdown = false)
            R.id.action_share_markdown -> shareText(currentPagesForAction(), markdown = true)
            R.id.action_share_word -> shareWord(currentPagesForAction())
            R.id.action_text_view -> adapter.allPages.firstOrNull()?.let {
                startActivity(TextReaderActivity.intent(this, documentId, it.id))
            }
            R.id.action_select -> setSelecting(true)
            R.id.action_select_all -> selectAll()
            R.id.action_recognize_all -> TextUi.recognizePages(this, adapter.allPages.toList())
            R.id.action_recognize_selected -> {
                val pages = currentPagesForAction()
                setSelecting(false)
                TextUi.recognizePages(this, pages)
            }
            R.id.action_remove_text_all -> TextUi.confirmRemoveAll(this, documentId)
            R.id.action_text_language -> TextUi.chooseScript(this)
            R.id.action_delete_selected -> confirmDeleteSelected()
            R.id.action_rename -> rename()
            R.id.action_delete -> confirmDeleteDocument()
        }
        return true
    }

    // --- view mode and search ------------------------------------------------------------

    private fun applyViewMode() {
        val span = if (resources.configuration.screenWidthDp >= 600) 4 else 2
        binding.grid.layoutManager = if (readerMode) LinearLayoutManager(this) else GridLayoutManager(this, span)
        binding.grid.adapter = adapter
        binding.toolbar.menu.findItem(R.id.action_view_mode)?.apply {
            setIcon(if (readerMode) R.drawable.ic_grid else R.drawable.ic_view_stream)
            setTitle(if (readerMode) R.string.action_view_grid else R.string.action_view_reader)
        }
    }

    private fun setUpSearch() {
        val item = binding.toolbar.menu.findItem(R.id.action_search) ?: return
        val view = item.actionView as SearchView
        view.queryHint = getString(R.string.search_hint)
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String) = true.also { view.clearFocus() }
            override fun onQueryTextChange(q: String): Boolean {
                setQuery(q)
                return true
            }
        })
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (adapter.allPages.none { it.ocr != null }) {
                    Toast.makeText(this@DocumentActivity, R.string.search_needs_text, Toast.LENGTH_LONG).show()
                }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                setQuery("")
                return true
            }
        })
    }

    private fun setQuery(q: String) {
        query = q.trim()
        adapter.refilter()
        binding.toolbar.subtitle = when {
            query.isEmpty() -> null
            adapter.pages.isEmpty() -> getString(R.string.search_no_results)
            else -> resources.getQuantityString(R.plurals.search_results, adapter.pages.size, adapter.pages.size)
        }
    }

    // --- selection ---------------------------------------------------------------------

    private fun setSelecting(on: Boolean) {
        selecting = on
        exitSelection.isEnabled = on
        if (!on) selected.clear()
        binding.toolbar.collapseActionView()
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(if (on) R.menu.document_selection else R.menu.document)
        if (!on) {
            applyViewMode()
            setUpSearch()
        }
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

    /** Updates the ticks on visible pages without rebinding them, so a held page isn't interrupted. */
    private fun refreshChecks() {
        for (i in 0 until binding.grid.childCount) {
            val holder = binding.grid.getChildViewHolder(binding.grid.getChildAt(i)) as? PageAdapter.Holder ?: continue
            val page = adapter.pages.getOrNull(holder.bindingAdapterPosition) ?: continue
            (holder.b.root as MaterialCardView).isChecked = selecting && page.id in selected
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
        if (selecting) {
            val any = selected.isNotEmpty()
            listOf(R.id.action_share, R.id.action_delete_selected, R.id.action_recognize_selected).forEach {
                binding.toolbar.menu.findItem(it)?.isEnabled = any
            }
        }
    }

    /** Selected pages (in document order) while selecting, otherwise every page. */
    private fun currentPagesForAction(): List<PageEntity> =
        if (selecting) adapter.allPages.filter { it.id in selected } else adapter.allPages.toList()

    private fun pageNumber(page: PageEntity) = adapter.allPages.indexOfFirst { it.id == page.id } + 1

    private fun confirmDeleteSelected() {
        val pages = currentPagesForAction()
        if (pages.isEmpty()) return
        val all = pages.size == adapter.allPages.size
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

    private fun shareImages(pages: List<PageEntity>) {
        if (pages.isEmpty()) return
        val numbers = pages.map(::pageNumber)
        export(R.string.export_images_failed) { Sharing.images(this, pages, numbers, title) }
    }

    private fun shareZip(pages: List<PageEntity>) {
        if (pages.isEmpty()) return
        val numbers = pages.map(::pageNumber)
        export(R.string.export_images_failed) { Sharing.zip(this, pages, numbers, title) }
    }

    private fun shareText(pages: List<PageEntity>, markdown: Boolean) =
        withRecognisedText(pages) { now -> Sharing.text(this, now, title, markdown) }

    private fun shareWord(pages: List<PageEntity>) =
        withRecognisedText(pages) { now -> Sharing.word(this, now, title) }

    /** Text exports need recognised text: offers to recognise it first if none of [pages] has any. */
    private fun withRecognisedText(pages: List<PageEntity>, build: (List<PageEntity>) -> Intent) {
        if (pages.isEmpty()) return
        val share = { export(R.string.export_failed) { build(pagesNow(pages)) } }
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

    // --- pages ---------------------------------------------------------------------------

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        /** Every page of the document, in order. */
        val allPages = mutableListOf<PageEntity>()
        /** The pages shown: all of them, or the search matches. */
        val pages = mutableListOf<PageEntity>()
        private val aspects = HashMap<String, Float>()

        inner class Holder(val b: ViewBinding, val image: HighlightImageView, val number: android.widget.TextView) :
            RecyclerView.ViewHolder(b.root)

        fun submit(list: List<PageEntity>) {
            allPages.clear()
            allPages.addAll(list)
            selected.retainAll(list.map { it.id }.toSet())
            refilter()
            if (selecting) updateSelectionTitle()
        }

        fun refilter() {
            pages.clear()
            pages.addAll(if (query.isEmpty()) allPages else allPages.filter { matches(it).isNotEmpty() })
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        fun matches(page: PageEntity): List<RectF> {
            if (query.isEmpty()) return emptyList()
            val text = PageTextCodec.decode(page.ocr) ?: return emptyList()
            return text.linesMatching(query).map { RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        }

        fun move(from: Int, to: Int) {
            Collections.swap(pages, from, to)
            Collections.swap(allPages, from, to)
            notifyItemMoved(from, to)
        }

        override fun getItemCount() = pages.size

        override fun getItemViewType(position: Int) = if (readerMode) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) {
                val b = ItemPageReaderBinding.inflate(inflater, parent, false)
                Holder(b, b.image, b.number)
            } else {
                val b = ItemPageBinding.inflate(inflater, parent, false)
                Holder(b, b.image, b.number)
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages[position]
            val label = getString(R.string.page_number, pageNumber(page))
            holder.number.text = if (page.ocr != null) getString(R.string.text_page_label, label) else label
            (holder.b.root as MaterialCardView).isChecked = selecting && page.id in selected
            holder.image.highlights = matches(page)
            if (readerMode) holder.image.aspect = aspectOf(page.processedPath)
            Thumbnails.load(holder.image, page.processedPath, if (readerMode) 1080 else 512, lifecycleScope)
            holder.b.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (selecting) toggle(pages[pos])
                else startActivity(PageViewerActivity.intent(this@DocumentActivity, documentId, pages[pos].id, query))
            }
            // The reader view has no drag-to-reorder, so holding there just selects.
            holder.b.root.setOnLongClickListener {
                if (!readerMode && query.isEmpty()) return@setOnLongClickListener false
                holder.b.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                selectByHold(holder.bindingAdapterPosition)
                true
            }
        }

        /** Page height / width from the image header, cached; A4 until known. */
        private fun aspectOf(path: String): Float = aspects.getOrPut(path) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth > 0) o.outHeight.toFloat() / o.outWidth else 1.414f
        }
    }

    /**
     * In the grid: long-press selects the page; long-press and drag reorders. The new order is
     * saved when the page is dropped. Off in the reader view and while searching.
     */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0,
    ) {
        override fun isLongPressDragEnabled() = !readerMode && query.isEmpty()

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
            val order = adapter.allPages.map { it.id }
            lifecycleScope.launch {
                repository.reorder(documentId, order)
                dragging = false
                adapter.submit(repository.getPages(documentId))
            }
        }
    }

    companion object {
        private const val EXTRA_DOCUMENT_ID = "document_id"
        private const val PREF_READER = "reader_view"

        fun intent(context: Context, documentId: Long) =
            Intent(context, DocumentActivity::class.java).putExtra(EXTRA_DOCUMENT_ID, documentId)
    }
}
