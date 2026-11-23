package com.scanpdf.app.imaging

import com.scanpdf.app.model.PaperPreset
import com.scanpdf.app.model.Quad
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

/** Flattens the document inside [Quad] into an upright rectangle with the preset's proportions. */
object PerspectiveCorrector {

    fun warp(rgb: Mat, quad: Quad, preset: PaperPreset, maxLongSide: Int): Mat {
        val w = rgb.cols().toDouble()
        val h = rgb.rows().toDouble()
        val p = quad.points().map { Point(it.x * w, it.y * h) }
        val (tl, tr, br, bl) = p

        val measuredW = max(distance(tl, tr), distance(bl, br))
        val measuredH = max(distance(tl, bl), distance(tr, br))
        val (outW, outH) = OutputSizing.compute(measuredW, measuredH, preset, maxLongSide)

        val src = MatOfPoint2f(tl, tr, br, bl)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW.toDouble(), 0.0),
            Point(outW.toDouble(), outH.toDouble()),
            Point(0.0, outH.toDouble()),
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(
            rgb, out, transform, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE,
        )
        src.release()
        dst.release()
        transform.release()
        return out
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
}
