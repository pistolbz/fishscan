package com.scanpdf.app.imaging

import com.scanpdf.app.model.FilterType
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Document filters. All functions take and return 3-channel RGB mats (no alpha, so brightness
 * changes can never make the page semi-transparent).
 *
 * "Làm sáng" works like a scanner's flat-field correction: estimate how bright the bare paper
 * is at every point (text removed by a max-filter, then smoothed), and divide the photo by it.
 * Shadows and uneven light disappear, the paper becomes white and the text keeps its colour.
 */
object Enhancer {

    fun apply(rgb: Mat, filter: FilterType, brightness: Float, contrast: Float): Mat {
        val out = when (filter) {
            FilterType.ORIGINAL -> rgb.clone()
            FilterType.ENHANCE -> flatten(rgb)
            FilterType.GRAY -> {
                val flat = flatten(rgb)
                val gray = Mat()
                Imgproc.cvtColor(flat, gray, Imgproc.COLOR_RGB2GRAY)
                Imgproc.cvtColor(gray, flat, Imgproc.COLOR_GRAY2RGB)
                gray.release()
                flat
            }
            FilterType.BW -> blackAndWhite(rgb)
        }
        adjust(out, brightness, contrast)
        return out
    }

    private fun flatten(rgb: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val background = estimatePaper(gray)
        gray.release()

        val channels = ArrayList<Mat>(3)
        Core.split(rgb, channels)
        for (channel in channels) Core.divide(channel, background, channel, 255.0)
        val out = Mat()
        Core.merge(channels, out)
        channels.forEach { it.release() }
        background.release()

        applyLevels(out)
        sharpen(out)
        return out
    }

    /**
     * Scanner-style tone curve on a flattened page: the darkest ink becomes black, paper becomes
     * pure white, and a gamma > 1 darkens mid-tones so thin strokes stay solid. Without this,
     * photographed print only reaches grey (~90 of 255) after flattening.
     */
    private fun applyLevels(img: Mat) {
        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_RGB2GRAY)
        // Ink level = darkest 1% of pixels; capped so a nearly blank page is not blown up.
        val black = percentile(gray, 0.01).coerceIn(0.0, BLACK_POINT_MAX)
        gray.release()
        val white = WHITE_POINT
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val table = ByteArray(256) { v ->
            val t = ((v - black) / (white - black)).coerceIn(0.0, 1.0)
            (255.0 * Math.pow(t, GAMMA)).roundToInt().toByte()
        }
        lut.put(0, 0, table)
        Core.LUT(img, lut, img)
        lut.release()
    }

    /** Unsharp mask; radius scales with resolution so preview and export look alike. */
    private fun sharpen(img: Mat) {
        val sigma = max(0.8, max(img.cols(), img.rows()) / 2800.0)
        val blurred = Mat()
        Imgproc.GaussianBlur(img, blurred, Size(0.0, 0.0), sigma)
        Core.addWeighted(img, 1.0 + SHARPEN_AMOUNT, blurred, -SHARPEN_AMOUNT, 0.0, img)
        blurred.release()
    }

    /** Value below which [fraction] of the pixels fall (on a ≤1000 px copy, for speed). */
    private fun percentile(gray: Mat, fraction: Double): Double {
        val scale = min(1.0, 1000.0 / max(gray.cols(), gray.rows()))
        val small = Mat()
        Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val bytes = ByteArray((small.total()).toInt())
        small.get(0, 0, bytes)
        small.release()
        val histogram = IntArray(256)
        for (b in bytes) histogram[b.toInt() and 0xFF]++
        val target = bytes.size * fraction
        var cumulative = 0
        for (v in 0..255) {
            cumulative += histogram[v]
            if (cumulative >= target) return v.toDouble()
        }
        return 255.0
    }

    private const val BLACK_POINT_MAX = 140.0
    private const val WHITE_POINT = 235.0
    private const val GAMMA = 1.35
    private const val SHARPEN_AMOUNT = 0.6

    private fun blackAndWhite(rgb: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val background = estimatePaper(gray)
        Core.divide(gray, background, gray, 255.0)
        background.release()

        var block = max(gray.cols(), gray.rows()) / 40
        block = max(block, 15)
        if (block % 2 == 0) block++

        val bw = Mat()
        Imgproc.adaptiveThreshold(gray, bw, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, block, 12.0)
        val out = Mat()
        Imgproc.cvtColor(bw, out, Imgproc.COLOR_GRAY2RGB)
        gray.release()
        bw.release()
        return out
    }

    /** Brightness of the bare paper at every pixel (same size as [gray], never 0). */
    internal fun estimatePaper(gray: Mat): Mat {
        val scale = min(1.0, 600.0 / max(gray.cols(), gray.rows()))
        val small = Mat()
        Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)

        // Max-filter wipes out dark strokes (text, lines), leaving the paper.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.dilate(small, small, kernel)
        kernel.release()
        Imgproc.medianBlur(small, small, 21)

        val background = Mat()
        Imgproc.resize(small, background, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        Core.max(background, Scalar(1.0), background)
        return background
    }

    private fun adjust(img: Mat, brightness: Float, contrast: Float) {
        if (brightness == 0f && contrast == 0f) return
        val alpha = 1.0 + contrast
        // Contrast pivots around mid-grey so it does not also shift brightness.
        val beta = brightness * 100.0 + 128.0 * (1.0 - alpha)
        img.convertTo(img, -1, alpha, beta)
    }
}
