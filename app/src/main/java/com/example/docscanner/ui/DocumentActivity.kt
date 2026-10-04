package com.example.docscanner.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
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
import com.example.docscanner.export.GalleryExporter
import com.example.docscanner.ocr.PageTextCodec
import com.example.docscanner.repository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * A folder's pages (S03), as a grid or a continuous reader. The bottom bar imports, reads the
 * text, shares, makes a PDF, and opens More (move, save to gallery, cover, crop, rotate, delete).
 * Actions apply to the selected pages, or to all pages when nothing is selected. Hold a page to
 * select it; hold and drag to reorder. Search marks matches on the pages.
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

    private val pickImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) startActivity(ImportActivity.intent(this, uris, documentId))
    }

    /** Android 7-9 need storage permission to save to the gallery. */
    private var pendingGallery: List<PageEntity> = emptyList()
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveToGallery(pendingGallery)
        else Toast.makeText(this, R.string.gallery_permission_needed, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        binding = ActivityDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, exitSelection)
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottom) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { if (selecting) setSelecting(false) else finish() }
        binding.toolbar.setOnMenuItemClickListener(::onMenuItem)
        binding.addPageButton.setOnClickListener { startActivity(CameraActivity.intent(this, documentId)) }
        binding.actionImport.setOnClickListener {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.actionText.setOnClickListener { openText() }
        binding.actionShare.setOnClickListener { showShareSheet(pagesForAction()) }
        binding.actionPdf.setOnClickListener { createPdf(pagesForAction()) }
        binding.actionMore.setOnClickListener { showMoreSheet() }

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
                    repository.observePages(documentId).collect {
                        if (!dragging) adapter.submit(it)
                        binding.empty.isVisible = it.isEmpty()
                    }
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
            R.id.action_select -> setSelecting(true)
            R.id.action_select_all -> {
                if (selected.size == adapter.pages.size) selected.clear() else adapter.pages.forEach { selected += it.id }
                updateSelection()
            }
            R.id.action_reverse -> lifecycleScope.launch { repository.reversePages(documentId) }
            R.id.action_change_cover -> {
                setSelecting(true)
                Toast.makeText(this, R.string.change_cover_hint, Toast.LENGTH_LONG).show()
            }
            R.id.action_recognize_all -> TextUi.recognizePages(this, adapter.allPages.toList())
            R.id.action_remove_text_all -> TextUi.confirmRemoveAll(this, documentId)
            R.id.action_text_language -> TextUi.chooseScript(this)
            R.id.action_rename -> promptText(R.string.action_rename_folder, title, R.string.action_rename) { name ->
                lifecycleScope.launch { repository.renameDocument(documentId, name) }
            }
            R.id.action_delete -> confirmDeleteFolder()
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
        }
        return true
    }

    // --- view mode and search ------------------------------------------------------------

    private fun applyViewMode() {
        val span = if (resources.configuration.screenWidthDp >= 600) 5 else 3
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
        binding.actionImport.isEnabled = !on
        updateSelection()
    }

    private fun updateSelection() {
        binding.toolbar.title = if (selecting) {
            if (selected.isEmpty()) getString(R.string.select_pages_hint)
            else resources.getQuantityString(R.plurals.selected_count, selected.size, selected.size)
        } else title
        binding.toolbar.menu.findItem(R.id.action_select_all)?.setTitle(
            if (selecting && selected.size == adapter.pages.size && adapter.pages.isNotEmpty()) R.string.action_deselect_all
            else R.string.action_select_all,
        )
        adapter.refreshChecks()
    }

    private fun toggle(page: PageEntity) {
        if (!selected.add(page.id)) selected.remove(page.id)
        updateSelection()
    }

    /** Holding a page selects it (and starts selection mode); holding and dragging moves it. */
    private fun selectByHold(position: Int) {
        val page = adapter.pages.getOrNull(position) ?: return
        if (!selecting) setSelecting(true)
        selected += page.id
        updateSelection()
    }

    /** Selected pages (in document order) while selecting, otherwise every page. */
    private fun pagesForAction(): List<PageEntity> =
        if (selecting && selected.isNotEmpty()) adapter.allPages.filter { it.id in selected } else adapter.allPages.toList()

    /** Pages for actions that need an explicit selection (move, rotate, delete...). */
    private fun selectedOrHint(): List<PageEntity>? {
        val pages = adapter.allPages.filter { it.id in selected }
        if (selecting && pages.isNotEmpty()) return pages
        if (!selecting) setSelecting(true)
        Toast.makeText(this, R.string.select_pages_first, Toast.LENGTH_LONG).show()
        return null
    }

    private fun pageNumber(page: PageEntity) = adapter.allPages.indexOfFirst { it.id == page.id } + 1

    // --- bottom bar ----------------------------------------------------------------------

    private fun openText() {
        val first = pagesForAction().firstOrNull() ?: return
        startActivity(TextReaderActivity.intent(this, documentId, first.id))
    }

    private fun createPdf(pages: List<PageEntity>) {
        if (pages.isEmpty()) return
        PdfSheet.show(this, title) { name, options -> export(R.string.export_failed) { Sharing.pdf(this, pages, name, options) } }
    }

    private fun showShareSheet(pages: List<PageEntity>) {
        if (pages.isEmpty()) return
        val numbers = pages.map(::pageNumber)
        ActionSheet.show(this, R.string.action_share, listOf(
            ActionSheet.Action(R.drawable.ic_photo, R.string.share_as_images) {
                export(R.string.export_images_failed) { Sharing.images(this, pages, numbers, title) }
            },
            ActionSheet.Action(R.drawable.ic_pdf, R.string.share_as_pdf) {
                export(R.string.export_failed) { Sharing.pdf(this, pages, title) }
            },
            ActionSheet.Action(R.drawable.ic_zip, R.string.create_zip) {
                export(R.string.export_images_failed) { Sharing.zip(this, pages, numbers, title) }
            },
            ActionSheet.Action(R.drawable.ic_txt, R.string.action_create_txt) {
                withRecognisedText(pages) { Sharing.text(this, it, title, markdown = false) }
            },
            ActionSheet.Action(R.drawable.ic_text, R.string.share_as_markdown) {
                withRecognisedText(pages) { Sharing.text(this, it, title, markdown = true) }
            },
            ActionSheet.Action(R.drawable.ic_txt, R.string.share_as_word) {
                withRecognisedText(pages) { Sharing.word(this, it, title) }
            },
            ActionSheet.Action(R.drawable.ic_save_gallery, R.string.action_save_to_gallery) { saveToGallery(pages) },
        ))
    }

    private fun showMoreSheet() {
        val one = selecting && selected.size == 1
        ActionSheet.show(this, R.string.action_more, listOf(
            ActionSheet.Action(R.drawable.ic_text, R.string.action_recognize_selected) {
                TextUi.recognizePages(this, pagesForAction())
            },
            ActionSheet.Action(R.drawable.ic_move, R.string.action_move) { selectedOrHint()?.let(::moveTo) },
            ActionSheet.Action(R.drawable.ic_save_gallery, R.string.action_save_to_gallery) { saveToGallery(pagesForAction()) },
            ActionSheet.Action(R.drawable.ic_cover, R.string.action_set_cover, enabled = one) {
                val page = adapter.allPages.first { it.id in selected }
                lifecycleScope.launch { repository.setCover(documentId, page.id) }
                Toast.makeText(this, R.string.cover_set, Toast.LENGTH_SHORT).show()
                setSelecting(false)
            },
            ActionSheet.Action(R.drawable.ic_txt, R.string.action_create_txt) {
                withRecognisedText(pagesForAction()) { Sharing.text(this, it, title, markdown = false) }
            },
            ActionSheet.Action(R.drawable.ic_zip, R.string.create_zip) {
                val pages = pagesForAction()
                export(R.string.export_images_failed) { Sharing.zip(this, pages, pages.map(::pageNumber), title) }
            },
            ActionSheet.Action(R.drawable.ic_crop, R.string.action_crop, enabled = one || adapter.allPages.size == 1) {
                val page = if (one) adapter.allPages.first { it.id in selected } else adapter.allPages.first()
                startActivity(CropActivity.editPageIntent(this, page.id))
            },
            ActionSheet.Action(R.drawable.ic_rotate_left, R.string.action_rotate_left) { rotate(-90) },
            ActionSheet.Action(R.drawable.ic_rotate_right, R.string.action_rotate_right) { rotate(90) },
            ActionSheet.Action(R.drawable.ic_delete, R.string.action_delete, destructive = true) {
                selectedOrHint()?.let(::confirmTrash)
            },
        ))
    }

    private fun rotate(degrees: Int) {
        val pages = selectedOrHint() ?: return
        binding.progress.isVisible = true
        lifecycleScope.launch {
            pages.forEach { runCatching { repository.rotatePage(it, degrees) } }
            binding.progress.isVisible = false
        }
    }

    private fun moveTo(pages: List<PageEntity>) {
        lifecycleScope.launch {
            val others = repository.getDocuments().filter { it.id != documentId }
            val labels = arrayOf(getString(R.string.move_to_new_folder)) + others.map { it.title }
            MaterialAlertDialogBuilder(this@DocumentActivity)
                .setTitle(R.string.move_to_title)
                .setItems(labels) { _, which ->
                    if (which == 0) {
                        promptText(R.string.new_folder_title, repository.defaultTitle(), R.string.action_create) { name ->
                            lifecycleScope.launch { move(pages, repository.createDocument(name)) }
                        }
                    } else {
                        lifecycleScope.launch { move(pages, others[which - 1].id) }
                    }
                }
                .show()
        }
    }

    private suspend fun move(pages: List<PageEntity>, target: Long) {
        repository.movePages(pages, target)
        setSelecting(false)
        Toast.makeText(this, resources.getQuantityString(R.plurals.moved_pages, pages.size, pages.size), Toast.LENGTH_SHORT).show()
    }

    private fun saveToGallery(pages: List<PageEntity>) {
        if (pages.isEmpty()) return
        if (GalleryExporter.needsPermission() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingGallery = pages
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val stamp = System.currentTimeMillis()
            val n = withContext(Dispatchers.IO) {
                GalleryExporter.save(this@DocumentActivity, pages.map { "${title}_${pageNumber(it)}_$stamp.jpg" to java.io.File(it.processedPath) })
            }
            binding.progress.isVisible = false
            if (selecting) setSelecting(false)
            Toast.makeText(this@DocumentActivity, resources.getQuantityString(R.plurals.saved_to_gallery, n, n), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmTrash(pages: List<PageEntity>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(resources.getQuantityString(R.plurals.move_pages_to_trash, pages.size, pages.size))
            .setMessage(R.string.trash_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_move_to_trash) { _, _ ->
                lifecycleScope.launch {
                    setSelecting(false)
                    pages.forEach { repository.deletePage(it) }
                }
            }
            .show()
    }

    private fun confirmDeleteFolder() {
        MaterialAlertDialogBuilder(this)
            .setTitle(resources.getQuantityString(R.plurals.move_folders_to_trash, 1, 1))
            .setMessage(R.string.trash_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_move_to_trash) { _, _ ->
                lifecycleScope.launch {
                    repository.deleteDocument(documentId)
                    finish()
                }
            }
            .show()
    }

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
        /** Every page of the folder, in order. */
        val allPages = mutableListOf<PageEntity>()
        /** The pages shown: all of them, or the search matches. */
        val pages = mutableListOf<PageEntity>()
        private val aspects = HashMap<String, Float>()

        inner class Holder(val b: ViewBinding, val image: HighlightImageView, val number: TextView, val check: ImageView) :
            RecyclerView.ViewHolder(b.root)

        fun submit(list: List<PageEntity>) {
            allPages.clear()
            allPages.addAll(list)
            selected.retainAll(list.map { it.id }.toSet())
            refilter()
            if (selecting) updateSelection()
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

        /** Updates the selection circles on visible pages without rebinding them (a held page keeps its drag). */
        fun refreshChecks() {
            for (i in 0 until binding.grid.childCount) {
                val holder = binding.grid.getChildViewHolder(binding.grid.getChildAt(i)) as? Holder ?: continue
                pages.getOrNull(holder.bindingAdapterPosition)?.let { bindCheck(holder, it) }
            }
        }

        private fun bindCheck(holder: Holder, page: PageEntity) {
            holder.check.isVisible = selecting
            val on = page.id in selected
            holder.check.setImageResource(if (on) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
            holder.check.imageTintList = if (on) getColorStateList(R.color.accent) else null
            (holder.b.root as com.google.android.material.card.MaterialCardView).isChecked = selecting && on
        }

        override fun getItemCount() = pages.size

        override fun getItemViewType(position: Int) = if (readerMode) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) {
                val b = ItemPageReaderBinding.inflate(inflater, parent, false)
                Holder(b, b.image, b.number, b.check)
            } else {
                val b = ItemPageBinding.inflate(inflater, parent, false)
                b.image.aspect = 1.414f
                Holder(b, b.image, b.number, b.check)
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages[position]
            val label = pageNumber(page).toString()
            holder.number.text = if (page.ocr != null) getString(R.string.text_page_label, label) else label
            bindCheck(holder, page)
            holder.image.highlights = matches(page)
            if (readerMode) holder.image.aspect = aspectOf(page.processedPath)
            Thumbnails.load(holder.image, page.processedPath, if (readerMode) 1080 else 400, lifecycleScope)
            holder.b.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (selecting) toggle(pages[pos])
                else startActivity(PageViewerActivity.intent(this@DocumentActivity, documentId, pages[pos].id, query))
            }
            // The reader view and search results have no drag-to-reorder, so holding there just selects.
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
