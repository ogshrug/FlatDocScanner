package com.example.docscanner.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import com.example.docscanner.R
import com.example.docscanner.data.PageEntity
import com.example.docscanner.databinding.ActivityPageViewerBinding
import com.example.docscanner.databinding.ItemPageViewerBinding
import com.example.docscanner.repository
import com.example.docscanner.util.Bitmaps
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var title = ""
    private val adapter = ViewerAdapter()
    private var positioned = false

    /** A few decoded pages, so swiping back and forth doesn't decode again. */
    private val cache = LruCache<String, Bitmap>(5)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        binding = ActivityPageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.padForSystemBars(top = true)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener {
            val page = currentPage() ?: return@setOnMenuItemClickListener true
            when (it.itemId) {
                R.id.action_text -> TextUi.showPageText(this, page, binding.pager.currentItem + 1)
                R.id.action_edit -> startActivity(CropActivity.editPageIntent(this, page.id))
                R.id.action_share_image -> share(page)
                R.id.action_delete_page -> confirmDelete(page)
            }
            true
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
                        if (pages.isEmpty()) return@collect
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
        if (n > 0) binding.toolbar.title = getString(R.string.viewer_title, binding.pager.currentItem + 1, n)
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
        binding.toolbar.animate().cancel()
        if (show) {
            binding.toolbar.alpha = 0f
            binding.toolbar.isVisible = true
            binding.toolbar.animate().alpha(1f).setDuration(150).start()
        } else {
            binding.toolbar.animate().alpha(0f).setDuration(150).withEndAction { binding.toolbar.isVisible = false }.start()
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

    private fun confirmDelete(page: PageEntity) {
        val number = adapter.pages.indexOfFirst { it.id == page.id } + 1
        val last = adapter.pages.size == 1
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_page_title, number))
            .setMessage(if (last) R.string.delete_page_last_body else R.string.delete_page_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch { repository.deletePage(page) }
            }
            .show()
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

        fun intent(context: Context, documentId: Long, pageId: Long) =
            Intent(context, PageViewerActivity::class.java)
                .putExtra(EXTRA_DOCUMENT_ID, documentId)
                .putExtra(EXTRA_PAGE_ID, pageId)
    }
}
