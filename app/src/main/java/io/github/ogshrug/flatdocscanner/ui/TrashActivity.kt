package io.github.ogshrug.flatdocscanner.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.ogshrug.flatdocscanner.R
import io.github.ogshrug.flatdocscanner.data.DocumentSummary
import io.github.ogshrug.flatdocscanner.data.TrashedPage
import io.github.ogshrug.flatdocscanner.databinding.ActivityListScreenBinding
import io.github.ogshrug.flatdocscanner.databinding.ItemFolderBinding
import io.github.ogshrug.flatdocscanner.repository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Deleted folders and pages, restorable for 30 days. Tap an item to restore or delete it for good. */
class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListScreenBinding
    private val adapter = TrashAdapter()

    private sealed interface Item {
        data class Header(val title: Int) : Item
        data class Folder(val doc: DocumentSummary) : Item
        data class Page(val page: TrashedPage) : Item
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        binding = ActivityListScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)
        binding.toolbar.setTitle(R.string.trash_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.menu.add(R.string.action_empty_trash).setOnMenuItemClickListener { confirmEmpty(); true }
        binding.info.setText(R.string.trash_info)
        binding.empty.setText(R.string.trash_empty)

        val span = 3
        binding.list.layoutManager = GridLayoutManager(this, span).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.items[position] is Item.Header) span else 1
            }
        }
        binding.list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeTrashedDocuments().combine(repository.observeTrashedPages()) { docs, pages ->
                    buildList {
                        if (docs.isNotEmpty()) {
                            add(Item.Header(R.string.trash_folders))
                            docs.forEach { add(Item.Folder(it)) }
                        }
                        if (pages.isNotEmpty()) {
                            add(Item.Header(R.string.trash_pages))
                            pages.forEach { add(Item.Page(it)) }
                        }
                    }
                }.collect {
                    adapter.submit(it)
                    binding.empty.isVisible = it.isEmpty()
                }
            }
        }
    }

    private fun confirmEmpty() {
        if (adapter.items.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.empty_trash_title)
            .setMessage(R.string.empty_trash_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_empty_trash) { _, _ -> lifecycleScope.launch { repository.emptyTrash() } }
            .show()
    }

    private fun choose(title: String, restore: suspend () -> Unit, purge: suspend () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(arrayOf(getString(R.string.action_restore), getString(R.string.action_delete_forever))) { _, which ->
                if (which == 0) {
                    lifecycleScope.launch {
                        restore()
                        Toast.makeText(this@TrashActivity, R.string.restored, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.delete_forever_title)
                        .setMessage(R.string.delete_forever_body)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.action_delete_forever) { _, _ -> lifecycleScope.launch { purge() } }
                        .show()
                }
            }
            .show()
    }

    private inner class TrashAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Item> = emptyList()

        fun submit(list: List<Item>) {
            items = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val tv = TextView(parent.context).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    val pad = (8 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad * 2, pad, pad)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            return object : RecyclerView.ViewHolder(ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder.itemView as TextView).setText(item.title)
                is Item.Folder -> bind(
                    ItemFolderBinding.bind(holder.itemView), item.doc.coverPath, item.doc.title,
                    DateUtils.getRelativeTimeSpanString(item.doc.deletedAt ?: 0).toString(),
                ) {
                    choose(item.doc.title, { repository.restoreDocument(item.doc.id) }, { repository.purgeDocument(item.doc.id) })
                }
                is Item.Page -> bind(
                    ItemFolderBinding.bind(holder.itemView), item.page.page.processedPath,
                    getString(R.string.trash_page_from, item.page.documentTitle),
                    DateUtils.getRelativeTimeSpanString(item.page.page.deletedAt ?: 0).toString(),
                ) {
                    choose(item.page.documentTitle, { repository.restorePage(item.page.page) }, { repository.purgePage(item.page.page) })
                }
            }
        }

        private fun bind(b: ItemFolderBinding, cover: String?, title: String, subtitle: String, onClick: () -> Unit) {
            b.cover.aspect = 4f / 3f
            b.title.text = title
            b.count.text = subtitle
            b.count.setCompoundDrawables(null, null, null, null)
            b.emptyIcon.isVisible = cover == null
            b.check.isVisible = false
            Thumbnails.load(b.cover, cover, 360, lifecycleScope)
            b.root.setOnClickListener { onClick() }
        }
    }
}
