package com.example.docscanner.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.docscanner.R
import com.example.docscanner.databinding.ActivityCameraBinding
import com.example.docscanner.repository
import com.example.docscanner.scan.AutoCapture
import com.example.docscanner.scan.FrameAnalyzer
import com.example.docscanner.scan.LiveFrame
import com.example.docscanner.scan.Quad
import com.example.docscanner.scan.ScanPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Capture screen (S02). Shows the detected page live. In Auto mode it captures by itself
 * when the page is still and sharp, saves the page in the background and waits for the
 * next page turn. A shutter tap (either mode) opens [CropActivity] for that photo.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var analysisExecutor: ExecutorService

    /** Null until the first page is saved; saving creates the document then. */
    private var documentId: Long? = null
    private var openedFromDocument = false
    private var savedPages = 0

    private var autoMode = true
    private var bookMode = false
    private lateinit var analyzer: FrameAnalyzer
    private val autoCapture = AutoCapture()
    private var capturing = false
    private var lastLive: LiveFrame? = null

    private val saveLock = Mutex()
    private val pendingSaves = mutableListOf<Job>()

    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
                binding.shutterButton.isEnabled = false
                binding.autoButton.isEnabled = false
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::importImage) }

    private val crop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            setCapturing(false)
            if (result.resultCode == Activity.RESULT_OK) {
                val id = result.data?.getLongExtra(CropActivity.EXTRA_DOCUMENT_ID, -1L) ?: -1L
                if (id >= 0) documentId = id
            } else {
                // Crop cancelled ("retake"): let auto-capture take this page again.
                autoCapture.onCaptureFailed()
            }
            refreshPageCount()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.topBar.padForSystemBars(top = true)
        binding.bottomBar.padForSystemBars(bottom = true)
        analysisExecutor = Executors.newSingleThreadExecutor()

        val fromIntent = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L)
        openedFromDocument = fromIntent >= 0
        val saved = savedInstanceState?.getLong(EXTRA_DOCUMENT_ID, -1L) ?: -1L
        documentId = (if (saved >= 0) saved else fromIntent).takeIf { it >= 0 }

        autoMode = prefs.getBoolean(PREF_AUTO, true)
        bookMode = prefs.getBoolean(PREF_BOOK, false)
        analyzer = FrameAnalyzer { frame -> runOnUiThread { onLiveFrame(frame) } }
        updateAutoButton()
        updateBookButton()
        binding.bookButton.setOnClickListener {
            bookMode = !bookMode
            prefs.edit { putBoolean(PREF_BOOK, bookMode) }
            updateBookButton()
            if (bookMode) Toast.makeText(this, R.string.book_turned_on, Toast.LENGTH_LONG).show()
        }
        binding.autoButton.setOnClickListener {
            autoMode = !autoMode
            prefs.edit { putBoolean(PREF_AUTO, autoMode) }
            updateAutoButton()
            if (autoMode) Toast.makeText(this, R.string.auto_turned_on, Toast.LENGTH_LONG).show()
        }

        binding.closeButton.setOnClickListener { finishWhenSaved(openDocument = false) }
        binding.shutterButton.setOnClickListener { takePhoto(auto = false) }
        binding.galleryButton.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.doneButton.setOnClickListener { finishWhenSaved(openDocument = true) }
        refreshPageCount()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
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
                // All three use cases share a 4:3 frame, so live corners map straight onto the photo.
                val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build())
                    .build()
                    .also { it.surfaceProvider = binding.preview.surfaceProvider }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build())
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(fourThree)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                imageCapture = capture
            } catch (e: Exception) {
                Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onLiveFrame(frame: LiveFrame) {
        if (isFinishing || capturing) return
        lastLive = frame
        val decision = autoCapture.onFrame(
            AutoCapture.Frame(frame.quad, frame.sharpness, frame.signature, frame.timeMillis),
        )
        val ready = decision.status == AutoCapture.Status.HOLD_STILL || decision.status == AutoCapture.Status.CAPTURING
        binding.overlay.show(
            frame.quad, frame.frameWidth, frame.frameHeight,
            ready = ready, progress = if (autoMode) decision.progress else 0f,
        )
        binding.status.setText(statusText(decision.status))
        if (autoMode && decision.capture) takePhoto(auto = true)
    }

    private fun statusText(status: AutoCapture.Status) = when (status) {
        AutoCapture.Status.NO_PAGE -> R.string.status_no_page
        AutoCapture.Status.TOO_SMALL -> R.string.status_too_small
        AutoCapture.Status.BLURRY -> R.string.status_blurry
        AutoCapture.Status.HOLD_STILL -> if (autoMode) R.string.status_hold_still else R.string.status_ready_manual
        AutoCapture.Status.CAPTURING -> R.string.status_capturing
        // In manual mode there is no page-turn wait; the page is simply ready.
        AutoCapture.Status.TURN_PAGE -> if (autoMode) R.string.status_turn_page else R.string.status_ready_manual
    }

    private fun takePhoto(auto: Boolean) {
        val capture = imageCapture ?: return
        if (capturing) return
        setCapturing(true, showProgress = !auto && !bookMode)
        val live = lastLive
        val liveQuad = live?.quad
        val book = bookMode
        if (!auto) autoCapture.onManualCapture(live?.signature, live?.timeMillis ?: 0L)
        binding.preview.display?.let { capture.targetRotation = it.rotation }
        val file = newCaptureFile(this)
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Book spreads are always saved directly: each photo becomes two pages.
                    if (auto || book) {
                        flash()
                        saveInBackground(file, liveQuad, book)
                        setCapturing(false)
                    } else {
                        openCrop(file)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    setCapturing(false)
                    if (auto) autoCapture.onCaptureFailed()
                    Toast.makeText(this@CameraActivity, R.string.capture_failed, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    /** Saves auto-captured pages one at a time, so the first save creates the document exactly once. */
    private fun saveInBackground(file: File, liveQuad: Quad?, spread: Boolean) {
        val job = lifecycleScope.launch {
            saveLock.withLock {
                runCatching {
                    if (spread) ScanPipeline.saveSpread(repository, file, documentId, liveQuad)
                    else ScanPipeline.savePage(repository, file, documentId, liveQuad)
                }
                    .onSuccess { documentId = it }
                    .onFailure {
                        file.delete()
                        Toast.makeText(this@CameraActivity, R.string.auto_save_failed, Toast.LENGTH_LONG).show()
                    }
            }
        }
        pendingSaves += job
        job.invokeOnCompletion {
            runOnUiThread {
                pendingSaves.remove(job)
                refreshPageCount()
            }
        }
        refreshPageCount()
    }

    private fun importImage(uri: Uri) {
        if (capturing) return
        setCapturing(true)
        lifecycleScope.launch {
            val file = newCaptureFile(this@CameraActivity)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                }.isSuccess
            }
            if (ok) openCrop(file) else {
                file.delete()
                setCapturing(false)
                Toast.makeText(this@CameraActivity, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openCrop(file: File) {
        lifecycleScope.launch {
            // A new document must exist before the crop screen adds to it.
            pendingSaves.toList().joinAll()
            crop.launch(CropActivity.newPageIntent(this@CameraActivity, file, documentId))
        }
    }

    private fun finishWhenSaved(openDocument: Boolean) {
        binding.progress.isVisible = pendingSaves.isNotEmpty()
        binding.doneButton.isEnabled = false
        lifecycleScope.launch {
            pendingSaves.toList().joinAll()
            val id = documentId
            if (openDocument && id != null && !openedFromDocument) {
                startActivity(DocumentActivity.intent(this@CameraActivity, id))
            }
            finish()
        }
    }

    private fun refreshPageCount() {
        val id = documentId
        lifecycleScope.launch {
            savedPages = if (id == null) 0 else repository.getPages(id).size
            val total = savedPages + pendingSaves.size * (if (bookMode) 2 else 1)
            val count = resources.getQuantityString(R.plurals.page_count, total, total)
            binding.pageCount.text = when {
                total == 0 -> getString(R.string.camera_no_pages)
                pendingSaves.isNotEmpty() -> getString(R.string.camera_saving, count)
                else -> count
            }
            binding.doneButton.isEnabled = total > 0
        }
    }

    private fun updateBookButton() {
        binding.bookButton.setText(if (bookMode) R.string.book_on else R.string.book_off)
        binding.bookButton.contentDescription =
            getString(if (bookMode) R.string.book_on_description else R.string.book_off_description)
    }

    private fun updateAutoButton() {
        binding.autoButton.setText(if (autoMode) R.string.auto_on else R.string.auto_off)
        binding.autoButton.contentDescription =
            getString(if (autoMode) R.string.auto_on_description else R.string.auto_off_description)
    }

    private fun flash() {
        binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        binding.flash.animate().cancel()
        binding.flash.alpha = 0.6f
        binding.flash.animate().alpha(0f).setDuration(180).start()
    }

    private fun setCapturing(value: Boolean, showProgress: Boolean = true) {
        capturing = value
        binding.progress.isVisible = value && showProgress
        binding.shutterButton.isEnabled = !value
        binding.galleryButton.isEnabled = !value
        if (value) binding.overlay.clear()
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
        private const val PREF_AUTO = "auto_capture"
        private const val PREF_BOOK = "book_mode"

        fun intent(context: Context, documentId: Long?) = Intent(context, CameraActivity::class.java)
            .putExtra(EXTRA_DOCUMENT_ID, documentId ?: -1L)

        private fun newCaptureFile(context: Context) =
            File(File(context.cacheDir, "captures").apply { mkdirs() }, "capture_${System.currentTimeMillis()}.jpg")
    }
}
