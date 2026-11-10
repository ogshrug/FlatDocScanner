package io.github.ogshrug.flatdocscanner.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.annotation.SuppressLint
import android.util.Size
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.ogshrug.flatdocscanner.R
import io.github.ogshrug.flatdocscanner.databinding.ActivityCameraBinding
import io.github.ogshrug.flatdocscanner.repository
import io.github.ogshrug.flatdocscanner.settings
import io.github.ogshrug.flatdocscanner.scan.AutoCapture
import io.github.ogshrug.flatdocscanner.scan.Dewarper
import io.github.ogshrug.flatdocscanner.scan.FrameAnalyzer
import io.github.ogshrug.flatdocscanner.scan.LiveFrame
import io.github.ogshrug.flatdocscanner.scan.Quad
import io.github.ogshrug.flatdocscanner.scan.ScanPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Capture screen (S02). Shows the detected page live. In Auto mode it captures by itself
 * when the page is still and sharp, saves the page in the background and waits for the
 * next page turn. A shutter tap (either mode) opens [CropActivity] for that photo.
 * Tap the preview to focus there; pinch to zoom.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var analysisExecutor: ExecutorService

    /** Null until the first page is saved; saving creates the document then. */
    private var documentId: Long? = null
    private var openedFromDocument = false
    private var savedPages = 0
    private var shownPages = 0

    private var autoMode = true
    private var bookMode = false
    private var camera: Camera? = null
    private var torchOn = false

    /** Interval shooting: 0 = off, otherwise a photo every this many seconds. */
    private var timerSeconds = 0
    private var timerJob: Job? = null
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
                binding.timerButton.isEnabled = false
                binding.flashButton.isEnabled = false
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
        enableDarkEdgeToEdge()
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.topBar.padForSystemBars(top = true)
        binding.bottomBar.padForSystemBars(bottom = true)
        // Scanning a book takes minutes with no touches; the screen must not dim meanwhile.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        analysisExecutor = Executors.newSingleThreadExecutor()
        setUpGestures()

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
            if (bookMode) {
                showHint(getString(R.string.book_turned_on))
                warmUpBookModel()
            } else {
                hideHint.run()
            }
        }
        if (bookMode) warmUpBookModel()
        binding.autoButton.setOnClickListener {
            autoMode = !autoMode
            prefs.edit { putBoolean(PREF_AUTO, autoMode) }
            if (autoMode) setTimer(0)
            updateAutoButton()
            showHint(getString(if (autoMode) R.string.auto_turned_on else R.string.manual_turned_on))
        }
        binding.timerButton.setOnClickListener {
            // Off <-> every N seconds (N set in Settings). Interval shooting and auto-capture are alternatives.
            setTimer(if (timerSeconds == 0) settings.timerSeconds else 0)
            if (timerSeconds > 0) {
                showHint(resources.getQuantityString(R.plurals.timer_turned_on, timerSeconds, timerSeconds))
            } else {
                hideHint.run()
            }
        }
        binding.flashButton.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            updateFlashButton()
        }
        updateFlashButton()
        updateTimerButton()

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

    /** The flattening model is only needed for book spreads and curved pages; load it ahead of the first one. */
    private fun warmUpBookModel() {
        lifecycleScope.launch(Dispatchers.Default) { Dewarper.warmUp() }
    }

    // --- hints ---------------------------------------------------------------------------

    private val hideHint = Runnable {
        binding.hint.animate().alpha(0f).setDuration(200)
            .withEndAction { binding.hint.visibility = View.INVISIBLE }.start()
    }

    /** Says what a setting does, just above the controls, and fades away by itself. */
    private fun showHint(text: CharSequence) {
        binding.hint.removeCallbacks(hideHint)
        binding.hint.animate().cancel()
        binding.hint.text = text
        binding.hint.visibility = View.VISIBLE
        binding.hint.animate().alpha(1f).setDuration(150).withEndAction(null).start()
        binding.hint.postDelayed(hideHint, HINT_MILLIS)
    }

    // --- focus and zoom ------------------------------------------------------------------

    private val hideZoom = Runnable { binding.zoomLabel.animate().alpha(0f).setDuration(250).start() }

    /** Tap the preview to focus and expose there; pinch to zoom. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setUpGestures() {
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val zoom = cam.cameraInfo.zoomState.value ?: return true
                val ratio = (zoom.zoomRatio * detector.scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                cam.cameraControl.setZoomRatio(ratio)
                binding.zoomLabel.removeCallbacks(hideZoom)
                binding.zoomLabel.animate().cancel()
                binding.zoomLabel.text = getString(R.string.zoom_value, ratio)
                binding.zoomLabel.alpha = 1f
                binding.zoomLabel.postDelayed(hideZoom, 1200)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        // The overlay covers the preview exactly, so its touch coordinates are the preview's.
        binding.overlay.setOnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            if (!pinch.isInProgress) tap.onTouchEvent(event)
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = binding.preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }

        val ring = binding.focusRing
        ring.animate().cancel()
        ring.x = x - ring.width / 2f
        ring.y = y - ring.height / 2f
        ring.scaleX = 1.35f
        ring.scaleY = 1.35f
        ring.alpha = 1f
        ring.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            ring.animate().alpha(0f).setStartDelay(500).setDuration(250).withEndAction(null).start()
        }.start()
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
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                imageCapture = capture
                binding.flashButton.isVisible = camera?.cameraInfo?.hasFlashUnit() == true
                if (torchOn) camera?.cameraControl?.enableTorch(true)
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
        if (timerSeconds == 0) {
            binding.status.setText(statusText(decision.status))
            // The AUTO badge fills up while the page is held still.
            val counting = autoMode && decision.status == AutoCapture.Status.HOLD_STILL && decision.progress > 0f
            showBadge(counting, getString(R.string.badge_auto), decision.progress)
        }
        if (autoMode && timerSeconds == 0 && decision.capture) takePhoto(auto = true)
    }

    private fun showBadge(visible: Boolean, label: String, progress: Float) {
        binding.autoBadge.isVisible = visible
        if (!visible) return
        binding.autoLabel.text = label
        binding.autoRing.setProgressCompat((progress * 100).toInt(), false)
    }

    /** Interval shooting: counts down in the badge and takes a photo every [seconds]. */
    private fun setTimer(seconds: Int) {
        timerSeconds = seconds
        timerJob?.cancel()
        timerJob = null
        if (seconds > 0) {
            autoMode = false
            updateAutoButton()
            // Only while the camera is on screen: leaving pauses it, coming back restarts the count.
            timerJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        for (left in seconds downTo 1) {
                            binding.status.text = resources.getQuantityString(R.plurals.timer_on_description, seconds, seconds)
                            showBadge(true, left.toString(), (seconds - left + 1f) / seconds)
                            delay(1000)
                        }
                        showBadge(false, "", 0f)
                        takePhoto(auto = true)
                        // Wait for the shot to finish before counting again.
                        while (capturing) delay(100)
                    }
                }
            }
        } else {
            showBadge(false, "", 0f)
        }
        updateTimerButton()
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
                    if (spread) ScanPipeline.saveSpread(repository, file, documentId, liveQuad, settings.leftPageFirst)
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
        setTimer(0)
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

    /** The last saved page as a thumbnail with a page-count badge; tapping it finishes. */
    private fun refreshPageCount() {
        val id = documentId
        lifecycleScope.launch {
            val pages = if (id == null) emptyList() else repository.getPages(id)
            savedPages = pages.size
            val total = savedPages + pendingSaves.size * (if (bookMode) 2 else 1)
            binding.doneButton.isVisible = total > 0
            binding.pageBadge.text = java.text.NumberFormat.getIntegerInstance().format(total)
            binding.doneButton.contentDescription = resources.getQuantityString(R.plurals.done_description, total, total)
            pages.lastOrNull()?.let { Thumbnails.load(binding.lastPage, it.processedPath, 256, lifecycleScope) }
            // A small bounce confirms that the page just taken has landed in the folder.
            if (total > shownPages) {
                binding.doneButton.animate().cancel()
                binding.doneButton.scaleX = 0.8f
                binding.doneButton.scaleY = 0.8f
                binding.doneButton.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }
            shownPages = total
        }
    }

    private fun updateBookButton() {
        binding.bookButton.isActivated = bookMode
        binding.bookButton.setCompoundDrawablesWithIntrinsicBounds(0, if (bookMode) R.drawable.ic_book else R.drawable.ic_page, 0, 0)
        binding.bookButton.setText(if (bookMode) R.string.option_book else R.string.option_page)
        binding.modeTitle.setText(if (bookMode) R.string.mode_title_book else R.string.mode_title_page)
        binding.bookButton.contentDescription =
            getString(if (bookMode) R.string.book_on_description else R.string.book_off_description)
    }

    private fun updateAutoButton() {
        binding.autoButton.isActivated = autoMode
        binding.autoButton.contentDescription =
            getString(if (autoMode) R.string.auto_on_description else R.string.auto_off_description)
    }

    private fun updateFlashButton() {
        binding.flashButton.isActivated = torchOn
        binding.flashButton.setCompoundDrawablesWithIntrinsicBounds(0, if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off, 0, 0)
        binding.flashButton.contentDescription =
            getString(if (torchOn) R.string.flash_on_description else R.string.flash_off_description)
    }

    private fun updateTimerButton() {
        binding.timerButton.isActivated = timerSeconds > 0
        binding.timerButton.text =
            if (timerSeconds > 0) getString(R.string.timer_seconds_short, timerSeconds) else getString(R.string.option_timer)
        binding.timerButton.contentDescription = if (timerSeconds > 0) {
            resources.getQuantityString(R.plurals.timer_on_description, timerSeconds, timerSeconds)
        } else {
            getString(R.string.timer_off_description)
        }
    }

    private fun flash() {
        if (settings.vibrate) binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
        private const val HINT_MILLIS = 3500L

        fun intent(context: Context, documentId: Long?) = Intent(context, CameraActivity::class.java)
            .putExtra(EXTRA_DOCUMENT_ID, documentId ?: -1L)

        private fun newCaptureFile(context: Context) =
            File(File(context.cacheDir, "captures").apply { mkdirs() }, "capture_${System.currentTimeMillis()}.jpg")
    }
}
