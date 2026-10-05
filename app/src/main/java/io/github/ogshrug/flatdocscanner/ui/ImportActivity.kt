package io.github.ogshrug.flatdocscanner.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.ogshrug.flatdocscanner.R
import io.github.ogshrug.flatdocscanner.databinding.ActivityImportBinding
import io.github.ogshrug.flatdocscanner.repository
import io.github.ogshrug.flatdocscanner.scan.ScanPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Receives photos shared to the app (or opened with it) and scans each one like an
 * auto-captured page: the page is found, cropped and cleaned, and all of them go into one
 * new document, which then opens.
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uris = sharedImages(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.import_nothing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        binding.cancelButton.setOnClickListener { finish() }
        // Survive rotation without scanning twice: only start on a fresh launch.
        if (savedInstanceState == null) scan(uris)
    }

    private fun sharedImages(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        else -> emptyList()
    }

    private fun scan(uris: List<Uri>) {
        binding.progressBar.max = uris.size
        lifecycleScope.launch {
            // Into an existing folder only via the app's own (non-exported) entry point, so other
            // apps sharing images can't add pages to a folder; otherwise a new folder.
            val internal = intent.component?.className == INTERNAL_ENTRY
            var documentId: Long? = if (internal) intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L).takeIf { it >= 0 } else null
            val intoExisting = documentId != null
            var failed = 0
            uris.forEachIndexed { i, uri ->
                binding.progressText.text = getString(R.string.import_progress, i + 1, uris.size)
                binding.progressBar.setProgressCompat(i, true)
                val file = File(File(cacheDir, "captures").apply { mkdirs() }, "shared_${System.nanoTime()}.jpg")
                runCatching {
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                    documentId = ScanPipeline.savePage(repository, file, documentId, liveQuad = null)
                }.onFailure {
                    if (it is CancellationException) throw it
                    file.delete()
                    failed++
                }
            }
            if (failed > 0) {
                Toast.makeText(this@ImportActivity, resources.getQuantityString(R.plurals.import_failed, failed, failed), Toast.LENGTH_LONG).show()
            }
            documentId?.takeIf { !intoExisting }?.let {
                startActivity(DocumentActivity.intent(this@ImportActivity, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        }
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
        /** The non-exported activity-alias declared in the manifest. */
        private val INTERNAL_ENTRY = ImportActivity::class.java.name.substringBeforeLast('.') + ".ImportIntoFolder"

        /** Scans picked photos into [documentId], or a new folder when null. */
        fun intent(context: android.content.Context, uris: List<Uri>, documentId: Long?): Intent {
            val intent = Intent().setClassName(context, INTERNAL_ENTRY)
                .setAction(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .putExtra(EXTRA_DOCUMENT_ID, documentId ?: -1L)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri(null, uris[0]).apply {
                uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
            }
            return intent
        }
    }
}
