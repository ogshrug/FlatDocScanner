package com.example.docscanner.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.docscanner.R
import com.example.docscanner.data.PageEntity
import com.example.docscanner.databinding.ActivityCropBinding
import com.example.docscanner.repository
import com.example.docscanner.scan.PageFilter
import com.example.docscanner.scan.PageMode
import com.example.docscanner.scan.PageSide
import com.example.docscanner.scan.PageSpec
import com.example.docscanner.scan.Quad
import com.example.docscanner.scan.ScanPipeline
import com.example.docscanner.util.Bitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Crop and clean up one page (S04), in two steps: outline the page (flat or curved), then
 * pick a look. Works on a new capture, or re-edits a saved page from its kept original photo.
 * Pages cut from a book spread skip the outline step: their geometry comes from the spine.
 */
class CropActivity : AppCompatActivity() {

    private enum class Step { CORNERS, FILTER }

    private lateinit var binding: ActivityCropBinding
    private var step = Step.CORNERS

    private var capture: File? = null
    private var documentId: Long? = null
    private var editingPage: PageEntity? = null

    private var source: Bitmap? = null
    /** Output of the expensive geometry step, reused while the look changes. */
    private var geometry: Bitmap? = null
    private var preview: Bitmap? = null
    private var spec = PageSpec(quad = null, mode = PageMode.FLAT)
    private var detected = false
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)

        capture = intent.getStringExtra(EXTRA_CAPTURE_PATH)?.let(::File)
        documentId = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L).takeIf { it >= 0 }

        binding.toolbar.setNavigationOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        binding.cornerEditor.onQuadChanged = { updateCornerHint() }
        binding.modeToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            spec = spec.copy(mode = if (id == R.id.modeCurved) PageMode.CURVED else PageMode.FLAT)
            updateCornerHint()
        }
        binding.filters.setOnCheckedStateChangeListener { _, ids ->
            val f = when (ids.firstOrNull()) {
                R.id.filterOriginal -> PageFilter.ORIGINAL
                R.id.filterGray -> PageFilter.GRAY
                R.id.filterBw -> PageFilter.BW
                else -> PageFilter.COLOR
            }
            if (f != spec.filter) {
                spec = spec.copy(filter = f)
                if (step == Step.FILTER) renderFinish()
            }
        }
        binding.fingersSwitch.setOnCheckedChangeListener { _, on ->
            if (on != spec.removeFingers) {
                spec = spec.copy(removeFingers = on)
                if (step == Step.FILTER) renderFinish()
            }
        }

        load(intent.getLongExtra(EXTRA_PAGE_ID, -1L))
    }

    private fun load(pageId: Long) {
        setWorking(true)
        lifecycleScope.launch {
            val page = if (pageId >= 0) repository.getPage(pageId) else null
            editingPage = page
            val file = page?.let { File(it.originalPath) } ?: capture
            val bitmap = file?.let { withContext(Dispatchers.IO) { Bitmaps.decode(it, ScanPipeline.WORKING_MAX_DIM) } }
            if (bitmap == null) {
                Toast.makeText(this@CropActivity, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
                cancel()
                return@launch
            }
            source = bitmap
            spec = if (page != null) {
                detected = true
                ScanPipeline.specOf(page)
            } else {
                withContext(Dispatchers.Default) { ScanPipeline.specForSinglePage(bitmap, null) }
                    .also { detected = it.quad != null }
            }
            binding.filters.check(chipFor(spec.filter))
            binding.fingersSwitch.isChecked = spec.removeFingers
            binding.modeToggle.check(if (spec.mode == PageMode.CURVED) R.id.modeCurved else R.id.modeFlat)

            if (spec.side != PageSide.NONE) {
                // Half of a book spread: nothing to outline.
                showFilter()
            } else {
                val quad = spec.quad?.denormalized(bitmap.width, bitmap.height)
                    ?: Quad.inset(bitmap.width, bitmap.height)
                binding.cornerEditor.setImage(bitmap, quad)
                showCorners()
                setWorking(false)
            }
        }
    }

    private fun showCorners() {
        step = Step.CORNERS
        binding.toolbar.setTitle(R.string.title_crop)
        binding.cornerEditor.isVisible = true
        binding.modeToggle.isVisible = true
        binding.result.isVisible = false
        binding.filterBar.isVisible = false
        binding.secondaryButton.isVisible = true
        binding.secondaryButton.setText(R.string.action_full_image)
        binding.secondaryButton.setOnClickListener {
            val bmp = source ?: return@setOnClickListener
            binding.cornerEditor.setImage(bmp, Quad.inset(bmp.width, bmp.height, 0.0))
            updateCornerHint()
        }
        binding.primaryButton.setText(R.string.action_next)
        binding.primaryButton.setOnClickListener { showFilter() }
        updateCornerHint()
    }

    private fun updateCornerHint() {
        if (step != Step.CORNERS) return
        val valid = binding.cornerEditor.quad?.isConvex() == true
        binding.hint.setText(
            when {
                !valid -> R.string.hint_invalid
                spec.mode == PageMode.CURVED -> R.string.hint_curved
                detected -> R.string.hint_detected
                else -> R.string.hint_not_detected
            },
        )
        binding.primaryButton.isEnabled = valid
    }

    private fun showFilter() {
        val bmp = source ?: return
        if (spec.side == PageSide.NONE) {
            val quad = binding.cornerEditor.quad ?: return
            spec = spec.copy(quad = quad.normalized(bmp.width, bmp.height))
        }
        step = Step.FILTER
        binding.toolbar.setTitle(R.string.title_filter)
        binding.hint.text = null
        binding.cornerEditor.isVisible = false
        binding.modeToggle.isVisible = false
        binding.result.isVisible = true
        binding.filterBar.isVisible = true
        binding.secondaryButton.isVisible = false
        binding.primaryButton.setText(R.string.action_save)
        binding.primaryButton.setOnClickListener { save() }

        setWorking(true)
        job?.cancel()
        val forSpec = spec
        // Not recycled: a cancelled background render may still be reading the old one.
        geometry = null
        job = lifecycleScope.launch {
            geometry = withContext(Dispatchers.Default) { ScanPipeline.geometry(bmp, forSpec) }
            renderFinish()
        }
    }

    private fun renderFinish() {
        val g = geometry ?: return
        setWorking(true)
        job?.cancel()
        val forSpec = spec
        job = lifecycleScope.launch {
            val out = withContext(Dispatchers.Default) { ScanPipeline.finish(g, forSpec) }
            preview = out
            binding.result.setImageBitmap(out)
            setWorking(false)
        }
    }

    private fun save() {
        val out = preview ?: return
        setWorking(true)
        val forSpec = spec
        lifecycleScope.launch {
            val result = runCatching {
                val page = editingPage
                if (page != null) {
                    repository.updatePage(page, forSpec, out)
                    page.documentId
                } else {
                    repository.addPage(documentId, capture!!, forSpec, out)
                }
            }
            result.onSuccess { docId ->
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_DOCUMENT_ID, docId))
                finish()
            }.onFailure {
                setWorking(false)
                Toast.makeText(this@CropActivity, R.string.save_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goBack() {
        if (step == Step.FILTER && spec.side == PageSide.NONE) {
            job?.cancel()
            setWorking(false)
            showCorners()
        } else {
            cancel()
        }
    }

    /** Leaves without saving; a new capture is thrown away so the cache doesn't fill up. */
    private fun cancel() {
        job?.cancel()
        if (editingPage == null) capture?.delete()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun setWorking(working: Boolean) {
        binding.progress.isVisible = working
        binding.primaryButton.isEnabled = !working &&
            (step == Step.FILTER || binding.cornerEditor.quad?.isConvex() == true)
        binding.secondaryButton.isEnabled = !working
        binding.fingersSwitch.isEnabled = !working
        for (i in 0 until binding.filters.childCount) binding.filters.getChildAt(i).isEnabled = !working
    }

    private fun chipFor(f: PageFilter) = when (f) {
        PageFilter.ORIGINAL -> R.id.filterOriginal
        PageFilter.COLOR -> R.id.filterColor
        PageFilter.GRAY -> R.id.filterGray
        PageFilter.BW -> R.id.filterBw
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
        private const val EXTRA_CAPTURE_PATH = "capture_path"
        private const val EXTRA_PAGE_ID = "page_id"

        fun newPageIntent(context: Context, capture: File, documentId: Long?) =
            Intent(context, CropActivity::class.java)
                .putExtra(EXTRA_CAPTURE_PATH, capture.path)
                .putExtra(EXTRA_DOCUMENT_ID, documentId ?: -1L)

        fun editPageIntent(context: Context, pageId: Long) =
            Intent(context, CropActivity::class.java).putExtra(EXTRA_PAGE_ID, pageId)
    }
}
