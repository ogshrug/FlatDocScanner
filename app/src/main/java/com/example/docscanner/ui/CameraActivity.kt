package com.example.docscanner.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.docscanner.R
import com.example.docscanner.databinding.ActivityCameraBinding
import com.example.docscanner.repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Capture screen (S02). Each photo goes to [CropActivity]; the camera stays open
 * for the next page until the user taps Done.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null

    /** Null until the first page is saved; [CropActivity] creates the document then. */
    private var documentId: Long? = null
    private var openedFromDocument = false
    private var busy = false

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
                binding.shutterButton.isEnabled = false
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::importImage) }

    private val crop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            setBusy(false)
            if (result.resultCode == Activity.RESULT_OK) {
                val id = result.data?.getLongExtra(EXTRA_DOCUMENT_ID, -1L) ?: -1L
                if (id >= 0) documentId = id
                refreshPageCount()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.topBar.padForSystemBars(top = true)
        binding.bottomBar.padForSystemBars(bottom = true)

        val fromIntent = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L)
        openedFromDocument = fromIntent >= 0
        val saved = savedInstanceState?.getLong(EXTRA_DOCUMENT_ID, -1L) ?: -1L
        documentId = (if (saved >= 0) saved else fromIntent).takeIf { it >= 0 }

        binding.closeButton.setOnClickListener { finish() }
        binding.shutterButton.setOnClickListener { takePhoto() }
        binding.galleryButton.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.doneButton.setOnClickListener { done() }
        refreshPageCount()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(EXTRA_DOCUMENT_ID, documentId ?: -1L)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = binding.preview.surfaceProvider }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (busy) return
        setBusy(true)
        binding.preview.display?.let { capture.targetRotation = it.rotation }
        val file = newCaptureFile(this)
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = openCrop(file)

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    setBusy(false)
                    Toast.makeText(this@CameraActivity, R.string.capture_failed, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    private fun importImage(uri: Uri) {
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val file = newCaptureFile(this@CameraActivity)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                }.isSuccess
            }
            if (ok) openCrop(file) else {
                file.delete()
                setBusy(false)
                Toast.makeText(this@CameraActivity, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openCrop(file: File) {
        crop.launch(CropActivity.newPageIntent(this, file, documentId))
    }

    private fun done() {
        val id = documentId
        if (id != null && !openedFromDocument) startActivity(DocumentActivity.intent(this, id))
        finish()
    }

    private fun refreshPageCount() {
        val id = documentId
        lifecycleScope.launch {
            val count = if (id == null) 0 else repository.getPages(id).size
            binding.pageCount.text = if (count == 0) {
                getString(R.string.camera_no_pages)
            } else {
                resources.getQuantityString(R.plurals.page_count, count, count)
            }
            binding.doneButton.isEnabled = count > 0
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.progress.isVisible = value
        binding.shutterButton.isEnabled = !value
        binding.galleryButton.isEnabled = !value
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"

        fun intent(context: Context, documentId: Long?) = Intent(context, CameraActivity::class.java)
            .putExtra(EXTRA_DOCUMENT_ID, documentId ?: -1L)

        private fun newCaptureFile(context: Context) =
            File(File(context.cacheDir, "captures").apply { mkdirs() }, "capture_${System.currentTimeMillis()}.jpg")
    }
}
