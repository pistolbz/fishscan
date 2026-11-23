package com.scanpdf.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scanpdf.app.ui.ScanPdfApp
import com.scanpdf.app.ui.theme.ScanPdfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScanPdfTheme {
                ScanPdfApp()
            }
        }
    }
}
