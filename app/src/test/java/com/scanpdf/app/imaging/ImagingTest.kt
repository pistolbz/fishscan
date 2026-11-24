package com.scanpdf.app.imaging

import com.scanpdf.app.model.FilterType
import com.scanpdf.app.model.PaperPreset
import com.scanpdf.app.model.Pt
import com.scanpdf.app.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Runs the real detector / warp / filter code on synthetic photos using desktop OpenCV
 * (org.openpnp:opencv ships Windows/Linux/macOS natives).
 */
class ImagingTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        /** Max corner error as a fraction of the image (0.01 = 16 px on a 1600 px photo). */
        private const val TOLERANCE = 0.01
    }

    /** Corners only when the detector found a real document outline (not a deskew-only result). */
    private fun documentQuad(img: Mat): Quad? =
        DocumentDetector.detect(img)?.takeIf { it.kind == DocumentDetector.Detection.Kind.DOCUMENT }?.quad

    private fun assertCorners(expected: List<Point>, w: Int, h: Int, actual: Quad?, tolerance: Double = TOLERANCE) {
        assertNotNull("no document found", actual)
        val exp = expected.map { Pt((it.x / w).toFloat(), (it.y / h).toFloat()) }
        val names = listOf("top-left", "top-right", "bottom-right", "bottom-left")
        actual!!.points().forEachIndexed { i, p ->
            val d = hypot((p.x - exp[i].x).toDouble(), (p.y - exp[i].y).toDouble())
            assertTrue("${names[i]}: expected ${exp[i]} got $p (off by $d)", d < tolerance)
        }
    }

    @Test
    fun detectsA4PhotographedAtAnAngle_withShadow() {
        val w = 1600
        val h = 2000
        val img = SyntheticScenes.background(w, h, Scalar(90.0, 70.0, 55.0)) // wooden desk
        val corners = listOf(Point(330.0, 260.0), Point(1290.0, 330.0), Point(1400.0, 1730.0), Point(210.0, 1800.0))
        SyntheticScenes.drawPaper(img, corners, Scalar(235.0, 235.0, 230.0), 210.0, 297.0)
        SyntheticScenes.shadeHorizontally(img, 1.0, 0.6)
        SyntheticScenes.cameraBlur(img)

        assertCorners(corners, w, h, documentQuad(img))
        img.release()
    }

    @Test
    fun detectsWhitePaperOnLightBackground_lowContrast() {
        val w = 1200
        val h = 1600
        val img = SyntheticScenes.background(w, h, Scalar(178.0, 178.0, 172.0), noise = 16.0)
        val corners = listOf(Point(200.0, 180.0), Point(1010.0, 220.0), Point(1050.0, 1420.0), Point(150.0, 1390.0))
        SyntheticScenes.drawPaper(img, corners, Scalar(232.0, 232.0, 228.0), 210.0, 297.0)
        SyntheticScenes.cameraBlur(img)

        assertCorners(corners, w, h, documentQuad(img))
        img.release()
    }

    @Test
    fun detectsRotatedIdCardWithRoundedCorners() {
        val w = 1600
        val h = 1200
        val img = SyntheticScenes.background(w, h, Scalar(60.0, 60.0, 70.0))
        // 85.6 x 54 mm card, ~10 px/mm, 3 mm corner radius, rotated 12°.
        val (outline, sharp) = SyntheticScenes.roundedRect(Point(800.0, 600.0), 856.0, 540.0, 30.0, 12.0)
        val poly = MatOfPoint(*outline.toTypedArray())
        Imgproc.fillPoly(img, listOf(poly), Scalar(210.0, 225.0, 240.0))
        poly.release()
        SyntheticScenes.cameraBlur(img)

        // Rounded corners: the result must be the true (sharp) corner, not a point on the arc.
        assertCorners(sharp, w, h, documentQuad(img), tolerance = 0.008)
        img.release()
    }

    @Test
    fun returnsNullWhenThereIsNoDocument() {
        val img = Mat(1200, 1600, CvType.CV_8UC3)
        // Smooth gradient: no edges at all.
        val row = ByteArray(1600 * 3) { (40 + (it / 3) * 150 / 1600).toByte() }
        for (y in 0 until 1200) img.put(y, 0, row)
        // May trim the dark end as "background", but must never claim a document outline.
        assertTrue(DocumentDetector.detect(img)?.kind != DocumentDetector.Detection.Kind.DOCUMENT)
        img.release()
    }

    @Test
    fun trimsDarkStripAtTheEdgeOfAPageThatFillsTheFrame() {
        // Like real page 12: the page overflows the photo except on the right, where the dark
        // keyboard shows beyond the page edge. That edge is tilted with the page (2°).
        val w = 1200
        val h = 1600
        val img = SyntheticScenes.background(w, h, Scalar(45.0, 45.0, 50.0))
        val a = Math.toRadians(2.0)
        val corners = listOf(-0.8 to -0.8, 0.44 to -0.8, 0.44 to 0.8, -0.8 to 0.8).map { (fx, fy) ->
            val x = fx * w
            val y = fy * h
            Point(w / 2.0 + x * kotlin.math.cos(a) - y * kotlin.math.sin(a), h / 2.0 + x * kotlin.math.sin(a) + y * kotlin.math.cos(a))
        }
        SyntheticScenes.drawPaper(img, corners, Scalar(232.0, 232.0, 228.0), 210.0, 297.0)
        SyntheticScenes.cameraBlur(img)
        val detection = DocumentDetector.detect(img)
        assertEquals(DocumentDetector.Detection.Kind.DESKEW, detection?.kind)

        val out = PerspectiveCorrector.warp(img, detection!!.quad, PaperPreset.AUTO, 1600)
        val rightEdge = Core.mean(out.submat(Rect(out.cols() - 8, 0, 8, out.rows()))).`val`[0]
        assertTrue("dark strip should be trimmed, right edge brightness $rightEdge", rightEdge > 150)
        img.release()
        out.release()
    }

    @Test
    fun orderClockwiseHandlesDiamondShape() {
        // Document rotated 45°: x+y / y−x heuristics would produce duplicate corners here.
        val pts = arrayOf(Point(100.0, 0.0), Point(0.0, 100.0), Point(100.0, 200.0), Point(200.0, 100.0))
        val o = DocumentDetector.orderClockwise(pts)
        assertEquals(4, o.toSet().size)
        // Top and left tie on x+y; the first one in clockwise order (top) is taken as start.
        assertEquals(Point(100.0, 0.0), o[0])
        assertEquals(Point(200.0, 100.0), o[1])
        assertEquals(Point(100.0, 200.0), o[2])
        assertEquals(Point(0.0, 100.0), o[3])
    }

    @Test
    fun warpProducesA4ProportionsAndCardProportions() {
        val img = SyntheticScenes.background(1600, 2000, Scalar(90.0, 70.0, 55.0))
        val quad = Quad(Pt(0.2f, 0.13f), Pt(0.8f, 0.16f), Pt(0.87f, 0.86f), Pt(0.13f, 0.9f))

        val a4 = PerspectiveCorrector.warp(img, quad, PaperPreset.A4, 4000)
        assertEquals(297.0 / 210.0, a4.rows().toDouble() / a4.cols(), 0.01)
        assertEquals(3508, a4.rows()) // 297 mm at 300 DPI
        a4.release()

        val card = PerspectiveCorrector.warp(img, quad, PaperPreset.ID_CARD, 4000)
        assertEquals(85.6 / 53.98, card.rows().toDouble() / card.cols(), 0.01)
        card.release()
        img.release()
    }

    @Test
    fun enhanceRemovesShadowAndKeepsTextDark() {
        val w = 1000
        val h = 1400
        val img = Mat(h, w, CvType.CV_8UC3, Scalar(225.0, 222.0, 215.0)) // already flattened page
        for (y in 100 until 1300 step 40) Imgproc.rectangle(img, Point(80.0, y.toDouble()), Point(920.0, y + 3.0), Scalar(40.0, 40.0, 40.0), -1)
        SyntheticScenes.shadeHorizontally(img, 1.0, 0.55)

        val out = Enhancer.apply(img, FilterType.ENHANCE, 0f, 0f)
        val litPaper = Core.mean(out.submat(Rect(100, 120, 100, 15))).`val`[0]
        val shadowPaper = Core.mean(out.submat(Rect(800, 120, 100, 15))).`val`[0]
        val shadowText = Core.mean(out.submat(Rect(800, 101, 100, 2))).`val`[0]
        assertTrue("lit paper should be pure white, was $litPaper", litPaper > 250)
        assertTrue("shadowed paper should be pure white too, was $shadowPaper", shadowPaper > 250)
        // Scanner-like: print must come out black, not grey (v0.1 gave ~90 on real photos).
        assertTrue("text in shadow should be black, was $shadowText", shadowText < 30)
        assertTrue("before: shadow was visible", abs(Core.mean(img.submat(Rect(800, 120, 100, 15))).`val`[0] - 225) > 60)

        val bw = Enhancer.apply(img, FilterType.BW, 0f, 0f)
        assertEquals(255.0, Core.mean(bw.submat(Rect(800, 120, 100, 15))).`val`[0], 1.0)
        assertTrue(Core.mean(bw.submat(Rect(800, 101, 100, 2))).`val`[0] < 60)

        listOf(img, out, bw).forEach { it.release() }
    }

    /** Page larger than the photo (no corners visible), text tilted 3°: like most real test photos. */
    private fun pageFillingFrame(w: Int, h: Int, tiltDeg: Double): Mat {
        val img = SyntheticScenes.background(w, h, Scalar(70.0, 60.0, 50.0))
        val a = Math.toRadians(tiltDeg)
        val corners = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
            val x = sx * w * 0.65
            val y = sy * h * 0.65
            Point(w / 2.0 + x * kotlin.math.cos(a) - y * kotlin.math.sin(a), h / 2.0 + x * kotlin.math.sin(a) + y * kotlin.math.cos(a))
        }
        SyntheticScenes.drawPaper(img, corners, Scalar(232.0, 232.0, 228.0), 210.0, 297.0)
        SyntheticScenes.cameraBlur(img)
        return img
    }

    @Test
    fun straightensTiltedPageThatFillsTheFrame() {
        val img = pageFillingFrame(1200, 1600, 3.0)
        val detection = DocumentDetector.detect(img)
        assertNotNull("tilted text should be detected", detection)
        assertEquals(DocumentDetector.Detection.Kind.DESKEW, detection!!.kind)
        val q = detection.quad
        val topAngle = Math.toDegrees(kotlin.math.atan2(((q.tr.y - q.tl.y) * 1600).toDouble(), ((q.tr.x - q.tl.x) * 1200).toDouble()))
        assertEquals("deskew angle", 3.0, topAngle, 0.5)

        // After flattening with that quad the text lines must be level.
        val out = PerspectiveCorrector.warp(img, q, PaperPreset.AUTO, 1600)
        val gray = Mat()
        Imgproc.cvtColor(out, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.resize(gray, gray, org.opencv.core.Size(), 800.0 / out.rows(), 800.0 / out.rows(), Imgproc.INTER_AREA)
        val residual = DocumentDetector.estimateSkewDegrees(gray) ?: 0.0
        assertTrue("residual tilt $residual°", abs(residual) < 0.5)
        listOf(img, out, gray).forEach { it.release() }
    }

    @Test
    fun doesNotCropATableInsideAPageThatFillsTheFrame() {
        // Regression for real page 13: with the page outline out of view, a bordered table on the
        // page was taken for the document and cropped.
        val img = pageFillingFrame(1200, 1600, 0.0)
        Imgproc.rectangle(img, Point(200.0, 450.0), Point(1000.0, 1150.0), Scalar(30.0, 30.0, 30.0), 5)
        val detection = DocumentDetector.detect(img)
        assertTrue("table must not be cropped as a document", detection?.kind != DocumentDetector.Detection.Kind.DOCUMENT)
        img.release()
    }

    @Test
    fun detectsPageCutOffAtTheBottomEdge() {
        val w = 1200
        val h = 1600
        val img = SyntheticScenes.background(w, h, Scalar(80.0, 65.0, 50.0))
        // Bottom corners lie below the photo; only top, left and right edges are visible.
        val corners = listOf(Point(220.0, 200.0), Point(1000.0, 230.0), Point(1060.0, 1900.0), Point(160.0, 1880.0))
        SyntheticScenes.drawPaper(img, corners, Scalar(235.0, 235.0, 230.0), 210.0, 297.0)
        SyntheticScenes.cameraBlur(img)

        val q = documentQuad(img)
        assertNotNull("page touching one border should still be found", q)
        fun xAtBottom(a: Point, b: Point) = a.x + (b.x - a.x) * ((h - 1) - a.y) / (b.y - a.y)
        val expected = listOf(
            corners[0], corners[1],
            Point(xAtBottom(corners[1], corners[2]), h - 1.0), Point(xAtBottom(corners[0], corners[3]), h - 1.0),
        )
        assertCorners(expected, w, h, q)
        img.release()
    }

    @Test
    fun cleansDeskRemnantsAtPageEdgesButKeepsTextTouchingTheEdge() {
        // Exactly the artefacts seen in the real v0.2.1 PDF after the tone curve: white page,
        // a solid black desk wedge in a corner, a thin black sliver along the top edge, and a text
        // line cut off at the left edge of a tightly framed photo.
        val w = 2700
        val h = 3600
        val img = Mat(h, w, CvType.CV_8UC3, Scalar.all(255.0))
        val black = Scalar.all(0.0)
        val wedge = MatOfPoint(Point(0.0, 0.0), Point(120.0, 0.0), Point(0.0, 900.0))
        Imgproc.fillConvexPoly(img, wedge, black)
        val sliver = MatOfPoint(Point(700.0, 0.0), Point(2400.0, 0.0), Point(2400.0, 6.0), Point(700.0, 22.0))
        Imgproc.fillConvexPoly(img, sliver, black)
        // Glyph-sized blocks (large ~14 pt letters); the line at y=1970 starts right at x=0.
        for (y in 1200 until 3300 step 110) {
            var x = if (y == 1970) 0 else 300
            while (x < 2400) {
                Imgproc.rectangle(img, Point(x.toDouble(), y.toDouble()), Point(x + 28.0, y + 40.0), black, -1)
                x += 45
            }
        }

        Enhancer.whitenBorderBackground(img)
        fun mean(r: Rect) = Core.mean(img.submat(r)).`val`[0]
        assertTrue("corner wedge should become white, was ${mean(Rect(0, 0, 40, 300))}", mean(Rect(0, 0, 40, 300)) > 245)
        assertTrue("top sliver should become white, was ${mean(Rect(800, 0, 1500, 5))}", mean(Rect(800, 0, 1500, 5)) > 245)
        assertTrue("letters touching the left edge must stay, was ${mean(Rect(0, 1970, 28, 40))}", mean(Rect(0, 1970, 28, 40)) < 30)
        assertTrue("normal text must stay, was ${mean(Rect(300, 1200, 28, 40))}", mean(Rect(300, 1200, 28, 40)) < 30)
        listOf(img, wedge, sliver).forEach { it.release() }
    }

    @Test
    fun brightnessSliderNeverBreaksThreeChannelImage() {
        val img = Mat(100, 100, CvType.CV_8UC3, Scalar(120.0, 120.0, 120.0))
        val out = Enhancer.apply(img, FilterType.ORIGINAL, 0.3f, 0.2f)
        assertEquals(3, out.channels())
        assertTrue(Core.mean(out).`val`[0] > 120)
        img.release(); out.release()
    }
}
