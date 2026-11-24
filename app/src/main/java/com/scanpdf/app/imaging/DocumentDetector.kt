package com.scanpdf.app.imaging

import com.scanpdf.app.model.Pt
import com.scanpdf.app.model.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the four corners of a document (paper, ID card, business card…) in a photo.
 *
 * Classic computer-vision pipeline, no ML model:
 * downscale → grayscale → blur → several edge/threshold variants → contours →
 * polygon approximation → keep convex quadrilaterals that are brighter than their
 * surroundings → pick the largest. If no clean quadrilateral exists (e.g. rounded card
 * corners), fall back to the minimum-area rectangle around the largest blob.
 *
 * When the page fills the whole photo (no corners visible) there is no outline to find;
 * the detector then measures the tilt of the text lines and returns a quad that only
 * straightens the page ([Detection.Kind.DESKEW]).
 */
object DocumentDetector {

    private const val WORK_SIDE = 800.0
    private const val MIN_AREA_RATIO = 0.10
    private const val MAX_AREA_RATIO = 0.98
    private const val FALLBACK_MIN_AREA_RATIO = 0.20
    /** Corners must be between ~53° and ~127°; perspective makes real corners deviate from 90°. */
    private const val MAX_CORNER_COSINE = 0.6
    /** A page/card must be at least this much brighter (0-255) than the ring just outside it. */
    private const val MIN_PAGE_CONTRAST = 10.0
    /** Smaller tilts are not worth a resample. */
    private const val MIN_DESKEW_DEGREES = 0.3
    private const val MAX_DESKEW_DEGREES = 20.0

    /** Pulls corners this many work-scale pixels inwards so no background sneaks into the page edge. */
    private const val INSET_PX = 3.0

    private class Candidate(val points: Array<Point>, val area: Double, val contour: Array<Point>)

    data class Detection(val quad: Quad, val kind: Kind) {
        enum class Kind {
            /** All four corners of the document were found. */
            DOCUMENT,
            /**
             * No outline (page fills the photo); the quad only straightens tilted text lines and
             * trims background strips at the photo's edges.
             */
            DESKEW,
        }
    }

    /** @param rgb 3-channel RGB image. @return null when neither a document nor tilted text was found. */
    fun detect(rgb: Mat): Detection? {
        val scale = min(1.0, WORK_SIDE / max(rgb.cols(), rgb.rows()))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val w = small.cols()
        val h = small.rows()

        val sharpGray = Mat()
        Imgproc.cvtColor(small, sharpGray, Imgproc.COLOR_RGB2GRAY)
        val gray = Mat()
        Imgproc.GaussianBlur(sharpGray, gray, Size(5.0, 5.0), 0.0)

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

        // A real page or card is brighter than what surrounds it. This rejects text blocks, tables
        // and pictures on a page that fills the frame, which would otherwise be cropped as "the document".
        val plausible = { c: Candidate -> brighterThanSurroundings(gray, c.points) != false }
        val best = candidates.filter(plausible).maxByOrNull { it.area }
            ?: fallbacks.filter(plausible).maxByOrNull { it.area }

        val result = if (best != null) {
            val refined = refineWithLines(best.contour, best.points) ?: best.points
            Detection(toQuad(inset(refined, INSET_PX), w, h), Detection.Kind.DOCUMENT)
        } else {
            straightenQuad(sharpGray, estimateSkewDegrees(sharpGray) ?: 0.0)?.let { Detection(it, Detection.Kind.DESKEW) }
        }

        listOf(small, sharpGray, gray, closed, closeKernel).forEach { it.release() }
        return result
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
                    sidesAlongBorder(pts, w, h) < 2
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
                if (sidesAlongBorder(corners, w, h) < 2) fallbacks += Candidate(corners, rectArea, boundary)
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
     * Number of sides lying on the image border. One is fine (page slightly cut off at one edge);
     * two or more means a slab of the frame itself, e.g. the bright half of an unevenly lit desk.
     */
    private fun sidesAlongBorder(pts: Array<Point>, w: Int, h: Int): Int {
        val mx = w * 0.015
        val my = h * 0.015
        var count = 0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            if ((a.x < mx && b.x < mx) || (a.x > w - 1 - mx && b.x > w - 1 - mx) ||
                (a.y < my && b.y < my) || (a.y > h - 1 - my && b.y > h - 1 - my)
            ) {
                count++
            }
        }
        return count
    }

    /**
     * Compares the brightness just inside the shape with a ring just outside it.
     * @return true/false, or null when too little of the outside ring is inside the photo to judge
     */
    private fun brighterThanSurroundings(gray: Mat, pts: Array<Point>): Boolean? {
        val cx = pts.sumOf { it.x } / pts.size
        val cy = pts.sumOf { it.y } / pts.size
        fun scaled(f: Double) = pts.map { Point(cx + (it.x - cx) * f, cy + (it.y - cy) * f) }
        fun ring(from: Double, to: Double): Mat {
            val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
            Imgproc.fillConvexPoly(mask, MatOfPoint(*scaled(to).toTypedArray()), Scalar(255.0))
            Imgproc.fillConvexPoly(mask, MatOfPoint(*scaled(from).toTypedArray()), Scalar(0.0))
            return mask
        }
        val outer = ring(1.03, 1.10)
        val inner = ring(0.90, 0.97)
        val expectedOuter = polygonArea(scaled(1.10)) - polygonArea(scaled(1.03))
        val result = if (Core.countNonZero(outer) < expectedOuter * 0.15) {
            null
        } else {
            Core.mean(gray, inner).`val`[0] - Core.mean(gray, outer).`val`[0] >= MIN_PAGE_CONTRAST
        }
        outer.release()
        inner.release()
        return result
    }

    private fun polygonArea(p: List<Point>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2
    }

    /**
     * Tilt of the text lines in degrees (positive = lines descend to the right), or null when
     * there are too few text lines or the page is already straight. Letters are joined into
     * line blobs with a wide horizontal closing; each long thin blob votes with its direction.
     */
    internal fun estimateSkewDegrees(gray: Mat): Double? {
        val w = gray.cols()
        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(9.0, w / 40.0), 1.0))
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(ink, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        ink.release()

        val votes = ArrayList<Pair<Double, Double>>() // angle to weight (blob length)
        for (contour in contours) {
            val c2f = MatOfPoint2f(*contour.toArray())
            val box = arrayOfNulls<Point>(4)
            Imgproc.minAreaRect(c2f).points(box)
            c2f.release()
            contour.release()
            val b = box.requireNoNulls()
            val e1 = hypot(b[1].x - b[0].x, b[1].y - b[0].y)
            val e2 = hypot(b[2].x - b[1].x, b[2].y - b[1].y)
            val (from, to) = if (e1 >= e2) b[0] to b[1] else b[1] to b[2]
            val long = max(e1, e2)
            val short = max(1.0, min(e1, e2))
            if (long < w * 0.15 || long / short < 6) continue
            var angle = Math.toDegrees(atan2(to.y - from.y, to.x - from.x))
            if (angle > 90) angle -= 180
            if (angle <= -90) angle += 180
            if (abs(angle) <= MAX_DESKEW_DEGREES) votes += angle to long
        }
        if (votes.size < 3) return null

        // Length-weighted median: robust against the odd diagonal blob.
        val sorted = votes.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2
        var acc = 0.0
        val median = sorted.first { acc += it.second; acc >= half }.first
        return median.takeIf { abs(it) >= MIN_DESKEW_DEGREES }
    }

    /**
     * For a page that fills the photo: the largest rectangle with the photo's proportions rotated by
     * [degrees] that fits inside the photo, then trimmed of dark background strips along its edges
     * (desk, keyboard…). A strip line is background when most of it is much darker than the paper;
     * a column of text never is. Each side is trimmed by at most 8%.
     * @return null when there is nothing to straighten or trim
     */
    private fun straightenQuad(gray: Mat, degrees: Double): Quad? {
        val w = gray.cols()
        val h = gray.rows()
        val a = Math.toRadians(degrees)
        val ca = cos(a)
        val sa = sin(a)
        val fit = min(w / (w * abs(ca) + h * abs(sa)), h / (w * abs(sa) + h * abs(ca)))
        val left0 = -w * fit / 2
        val top0 = -h * fit / 2
        var left = left0
        var right = -left0
        var top = top0
        var bottom = -top0

        val px = ByteArray(w * h)
        gray.get(0, 0, px)
        // (x, y) in the rotated frame, origin at the photo centre.
        fun at(x: Double, y: Double): Int {
            val ix = (w / 2.0 + x * ca - y * sa).toInt().coerceIn(0, w - 1)
            val iy = (h / 2.0 + x * sa + y * ca).toInt().coerceIn(0, h - 1)
            return px[iy * w + ix].toInt() and 0xFF
        }

        // Paper brightness: a bright percentile of a grid over the central area.
        val grid = ArrayList<Int>(1600)
        for (i in 0 until 40) for (j in 0 until 40) {
            grid += at(left0 * 0.8 * (1 - i / 19.5), top0 * 0.8 * (1 - j / 19.5))
        }
        val paper = grid.sorted()[(grid.size * 0.7).toInt()]
        val darkBelow = paper * 0.6

        fun isBackground(x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
            var dark = 0
            for (k in 0 until 120) {
                val t = k / 119.0
                if (at(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t) < darkBelow) dark++
            }
            return dark > 72 // > 60 % of the line
        }

        val maxX = (right - left) * 0.08
        val maxY = (bottom - top) * 0.08
        var t = 0.0
        while (t < maxX && isBackground(left0 + t, top0, left0 + t, -top0)) t += 1.0
        left += t
        t = 0.0
        while (t < maxX && isBackground(-left0 - t, top0, -left0 - t, -top0)) t += 1.0
        right -= t
        t = 0.0
        while (t < maxY && isBackground(left, top0 + t, right, top0 + t)) t += 1.0
        top += t
        t = 0.0
        while (t < maxY && isBackground(left, -top0 - t, right, -top0 - t)) t += 1.0
        bottom -= t

        val trimmed = left > left0 || right < -left0 || top > top0 || bottom < -top0
        if (degrees == 0.0 && !trimmed) return null
        fun corner(x: Double, y: Double) = Point(w / 2.0 + x * ca - y * sa, h / 2.0 + x * sa + y * ca)
        return toQuad(arrayOf(corner(left, top), corner(right, top), corner(right, bottom), corner(left, bottom)), w, h)
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
