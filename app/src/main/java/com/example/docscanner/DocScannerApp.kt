package com.example.docscanner

import android.app.Application
import android.util.Log
import com.example.docscanner.data.AppDatabase
import com.example.docscanner.data.ScanRepository
import org.opencv.android.OpenCVLoader

class DocScannerApp : Application() {

    lateinit var repository: ScanRepository
        private set

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("DocScanner", "OpenCV failed to load")
        repository = ScanRepository(this, AppDatabase.create(this).scanDao())
    }
}

val android.content.Context.repository: ScanRepository
    get() = (applicationContext as DocScannerApp).repository
