package io.github.ogshrug.flatdocscanner.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.ogshrug.flatdocscanner.AppSettings
import io.github.ogshrug.flatdocscanner.R
import io.github.ogshrug.flatdocscanner.data.DocumentSummary
import io.github.ogshrug.flatdocscanner.data.PageEntity
import io.github.ogshrug.flatdocscanner.databinding.ActivityMainBinding
import io.github.ogshrug.flatdocscanner.databinding.ItemFolderBinding
import io.github.ogshrug.flatdocscanner.databinding.ItemNewFolderBinding
import io.github.ogshrug.flatdocscanner.export.PdfOptions
import io.github.ogshrug.flatdocscanner.repository
import io.github.ogshrug.flatdocscanner.settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library (S01): folders as a grid of covers. Scan, import photos, make a PDF or ZIP of the
 * selected folders, or move them to the Trash. Hold a folder to start selecting.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = FolderAdapter()
    private var all: List<DocumentSummary> = emptyList()
    private var query = ""

    private var selecting = false
    private val selected = linkedSetOf<Long>()
    private val exitSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSelecting(false)
    }

    private val pickImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) importImages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, exitSelection)
        ViewCompat.setOnApplyWindowInsetsListener(binding.actionBar) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }

        binding.list.layoutManager = GridLayoutManager(this, if (resources.configuration.screenWidthDp >= 600) 5 else 3)
        binding.list.adapter = adapter
        binding.toolbar.setOnMenuItemClickListener(::onMenuItem)
        binding.toolbar.setNavigationOnClickListener { setSelecting(false) }
        setUpSearch()

        binding.scanButton.setOnClickListener { startActivity(CameraActivity.intent(this, documentId = null)) }
        binding.actionImport.setOnClickListener {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.actionPdf.setOnClickListener { withSelection { createPdf(it) } }
        binding.actionZip.setOnClickListener { withSelection { createZip(it) } }
        binding.actionDelete.setOnClickListener { withSelection { moveToTrash(it) } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeDocuments().collect {
                    all = it
                    selected.retainAll(it.map { d -> d.id }.toSet())
                    refresh()
                }
            }
        }

        if (savedInstanceState == null && settings.startInCamera) {
            startActivity(CameraActivity.intent(this, documentId = null))
        }
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_sort -> chooseSort()
            R.id.action_trash -> startActivity(Intent(this, TrashActivity::class.java))
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_select_all -> {
                if (selected.size == all.size) selected.clear() else all.forEach { selected += it.id }
                updateSelection()
            }
        }
        return true
    }

    // --- list --------------------------------------------------------------------------------

    private fun refresh() {
        val sorted = when (settings.librarySort) {
            AppSettings.LibrarySort.MODIFIED -> all.sortedByDescending { it.updatedAt }
            AppSettings.LibrarySort.CREATED -> all.sortedByDescending { it.createdAt }
            AppSettings.LibrarySort.NAME -> all.sortedBy { it.title.lowercase() }
        }
        val shown = if (query.isEmpty()) sorted else sorted.filter { it.title.contains(query, ignoreCase = true) }
        adapter.submit(shown, showNewFolder = query.isEmpty() && !selecting)
        binding.empty.isVisible = all.isEmpty()
        if (selecting) updateSelection() else adapter.refreshChecks()
    }

    private fun setUpSearch() {
        val item = binding.toolbar.menu.findItem(R.id.action_search) ?: return
        val view = item.actionView as SearchView
        view.queryHint = getString(R.string.search_folders)
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String) = true.also { view.clearFocus() }
            override fun onQueryTextChange(q: String): Boolean {
                query = q.trim()
                refresh()
                return true
            }
        })
    }

    private fun chooseSort() {
        val options = AppSettings.LibrarySort.entries
        val labels = arrayOf(getString(R.string.sort_modified), getString(R.string.sort_created), getString(R.string.sort_name))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_sort)
            .setSingleChoiceItems(labels, options.indexOf(settings.librarySort)) { d, which ->
                settings.librarySort = options[which]
                refresh()
                d.dismiss()
            }
            .show()
    }

    private fun newFolder() {
        promptText(R.string.new_folder_title, repository.defaultTitle(), R.string.action_create) { name ->
            lifecycleScope.launch {
                val id = repository.createDocument(name)
                startActivity(DocumentActivity.intent(this@MainActivity, id))
            }
        }
    }

    // --- selection ---------------------------------------------------------------------

    private fun setSelecting(on: Boolean) {
        selecting = on
        exitSelection.isEnabled = on
        if (!on) selected.clear()
        binding.toolbar.collapseActionView()
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(if (on) R.menu.library_selection else R.menu.library)
        if (!on) setUpSearch()
        binding.toolbar.navigationIcon = if (on) androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_close) else null
        binding.toolbar.navigationContentDescription = if (on) getString(R.string.action_cancel_selection) else null
        binding.scanButton.isVisible = !on
        binding.actionImport.isEnabled = !on
        if (!on) binding.toolbar.setTitle(R.string.library_title)
        refresh()
    }

    private fun updateSelection() {
        binding.toolbar.title = if (selected.isEmpty()) getString(R.string.select_pages_hint)
        else resources.getQuantityString(R.plurals.selected_count, selected.size, selected.size)
        binding.toolbar.menu.findItem(R.id.action_select_all)
            ?.setTitle(if (selected.size == all.size && all.isNotEmpty()) R.string.action_deselect_all else R.string.action_select_all)
        adapter.refreshChecks()
    }

    /** Runs [action] on the selected folders, or explains how to select some. */
    private fun withSelection(action: (List<DocumentSummary>) -> Unit) {
        val docs = all.filter { it.id in selected }
        if (!selecting || docs.isEmpty()) {
            if (!selecting) setSelecting(true)
            Toast.makeText(this, R.string.select_folders_first, Toast.LENGTH_LONG).show()
            return
        }
        action(docs)
    }

    // --- actions -----------------------------------------------------------------------

    private suspend fun pagesOf(docs: List<DocumentSummary>): List<Pair<DocumentSummary, PageEntity>> =
        docs.flatMap { d -> repository.getPages(d.id).map { d to it } }

    private fun createPdf(docs: List<DocumentSummary>) {
        val name = if (docs.size == 1) docs[0].title else getString(R.string.app_name)
        PdfSheet.show(this, name) { fileName, options: PdfOptions ->
            export { Sharing.pdf(this, pagesOf(docs).map { it.second }, fileName, options) }
        }
    }

    private fun createZip(docs: List<DocumentSummary>) {
        val name = if (docs.size == 1) docs[0].title else getString(R.string.app_name)
        export {
            val pages = pagesOf(docs)
            val named = pages.groupBy { it.first.id }.values.flatMap { list ->
                list.mapIndexed { i, (d, p) -> "${d.title} - page ${i + 1}" to p }
            }
            Sharing.zipNamed(this, named, name)
        }
    }

    /**
     * Nothing is lost by this (the Trash keeps folders for 30 days), so it happens straight away
     * and offers Undo, rather than stopping to ask first.
     */
    private fun moveToTrash(docs: List<DocumentSummary>) {
        lifecycleScope.launch {
            docs.forEach { repository.deleteDocument(it.id) }
            setSelecting(false)
            showUndo(
                binding.root, binding.scanButton,
                resources.getQuantityString(R.plurals.folders_moved_to_trash, docs.size, docs.size),
            ) { docs.forEach { repository.restoreDocument(it.id) } }
        }
    }

    override fun onResume() {
        super.onResume()
        // A folder that was deleted from its own screen offers its Undo here.
        PendingUndo.take()?.let { showUndo(binding.root, binding.scanButton, it.message, it.undo) }
    }

    /** Hands picked photos to [ImportActivity], which scans them into a new folder. */
    private fun importImages(uris: List<Uri>) = startActivity(ImportActivity.intent(this, uris, documentId = null))

    private fun export(build: suspend () -> Intent) {
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val chooser = runCatching { withContext(Dispatchers.IO) { build() } }.getOrNull()
            binding.progress.isVisible = false
            if (chooser == null) {
                Toast.makeText(this@MainActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
            } else {
                setSelecting(false)
                startActivity(chooser)
            }
        }
    }

    // --- grid ----------------------------------------------------------------------------

    private inner class FolderAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<DocumentSummary> = emptyList()
        private var newFolderTile = true

        inner class FolderHolder(val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root)
        inner class NewHolder(val b: ItemNewFolderBinding) : RecyclerView.ViewHolder(b.root)

        /**
         * Only what changed is rebound, and folders slide into their new places: a scan landing
         * in the library or a re-sort no longer redraws (and flickers) every cover.
         */
        fun submit(list: List<DocumentSummary>, showNewFolder: Boolean) {
            val old = items
            val oldOffset = offset()
            val newOffset = if (showNewFolder) 1 else 0
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size + oldOffset
                override fun getNewListSize() = list.size + newOffset
                override fun areItemsTheSame(o: Int, n: Int): Boolean {
                    val a = old.getOrNull(o - oldOffset)
                    val b = list.getOrNull(n - newOffset)
                    return if (a == null || b == null) a == null && b == null else a.id == b.id
                }
                override fun areContentsTheSame(o: Int, n: Int) =
                    old.getOrNull(o - oldOffset) == list.getOrNull(n - newOffset)
                // Rebind in place instead of cross-fading two copies of the tile.
                override fun getChangePayload(o: Int, n: Int): Any = Unit
            })
            items = list
            newFolderTile = showNewFolder
            diff.dispatchUpdatesTo(this)
        }

        private fun offset() = if (newFolderTile) 1 else 0

        override fun getItemCount() = items.size + offset()

        override fun getItemViewType(position: Int) = if (newFolderTile && position == 0) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) {
                NewHolder(ItemNewFolderBinding.inflate(inflater, parent, false)).also { h ->
                    h.b.box.layoutParams.height = 0
                    h.b.box.post { h.b.box.layoutParams.height = (h.b.box.width * COVER_ASPECT).toInt(); h.b.box.requestLayout() }
                    h.b.root.setOnClickListener { newFolder() }
                }
            } else {
                FolderHolder(ItemFolderBinding.inflate(inflater, parent, false)).also {
                    it.b.cover.aspect = COVER_ASPECT
                    it.b.cover.clipToOutline = true
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is FolderHolder) return
            val doc = items[position - offset()]
            val b = holder.b
            b.title.text = doc.title
            b.count.text = java.text.NumberFormat.getIntegerInstance().format(doc.pageCount)
            b.emptyIcon.isVisible = doc.coverPath == null
            Thumbnails.load(b.cover, doc.coverPath, 360, lifecycleScope)
            bindCheck(b, doc)
            b.root.setOnClickListener {
                if (selecting) {
                    if (!selected.add(doc.id)) selected.remove(doc.id)
                    updateSelection()
                } else {
                    startActivity(DocumentActivity.intent(this@MainActivity, doc.id))
                }
            }
            b.root.setOnLongClickListener {
                b.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!selecting) setSelecting(true)
                selected += doc.id
                updateSelection()
                true
            }
        }

        private fun bindCheck(b: ItemFolderBinding, doc: DocumentSummary) {
            b.check.isVisible = selecting
            val on = doc.id in selected
            b.check.setImageResource(if (on) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
            b.check.imageTintList = if (on) getColorStateList(R.color.accent) else null
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (holder is FolderHolder && payloads.contains(CHECK_ONLY)) {
                items.getOrNull(position - offset())?.let { bindCheck(holder.b, it) }
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        /** Updates the ticks without rebinding the covers. */
        fun refreshChecks() = notifyItemRangeChanged(0, itemCount, CHECK_ONLY)
    }

    private companion object {
        /** Folder covers are shown at 3:4. */
        const val COVER_ASPECT = 4f / 3f

        /** Bind payload: only the selection tick changed. */
        val CHECK_ONLY = Any()
    }
}
