package com.scanpdf.app

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class ScanPdfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) {
            Log.e("ScanPDF", "Không nạp được OpenCV")
        }
    }
}
