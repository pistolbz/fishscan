package com.scanpdf.app.imaging

import com.scanpdf.app.model.PaperPreset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel size of a flattened page. Pure math, kept separate so it can be unit tested. */
object OutputSizing {

    /**
     * @param measuredW width of the document in the photo (pixels, longest of top/bottom edge)
     * @param measuredH height of the document in the photo (pixels, longest of left/right edge)
     * @param maxLongSide hard cap on the long side (preview vs. export)
     * @return width to height, keeping the orientation seen in the photo
     */
    fun compute(measuredW: Double, measuredH: Double, preset: PaperPreset, maxLongSide: Int): Pair<Int, Int> {
        val landscape = measuredW > measuredH
        val measuredLong = max(measuredW, measuredH)
        val measuredShort = max(1.0, min(measuredW, measuredH))

        val aspect = preset.aspect?.toDouble() ?: (measuredLong / measuredShort)
        val targetLong = preset.longMm?.let { it / 25.4 * preset.dpi } ?: measuredLong

        val outLong = max(1.0, min(targetLong, maxLongSide.toDouble()))
        val outShort = max(1.0, outLong / aspect)

        val long = outLong.roundToInt()
        val short = outShort.roundToInt()
        return if (landscape) long to short else short to long
    }
}
