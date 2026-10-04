package com.example.docscanner

import android.app.Application
import android.util.Log
import com.example.docscanner.data.AppDatabase
import com.example.docscanner.data.ScanRepository
import com.example.docscanner.scan.CornerModel
import com.example.docscanner.scan.Dewarper
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
        // Whatever has sat in the Trash for 30 days is deleted for good.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { repository.purgeOldTrash() } }
    }
}

val android.content.Context.repository: ScanRepository
    get() = (applicationContext as DocScannerApp).repository
