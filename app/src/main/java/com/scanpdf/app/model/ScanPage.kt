package com.scanpdf.app.model

import android.graphics.Bitmap
import java.io.File

data class ScanPage(
    val id: Long,
    /** Private copy of the imported photo. Never modified. */
    val sourceFile: File,
    val edit: PageEdit,
    /** True when the detector found all four corners; false means the full photo is used. */
    val cornersDetected: Boolean,
    val thumbnail: Bitmap,
)
