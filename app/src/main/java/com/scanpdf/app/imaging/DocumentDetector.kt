package com.scanpdf.app.imaging

import com.scanpdf.app.model.Pt
import com.scanpdf.app.model.Quad
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the four corners of a document (paper, ID card, business card…) in a photo.
 *
 * Classic computer-vision pipeline, no ML model:
 * downscale → grayscale → blur → several edge/threshold variants → contours →
 * polygon approximation → keep convex quadrilaterals → pick the largest.
 * If no clean quadrilateral exists (e.g. rounded card corners), fall back to the
 * minimum-area rectangle around the largest blob.
 */
object DocumentDetector {

    private const val WORK_SIDE = 800.0
    private const val MIN_AREA_RATIO = 0.10
    private const val MAX_AREA_RATIO = 0.98
    private const val FALLBACK_MIN_AREA_RATIO = 0.20
    /** Corners must be between ~53° and ~127°; perspective makes real corners deviate from 90°. */
    private const val MAX_CORNER_COSINE = 0.6

    /** Pulls corners this many work-scale pixels inwards so no background sneaks into the page edge. */
    private const val INSET_PX = 3.0

    private class Candidate(val points: Array<Point>, val area: Double, val contour: Array<Point>)

    /** @param rgb 3-channel RGB image. @return corners or null when nothing document-like was found. */
    fun detect(rgb: Mat): Quad? {
        val scale = min(1.0, WORK_SIDE / max(rgb.cols(), rgb.rows()))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val w = small.cols()
        val h = small.rows()
        val imageArea = w.toDouble() * h

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        // Closing removes dark text/lines inside the page so the page becomes one flat blob.
        val closed = Mat()
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(gray, closed, Imgproc.MORPH_CLOSE, closeKernel, Point(-1.0, -1.0), 2)

        val candidates = ArrayList<Candidate>()
        val fallbacks = ArrayList<Candidate>()
        val variants = listOf(
            autoCanny(gray),
            autoCanny(closed),
            fixedCanny(closed, 20.0, 60.0),
            otsu(closed),
        )
        for (binary in variants) {
            collect(binary, w, h, candidates, fallbacks)
            binary.release()
        }

        val best = candidates.maxByOrNull { it.area } ?: fallbacks.maxByOrNull { it.area }

        listOf(small, gray, closed, closeKernel).forEach { it.release() }
        if (best == null) return null
        val refined = refineWithLines(best.contour, best.points) ?: best.points
        return toQuad(inset(refined, INSET_PX), w, h)
    }

    private fun collect(
        binary: Mat,
        w: Int,
        h: Int,
        candidates: MutableList<Candidate>,
        fallbacks: MutableList<Candidate>,
    ) {
        val imageArea = w.toDouble() * h
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        // CHAIN_APPROX_NONE keeps every boundary pixel; line refinement needs points along straight edges.
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
        hierarchy.release()

        val largest = contours
            .map { it to Imgproc.contourArea(it) }
            .filter { it.second >= imageArea * MIN_AREA_RATIO }
            .sortedByDescending { it.second }
            .take(8)

        for ((contour, _) in largest) {
            val boundary = contour.toArray()
            val c2f = MatOfPoint2f(*boundary)
            val perimeter = Imgproc.arcLength(c2f, true)

            for (epsilon in doubleArrayOf(0.02, 0.03, 0.05)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, epsilon * perimeter, true)
                val pts = approx.toArray()
                approx.release()
                if (pts.size != 4) continue

                val asInt = MatOfPoint(*pts)
                val convex = Imgproc.isContourConvex(asInt)
                val area = abs(Imgproc.contourArea(asInt))
                asInt.release()

                if (convex && maxCosine(pts) < MAX_CORNER_COSINE &&
                    area >= imageArea * MIN_AREA_RATIO && area <= imageArea * MAX_AREA_RATIO &&
                    !runsAlongBorder(pts, w, h)
                ) {
                    candidates += Candidate(pts, area, boundary)
                    break
                }
            }

            // Fallback for rounded corners / ragged edges.
            val rect = Imgproc.minAreaRect(c2f)
            val rectArea = rect.size.area()
            if (rectArea >= imageArea * FALLBACK_MIN_AREA_RATIO && rectArea <= imageArea * MAX_AREA_RATIO) {
                val box = arrayOfNulls<Point>(4)
                rect.points(box)
                val corners = box.requireNoNulls()
                if (!runsAlongBorder(corners, w, h)) fallbacks += Candidate(corners, rectArea, boundary)
            }
            c2f.release()
        }
        contours.forEach { it.release() }
    }

    private fun autoCanny(gray: Mat, sigma: Double = 0.33): Mat {
        val median = median(gray)
        return fixedCanny(gray, max(0.0, (1 - sigma) * median), min(255.0, (1 + sigma) * median))
    }

    private fun fixedCanny(gray: Mat, low: Double, high: Double): Mat {
        val edges = Mat()
        Imgproc.Canny(gray, edges, low, high)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        kernel.release()
        return edges
    }

    private fun otsu(gray: Mat): Mat {
        val out = Mat()
        Imgproc.threshold(gray, out, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        return out
    }

    private fun median(gray: Mat): Double {
        val bytes = ByteArray((gray.total() * gray.channels()).toInt())
        gray.get(0, 0, bytes)
        val histogram = IntArray(256)
        for (b in bytes) histogram[b.toInt() and 0xFF]++
        val half = bytes.size / 2
        var cumulative = 0
        for (value in 0..255) {
            cumulative += histogram[value]
            if (cumulative > half) return value.toDouble()
        }
        return 127.0
    }

    /**
     * Sharpens rough corners: fits a straight line through the boundary pixels along each side
     * (ignoring the 15% next to each corner) and intersects neighbouring lines. For cards with
     * rounded corners this lands on the true corner, where approxPolyDP would pick a point on the arc.
     * @param quad rough corners in boundary order (either winding)
     * @return refined corners in the same order, or null if a side could not be fitted
     */
    private fun refineWithLines(boundary: Array<Point>, quad: Array<Point>): Array<Point>? {
        val lines = ArrayList<DoubleArray>(4)
        var perimeter = 0.0
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            if (length < 1.0) return null
            perimeter += length
            val tolerance = max(3.0, length * 0.04)
            val alongSide = boundary.filter { p ->
                val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (length * length)
                val distance = abs((p.x - a.x) * dy - (p.y - a.y) * dx) / length
                t in 0.15..0.85 && distance < tolerance
            }
            if (alongSide.size < 5) return null

            val points = MatOfPoint2f(*alongSide.toTypedArray())
            val line = Mat()
            Imgproc.fitLine(points, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            val v = FloatArray(4)
            line.get(0, 0, v)
            points.release()
            line.release()
            lines += doubleArrayOf(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), v[3].toDouble())
        }

        val maxShift = max(8.0, perimeter * 0.03)
        return Array(4) { i ->
            // Corner i sits between side i-1 (quad[i-1]→quad[i]) and side i (quad[i]→quad[i+1]).
            val corner = intersect(lines[(i + 3) % 4], lines[i]) ?: return null
            if (hypot(corner.x - quad[i].x, corner.y - quad[i].y) > maxShift) return null
            corner
        }
    }

    /** Intersection of two lines given as (vx, vy, x0, y0); null when (nearly) parallel. */
    private fun intersect(l1: DoubleArray, l2: DoubleArray): Point? {
        val (vx1, vy1, x1, y1) = l1
        val (vx2, vy2, x2, y2) = l2
        val det = vx1 * vy2 - vy1 * vx2
        if (abs(det) < 1e-6) return null
        val t = ((x2 - x1) * vy2 - (y2 - y1) * vx2) / det
        return Point(x1 + t * vx1, y1 + t * vy1)
    }

    private fun inset(pts: Array<Point>, px: Double): Array<Point> {
        val cx = pts.sumOf { it.x } / pts.size
        val cy = pts.sumOf { it.y } / pts.size
        return Array(pts.size) { i ->
            val p = pts[i]
            val dx = p.x - cx
            val dy = p.y - cy
            val d = hypot(dx, dy)
            if (d <= px) p else Point(p.x - dx / d * px, p.y - dy / d * px)
        }
    }

    /**
     * True when one side of the shape lies on the image border. Such shapes are background
     * regions split by a threshold (e.g. one bright half of an unevenly lit desk), not a document.
     */
    private fun runsAlongBorder(pts: Array<Point>, w: Int, h: Int): Boolean {
        val mx = w * 0.015
        val my = h * 0.015
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            if (a.x < mx && b.x < mx) return true
            if (a.x > w - 1 - mx && b.x > w - 1 - mx) return true
            if (a.y < my && b.y < my) return true
            if (a.y > h - 1 - my && b.y > h - 1 - my) return true
        }
        return false
    }

    /** Largest |cos| of the four interior angles. 0 = perfect rectangle. */
    private fun maxCosine(pts: Array<Point>): Double {
        var worst = 0.0
        for (i in 0 until 4) {
            val p0 = pts[(i + 3) % 4]
            val p1 = pts[i]
            val p2 = pts[(i + 1) % 4]
            val ax = p0.x - p1.x
            val ay = p0.y - p1.y
            val bx = p2.x - p1.x
            val by = p2.y - p1.y
            val denominator = sqrt((ax * ax + ay * ay) * (bx * bx + by * by)) + 1e-10
            worst = max(worst, abs((ax * bx + ay * by) / denominator))
        }
        return worst
    }

    /**
     * Orders corners clockwise starting at the top-left. Sorting by angle around the centroid
     * stays correct even when the document is rotated ~45°, unlike x+y / y−x heuristics.
     */
    internal fun orderClockwise(pts: Array<Point>): Array<Point> {
        val cx = pts.sumOf { it.x } / pts.size
        val cy = pts.sumOf { it.y } / pts.size
        // Image y grows downwards, so ascending atan2 walks clockwise on screen.
        val byAngle = pts.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = byAngle.indices.minBy { byAngle[it].x + byAngle[it].y }
        return Array(4) { byAngle[(start + it) % 4] }
    }

    private fun toQuad(pts: Array<Point>, w: Int, h: Int): Quad {
        val o = orderClockwise(pts)
        fun n(p: Point) = Pt(
            (p.x / w).toFloat().coerceIn(0f, 1f),
            (p.y / h).toFloat().coerceIn(0f, 1f),
        )
        return Quad(n(o[0]), n(o[1]), n(o[2]), n(o[3]))
    }
}
