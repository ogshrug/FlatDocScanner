package io.github.ogshrug.flatdocscanner.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.util.LruCache
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
import io.github.ogshrug.flatdocscanner.databinding.ActivityPageViewerBinding
import io.github.ogshrug.flatdocscanner.databinding.ItemPageViewerBinding
import io.github.ogshrug.flatdocscanner.ocr.PageTextCodec
import io.github.ogshrug.flatdocscanner.repository
import io.github.ogshrug.flatdocscanner.util.Bitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen page viewer: swipe for the next or previous page, pinch or double-tap to zoom,
 * tap to show or hide the top bar. Edit, share and delete act on the page being shown.
 */
class PageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPageViewerBinding
    private val documentId by lazy { intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L) }
    private val query by lazy { intent.getStringExtra(EXTRA_QUERY).orEmpty() }
    private var title = ""
    private val adapter = ViewerAdapter()
    private var positioned = false

    /** A few decoded pages, so swiping back and forth doesn't decode again. */
    private val cache = LruCache<String, Bitmap>(5)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always the dark colours: this screen is a black stage for the picture.
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        super.onCreate(savedInstanceState)
        enableAppEdgeToEdge()
        binding = ActivityPageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.padForSystemBars(top = true)
        binding.bottom.padForSystemBars(bottom = true)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.actionCrop.setOnClickListener { currentPage()?.let { startActivity(CropActivity.editPageIntent(this, it.id)) } }
        binding.actionShare.setOnClickListener { currentPage()?.let(::showShareSheet) }
        binding.actionPdf.setOnClickListener {
            currentPage()?.let { page ->
                PdfSheet.show(this, title) { name, options ->
                    export { Sharing.pdf(this, listOf(page), name, options) }
                }
            }
        }
        binding.actionRotate.setOnClickListener { currentPage()?.let { rotate(it, 90) } }
        binding.actionMore.setOnClickListener { currentPage()?.let(::showMoreSheet) }

        // Image | Text: Text shows this page's recognised text; coming back returns to the image.
        binding.modeSwitch.group.check(R.id.modeImage)
        binding.modeSwitch.group.addOnButtonCheckedListener { group, id, checked ->
            if (checked && id == R.id.modeText) {
                currentPage()?.let { page ->
                    textView.launch(TextReaderActivity.intent(this, documentId, page.id))
                    overridePendingTransition(0, 0)
                }
                group.check(R.id.modeImage)
            }
        }

        binding.pager.adapter = adapter
        binding.pager.offscreenPageLimit = 1
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTitle()
                resetZoomExcept(position)
            }
        })

        val startPageId = savedInstanceState?.getLong(EXTRA_PAGE_ID) ?: intent.getLongExtra(EXTRA_PAGE_ID, -1L)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observeDocument(documentId).collect { doc ->
                        if (doc == null) finish() else title = doc.title
                    }
                }
                launch {
                    repository.observePages(documentId).collect { pages ->
                        if (pages.isEmpty()) {
                            finish()
                            return@collect
                        }
                        // Stay on the same page after an edit or a deletion elsewhere in the list.
                        val keepId = if (positioned) currentPage()?.id else startPageId
                        val keepIndex = binding.pager.currentItem
                        adapter.submit(pages)
                        val target = pages.indexOfFirst { it.id == keepId }
                            .takeIf { it >= 0 } ?: keepIndex.coerceAtMost(pages.size - 1)
                        binding.pager.setCurrentItem(target, false)
                        positioned = true
                        updateTitle()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPage()?.let { outState.putLong(EXTRA_PAGE_ID, it.id) }
    }

    private fun currentPage(): PageEntity? = adapter.pages.getOrNull(binding.pager.currentItem)

    private fun updateTitle() {
        val n = adapter.pages.size
        binding.toolbar.title = title
        if (n > 0) binding.toolbar.subtitle = getString(R.string.viewer_subtitle, binding.pager.currentItem + 1, n)
    }

    /** The text view reports the page it ended on, so the image view follows. */
    private val textView = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val id = r.data?.getLongExtra(TextReaderActivity.RESULT_PAGE_ID, -1L) ?: -1L
        val index = adapter.pages.indexOfFirst { it.id == id }
        if (index >= 0) binding.pager.setCurrentItem(index, false)
    }

    private fun rotate(page: PageEntity, degrees: Int) {
        binding.progress.isVisible = true
        lifecycleScope.launch {
            runCatching { repository.rotatePage(page, degrees) }
                .onFailure { Toast.makeText(this@PageViewerActivity, R.string.save_failed, Toast.LENGTH_LONG).show() }
            binding.progress.isVisible = false
        }
    }

    private fun showShareSheet(page: PageEntity) {
        val number = adapter.pages.indexOfFirst { it.id == page.id } + 1
        ActionSheet.show(this, R.string.action_share, listOf(
            ActionSheet.Action(R.drawable.ic_photo, R.string.share_as_images) { share(page) },
            ActionSheet.Action(R.drawable.ic_pdf, R.string.share_as_pdf) { export { Sharing.pdf(this, listOf(page), "${title}_$number") } },
            ActionSheet.Action(R.drawable.ic_save_gallery, R.string.action_save_to_gallery) { saveToGallery(page, number) },
        ))
    }

    private fun showMoreSheet(page: PageEntity) {
        ActionSheet.show(this, R.string.action_more, listOf(
            ActionSheet.Action(R.drawable.ic_text, R.string.action_text_view) {
                textView.launch(TextReaderActivity.intent(this, documentId, page.id))
            },
            ActionSheet.Action(R.drawable.ic_rotate_left, R.string.action_rotate_left) { rotate(page, -90) },
            ActionSheet.Action(R.drawable.ic_rotate_right, R.string.action_rotate_right) { rotate(page, 90) },
            ActionSheet.Action(R.drawable.ic_cover, R.string.action_set_cover) {
                lifecycleScope.launch { repository.setCover(documentId, page.id) }
                Toast.makeText(this, R.string.cover_set, Toast.LENGTH_SHORT).show()
            },
            ActionSheet.Action(R.drawable.ic_delete, R.string.action_delete, destructive = true) { moveToTrash(page) },
        ))
    }

    private fun saveToGallery(page: PageEntity, number: Int) {
        if (io.github.ogshrug.flatdocscanner.export.GalleryExporter.needsPermission() &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.gallery_permission_needed, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                io.github.ogshrug.flatdocscanner.export.GalleryExporter.save(
                    this@PageViewerActivity, listOf("${title}_${number}_${System.currentTimeMillis()}.jpg" to File(page.processedPath)),
                )
            }
            Toast.makeText(this@PageViewerActivity, resources.getQuantityString(R.plurals.saved_to_gallery, n, n), Toast.LENGTH_LONG).show()
        }
    }

    private fun export(build: suspend () -> Intent) {
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val chooser = runCatching { withContext(Dispatchers.IO) { build() } }.getOrNull()
            binding.progress.isVisible = false
            if (chooser == null) Toast.makeText(this@PageViewerActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
            else startActivity(chooser)
        }
    }

    /** Pages left zoomed in come back at normal size. */
    private fun resetZoomExcept(position: Int) {
        val rv = binding.pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? ViewerAdapter.Holder ?: continue
            if (holder.bindingAdapterPosition != position) holder.b.image.resetZoom()
        }
    }

    private fun toggleBars() {
        val show = !binding.toolbar.isVisible
        for (bar in listOf(binding.toolbar, binding.bottom)) {
            bar.animate().cancel()
            if (show) {
                bar.alpha = 0f
                bar.isVisible = true
                bar.animate().alpha(1f).setDuration(150).start()
            } else {
                bar.animate().alpha(0f).setDuration(150).withEndAction { bar.isVisible = false }.start()
            }
        }
    }

    private fun share(page: PageEntity) {
        val number = adapter.pages.indexOfFirst { it.id == page.id } + 1
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val chooser = runCatching {
                withContext(Dispatchers.IO) { Sharing.images(this@PageViewerActivity, listOf(page), listOf(number), title) }
            }.getOrNull()
            binding.progress.isVisible = false
            if (chooser == null) {
                Toast.makeText(this@PageViewerActivity, R.string.export_images_failed, Toast.LENGTH_LONG).show()
            } else {
                startActivity(chooser)
            }
        }
    }

    /**
     * Moves the page to the Trash straight away and offers Undo, which puts it back where it
     * was. Deleting the only page closes the viewer, so the folder screen shows that Undo.
     */
    private fun moveToTrash(page: PageEntity) {
        val orderBefore = adapter.pages.map { it.id }
        val message = resources.getQuantityString(R.plurals.pages_moved_to_trash, 1, 1)
        val undo: suspend () -> Unit = { repository.undoDeletePages(documentId, listOf(page), orderBefore) }
        if (orderBefore.size == 1) PendingUndo.leave(PendingUndo.Offer(message, undo))
        lifecycleScope.launch {
            repository.deletePages(listOf(page))
            if (orderBefore.size > 1) showUndo(binding.root, binding.bottom, message, undo)
        }
    }

    private inner class ViewerAdapter : RecyclerView.Adapter<ViewerAdapter.Holder>() {
        val pages = mutableListOf<PageEntity>()

        inner class Holder(val b: ItemPageViewerBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<PageEntity>) {
            pages.clear()
            pages.addAll(list)
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemPageViewerBinding.inflate(LayoutInflater.from(parent.context), parent, false)).also { h ->
                h.b.image.onSingleTap = { toggleBars() }
            }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val path = pages[position].processedPath
            holder.b.image.contentDescription = getString(R.string.viewer_page_description, position + 1)
            // Search matches carried over from the document screen.
            holder.b.image.highlights = if (query.isEmpty()) emptyList() else
                PageTextCodec.decode(pages[position].ocr)?.linesMatching(query).orEmpty()
                    .map { RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            holder.b.image.tag = path
            val cached = cache.get(path)
            holder.b.image.setBitmap(cached)
            holder.b.loading.isVisible = cached == null
            if (cached != null) return
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { Bitmaps.decode(File(path), VIEWER_MAX_DIM) } ?: return@launch
                cache.put(path, bmp)
                if (holder.b.image.tag == path) {
                    holder.b.image.setBitmap(bmp)
                    holder.b.loading.isVisible = false
                }
            }
        }
    }

    companion object {
        private const val EXTRA_DOCUMENT_ID = "document_id"
        private const val EXTRA_PAGE_ID = "page_id"

        /** Sharp enough to read small print when zoomed, small enough to keep a few in memory. */
        private const val VIEWER_MAX_DIM = 2400

        private const val EXTRA_QUERY = "query"

        fun intent(context: Context, documentId: Long, pageId: Long, query: String = "") =
            Intent(context, PageViewerActivity::class.java)
                .putExtra(EXTRA_DOCUMENT_ID, documentId)
                .putExtra(EXTRA_PAGE_ID, pageId)
                .putExtra(EXTRA_QUERY, query)
    }
}
