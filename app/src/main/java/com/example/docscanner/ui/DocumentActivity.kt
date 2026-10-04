package com.example.docscanner.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
import com.example.docscanner.export.PdfExporter
import com.example.docscanner.repository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/** Pages of one document (S03): reorder, edit, delete, add more, share as PDF. */
class DocumentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentBinding
    private val documentId by lazy { intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L) }
    private var title = ""
    private val adapter = PageAdapter()
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.grid.padForSystemBars(bottom = true)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_share_pdf -> sharePdf()
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
                            binding.toolbar.title = doc.title
                        }
                    }
                }
                launch {
                    repository.observePages(documentId).collect { if (!dragging) adapter.submit(it) }
                }
            }
        }
    }

    private fun showPageMenu(anchor: android.view.View, page: PageEntity, number: Int) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.title_crop)
            menu.add(0, 2, 1, R.string.action_delete)
            setOnMenuItemClickListener {
                if (it.itemId == 1) startActivity(CropActivity.editPageIntent(this@DocumentActivity, page.id))
                else confirmDeletePage(page, number)
                true
            }
            show()
        }
    }

    private fun confirmDeletePage(page: PageEntity, number: Int) {
        val last = adapter.itemCount == 1
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_page_title, number))
            .setMessage(if (last) R.string.delete_page_last_body else R.string.delete_page_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch { repository.deletePage(page) }
            }
            .show()
    }

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

    private fun sharePdf() {
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val file = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
                    val name = title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifBlank { "scan" }
                    File(dir, "$name.pdf").also { PdfExporter.export(repository.getPages(documentId), it) }
                }
            }.getOrNull()
            binding.progress.isVisible = false
            if (file == null) {
                Toast.makeText(this@DocumentActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@DocumentActivity, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share_pdf_chooser)))
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        val pages = mutableListOf<PageEntity>()

        inner class Holder(val b: ItemPageBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(list: List<PageEntity>) {
            pages.clear()
            pages.addAll(list)
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
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
            holder.b.number.text = getString(R.string.page_number, position + 1)
            Thumbnails.load(holder.b.image, page.processedPath, 512, lifecycleScope)
            holder.b.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) showPageMenu(it, pages[pos], pos + 1)
            }
        }
    }

    /** Long-press and drag to reorder; the new order is saved when the page is dropped. */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0,
    ) {
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
