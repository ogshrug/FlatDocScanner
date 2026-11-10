package io.github.ogshrug.flatdocscanner

import android.app.Application
import android.util.Log
import io.github.ogshrug.flatdocscanner.data.AppDatabase
import io.github.ogshrug.flatdocscanner.data.ScanRepository
import io.github.ogshrug.flatdocscanner.scan.CornerModel
import io.github.ogshrug.flatdocscanner.scan.Dewarper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

class DocScannerApp : Application() {

    lateinit var repository: ScanRepository
        private set

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("DocScanner", "OpenCV failed to load")
        Dewarper.init(this)
        CornerModel.init(this)
        repository = ScanRepository(this, AppDatabase.create(this).scanDao())
        val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The page finder is needed by the camera's very first frame; load it while the
        // library is still drawing instead of when the camera opens.
        background.launch { CornerModel.warmUp() }
        // Whatever has sat in the Trash for 30 days is deleted for good.
        background.launch(Dispatchers.IO) { runCatching { repository.purgeOldTrash() } }
    }
}

val android.content.Context.repository: ScanRepository
    get() = (applicationContext as DocScannerApp).repository
