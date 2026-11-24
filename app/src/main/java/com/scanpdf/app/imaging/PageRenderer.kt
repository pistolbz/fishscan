package com.scanpdf.app.imaging

import android.graphics.Bitmap
import com.scanpdf.app.model.PageEdit
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Bitmap ⇄ OpenCV glue plus the full page pipeline: flatten → filter → rotate. */
object PageRenderer {

    fun detectCorners(bitmap: Bitmap): DocumentDetector.Detection? {
        val rgb = toRgb(bitmap)
        try {
            return DocumentDetector.detect(rgb)
        } finally {
            rgb.release()
        }
    }

    fun render(source: Bitmap, edit: PageEdit, maxLongSide: Int): Bitmap {
        val rgb = toRgb(source)
        val warped = PerspectiveCorrector.warp(rgb, edit.quad, edit.preset, maxLongSide)
        rgb.release()
        val filtered = Enhancer.apply(
            warped, edit.filter, edit.brightness, edit.contrast,
            cleanBorders = !edit.preset.isCard,
        )
        warped.release()
        val rotated = rotate(filtered, edit.rotation)
        if (rotated !== filtered) filtered.release()

        val rgba = Mat()
        Imgproc.cvtColor(rotated, rgba, Imgproc.COLOR_RGB2RGBA)
        rotated.release()
        val out = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, out)
        rgba.release()
        return out
    }

    private fun toRgb(bitmap: Bitmap): Mat {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        Utils.bitmapToMat(argb, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }

    private fun rotate(m: Mat, degrees: Int): Mat {
        val code = when (((degrees % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return m
        }
        val out = Mat()
        Core.rotate(m, out, code)
        return out
    }
}
