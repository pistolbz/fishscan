package com.scanpdf.app.imaging

import com.scanpdf.app.model.FilterType
import com.scanpdf.app.model.PaperPreset
import com.scanpdf.app.model.Quad
import com.scanpdf.app.pdf.JpegPdfWriter
import com.scanpdf.app.pdf.PageLayout
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * End-to-end run of the real pipeline on synthetic photos: detect → flatten → enhance → PDF.
 * Writes every stage to app/build/pipeline-demo/ so a human can eyeball the results.
 */
class PipelineDemoTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    private val outDir = File("build/pipeline-demo").apply { mkdirs() }

    private fun save(name: String, rgb: Mat) {
        val bgr = Mat()
        Imgproc.cvtColor(rgb, bgr, Imgproc.COLOR_RGB2BGR)
        Imgcodecs.imwrite(File(outDir, name).path, bgr)
        bgr.release()
    }

    private fun jpeg(rgb: Mat): ByteArray {
        val bgr = Mat()
        Imgproc.cvtColor(rgb, bgr, Imgproc.COLOR_RGB2BGR)
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", bgr, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85))
        bgr.release()
        return buf.toArray()
    }

    private fun overlay(rgb: Mat, quad: Quad): Mat {
        val out = rgb.clone()
        val pts = quad.points().map { Point(it.x * rgb.cols().toDouble(), it.y * rgb.rows().toDouble()) }
        Imgproc.polylines(out, listOf(MatOfPoint(*pts.toTypedArray())), true, Scalar(66.0, 165.0, 245.0), 6)
        pts.forEach { Imgproc.circle(out, it, 18, Scalar(66.0, 165.0, 245.0), -1) }
        return out
    }

    @Test
    fun a4AndIdCardToPdf() {
        // Scene 1: A4 on a desk, photographed at an angle, shadow on the right.
        val a4Photo = SyntheticScenes.background(1600, 2000, Scalar(90.0, 70.0, 55.0))
        SyntheticScenes.drawPaper(
            a4Photo,
            listOf(Point(330.0, 260.0), Point(1290.0, 330.0), Point(1400.0, 1730.0), Point(210.0, 1800.0)),
            Scalar(235.0, 235.0, 230.0), 210.0, 297.0,
        )
        SyntheticScenes.shadeHorizontally(a4Photo, 1.0, 0.6)
        SyntheticScenes.cameraBlur(a4Photo)

        // Scene 2: ID card with rounded corners, rotated, on a dark cloth.
        val cardPhoto = SyntheticScenes.background(1600, 1200, Scalar(60.0, 60.0, 70.0))
        val (outline, _) = SyntheticScenes.roundedRect(Point(800.0, 600.0), 856.0, 540.0, 30.0, 12.0)
        Imgproc.fillPoly(cardPhoto, listOf(MatOfPoint(*outline.toTypedArray())), Scalar(210.0, 225.0, 240.0))
        Imgproc.putText(cardPhoto, "CAN CUOC", Point(560.0, 520.0), Imgproc.FONT_HERSHEY_SIMPLEX, 2.0, Scalar(30.0, 30.0, 120.0), 5)
        SyntheticScenes.cameraBlur(cardPhoto)

        val pdf = File(outDir, "demo.pdf")
        JpegPdfWriter(pdf.outputStream()).use { writer ->
            for ((name, photo, preset) in listOf(
                Triple("a4", a4Photo, PaperPreset.A4),
                Triple("card", cardPhoto, PaperPreset.ID_CARD),
            )) {
                val quad = DocumentDetector.detect(photo)
                assertNotNull("$name: corners not found", quad)
                save("${name}_1_detected.jpg", overlay(photo, quad!!))

                val flat = PerspectiveCorrector.warp(photo, quad, preset, 2400)
                save("${name}_2_flattened.jpg", flat)
                val enhanced = Enhancer.apply(flat, FilterType.ENHANCE, 0f, 0f)
                save("${name}_3_enhanced.jpg", enhanced)

                val layout = PageLayout.compute(preset, enhanced.cols(), enhanced.rows())
                writer.addPage(jpeg(enhanced), layout.pageWidth, layout.pageHeight, layout.image)
                flat.release()
                enhanced.release()
            }
        }
        println("Pipeline demo written to ${outDir.absolutePath}")
    }
}
