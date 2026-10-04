package com.example.docscanner.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.docscanner.R
import com.example.docscanner.data.DocumentSummary
import com.example.docscanner.databinding.ActivityMainBinding
import com.example.docscanner.databinding.ItemDocumentBinding
import com.example.docscanner.repository
import kotlinx.coroutines.launch

/** Library of saved scans (screen S01). */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.list.padForSystemBars(bottom = true)
        binding.scanButton.liftAboveNavigationBar()

        val adapter = DocumentAdapter { startActivity(DocumentActivity.intent(this, it.id)) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.scanButton.setOnClickListener { startActivity(CameraActivity.intent(this, documentId = null)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeDocuments().collect { docs ->
                    adapter.submitList(docs)
                    binding.empty.isVisible = docs.isEmpty()
                }
            }
        }
    }

    private inner class DocumentAdapter(
        private val onClick: (DocumentSummary) -> Unit,
    ) : ListAdapter<DocumentSummary, DocumentAdapter.Holder>(Diff) {

        inner class Holder(val b: ItemDocumentBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val doc = getItem(position)
            holder.b.title.text = doc.title
            holder.b.subtitle.text = getString(
                R.string.document_subtitle,
                resources.getQuantityString(R.plurals.page_count, doc.pageCount, doc.pageCount),
                DateUtils.getRelativeTimeSpanString(doc.updatedAt),
            )
            Thumbnails.load(holder.b.cover, doc.coverPath, 256, lifecycleScope)
            holder.b.root.setOnClickListener { onClick(doc) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DocumentSummary>() {
        override fun areItemsTheSame(a: DocumentSummary, b: DocumentSummary) = a.id == b.id
        override fun areContentsTheSame(a: DocumentSummary, b: DocumentSummary) = a == b
    }
}
