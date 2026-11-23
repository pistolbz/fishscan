package com.scanpdf.app.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/** Builds fake "photos" of documents so the detector can be tested without real pictures. */
object SyntheticScenes {

    fun background(w: Int, h: Int, color: Scalar, noise: Double = 25.0, seed: Long = 7): Mat {
        val img = Mat(h, w, CvType.CV_8UC3, color)
        val n = Mat(h, w, CvType.CV_8UC3)
        Core.setRNGSeed(seed.toInt())
        Core.randu(n, 0.0, noise)
        Core.subtract(img, Scalar(noise / 2, noise / 2, noise / 2), img)
        Core.add(img, n, img)
        n.release()
        return img
    }

    /** Fills [corners] (clockwise from top-left) with paper colour and draws text lines in perspective. */
    fun drawPaper(img: Mat, corners: List<Point>, paper: Scalar, docW: Double, docH: Double, withText: Boolean = true) {
        val poly = MatOfPoint(*corners.toTypedArray())
        Imgproc.fillConvexPoly(img, poly, paper)
        poly.release()
        if (!withText) return

        val src = MatOfPoint2f(Point(0.0, 0.0), Point(docW, 0.0), Point(docW, docH), Point(0.0, docH))
        val dst = MatOfPoint2f(*corners.toTypedArray())
        val h = Imgproc.getPerspectiveTransform(src, dst)
        var y = docH * 0.12
        while (y < docH * 0.88) {
            val lineW = if ((y / docH * 20).toInt() % 3 == 2) docW * 0.5 else docW * 0.8
            val rect = MatOfPoint2f(
                Point(docW * 0.1, y), Point(docW * 0.1 + lineW, y),
                Point(docW * 0.1 + lineW, y + docH * 0.012), Point(docW * 0.1, y + docH * 0.012),
            )
            val out = MatOfPoint2f()
            Core.perspectiveTransform(rect, out, h)
            val pts = MatOfPoint(*out.toArray())
            Imgproc.fillConvexPoly(img, pts, Scalar(40.0, 40.0, 45.0))
            pts.release(); out.release(); rect.release()
            y += docH * 0.045
        }
        src.release(); dst.release(); h.release()
    }

    /** Darkens the image from left (factor [from]) to right (factor [to]), like a hand's shadow. */
    fun shadeHorizontally(img: Mat, from: Double, to: Double) {
        val f = Mat()
        img.convertTo(f, CvType.CV_32FC3)
        val ramp = Mat(img.rows(), img.cols(), CvType.CV_32FC3)
        val row = FloatArray(img.cols() * 3)
        for (x in 0 until img.cols()) {
            val v = (from + (to - from) * x / (img.cols() - 1)).toFloat()
            row[x * 3] = v; row[x * 3 + 1] = v; row[x * 3 + 2] = v
        }
        for (y in 0 until img.rows()) ramp.put(y, 0, row)
        Core.multiply(f, ramp, f)
        f.convertTo(img, CvType.CV_8UC3)
        f.release(); ramp.release()
    }

    fun cameraBlur(img: Mat) = Imgproc.GaussianBlur(img, img, Size(3.0, 3.0), 0.0)

    /** Rounded rectangle outline points (for cards), rotated by [angleDeg] around [center]. */
    fun roundedRect(center: Point, w: Double, h: Double, radius: Double, angleDeg: Double): Pair<List<Point>, List<Point>> {
        val a = Math.toRadians(angleDeg)
        fun rot(x: Double, y: Double) = Point(center.x + x * cos(a) - y * sin(a), center.y + x * sin(a) + y * cos(a))
        val hw = w / 2
        val hh = h / 2
        val arcs = listOf(
            Triple(-hw + radius, -hh + radius, 180.0),
            Triple(hw - radius, -hh + radius, 270.0),
            Triple(hw - radius, hh - radius, 0.0),
            Triple(-hw + radius, hh - radius, 90.0),
        )
        val outline = ArrayList<Point>()
        for ((cx, cy, start) in arcs) {
            for (k in 0..8) {
                val t = Math.toRadians(start + 90.0 * k / 8)
                outline += rot(cx + radius * cos(t), cy + radius * sin(t))
            }
        }
        val sharp = listOf(rot(-hw, -hh), rot(hw, -hh), rot(hw, hh), rot(-hw, hh))
        return outline to sharp
    }
}
