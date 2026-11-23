package com.scanpdf.app.imaging

import com.scanpdf.app.model.FilterType
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

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

        // Slightly deeper blacks so text stands out on the now-white paper.
        out.convertTo(out, -1, 1.12, -18.0)
        return out
    }

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
