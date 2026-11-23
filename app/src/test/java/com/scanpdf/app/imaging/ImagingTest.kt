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

        assertCorners(corners, w, h, DocumentDetector.detect(img))
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

        assertCorners(corners, w, h, DocumentDetector.detect(img))
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
        assertCorners(sharp, w, h, DocumentDetector.detect(img), tolerance = 0.008)
        img.release()
    }

    @Test
    fun returnsNullWhenThereIsNoDocument() {
        val img = Mat(1200, 1600, CvType.CV_8UC3)
        // Smooth gradient: no edges at all.
        val row = ByteArray(1600 * 3) { (40 + (it / 3) * 150 / 1600).toByte() }
        for (y in 0 until 1200) img.put(y, 0, row)
        assertNull(DocumentDetector.detect(img))
        img.release()
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

        val a4 = PerspectiveCorrector.warp(img, quad, PaperPreset.A4, 2400)
        assertEquals(297.0 / 210.0, a4.rows().toDouble() / a4.cols(), 0.01)
        assertEquals(2339, a4.rows()) // 297 mm at 200 DPI
        a4.release()

        val card = PerspectiveCorrector.warp(img, quad, PaperPreset.ID_CARD, 2400)
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
        assertTrue("lit paper should be white, was $litPaper", litPaper > 235)
        assertTrue("shadowed paper should be white too, was $shadowPaper", shadowPaper > 235)
        assertTrue("text in shadow should stay dark, was $shadowText", shadowText < 110)
        assertTrue("before: shadow was visible", abs(Core.mean(img.submat(Rect(800, 120, 100, 15))).`val`[0] - 225) > 60)

        val bw = Enhancer.apply(img, FilterType.BW, 0f, 0f)
        assertEquals(255.0, Core.mean(bw.submat(Rect(800, 120, 100, 15))).`val`[0], 1.0)
        assertTrue(Core.mean(bw.submat(Rect(800, 101, 100, 2))).`val`[0] < 60)

        listOf(img, out, bw).forEach { it.release() }
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
