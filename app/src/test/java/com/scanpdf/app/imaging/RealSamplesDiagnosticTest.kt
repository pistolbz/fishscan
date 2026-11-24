package com.scanpdf.app.imaging

import com.scanpdf.app.model.FilterType
import com.scanpdf.app.model.PaperPreset
import com.scanpdf.app.model.Quad
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Locale

/**
 * Diagnostic run of the detector on REAL photos kept outside the repo (they may contain private
 * documents). Skipped unless the SCANPDF_SAMPLES environment variable points to a folder of images:
 *
 *   SCANPDF_SAMPLES=/path/to/photos ./gradlew :app:testDebugUnitTest --tests '*RealSamplesDiagnosticTest'
 *
 * Prints the detected corners for each photo and writes overlays to app/build/real-samples/.
 * Never copy those photos or overlays into the repository.
 */
class RealSamplesDiagnosticTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    @Test
    fun detectOnRealPhotos() {
        val dir = System.getenv("SCANPDF_SAMPLES")?.let(::File)
        assumeTrue("SCANPDF_SAMPLES not set", dir != null && dir.isDirectory)
        val out = File("build/real-samples").apply { mkdirs() }

        val photos = dir!!.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }!!.sortedBy { it.name }
        var found = 0
        for (photo in photos) {
            val bgr = Imgcodecs.imread(photo.path)
            val rgb = Mat()
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB)
            val detection = DocumentDetector.detect(rgb)
            val quad = detection?.quad
            if (detection?.kind == DocumentDetector.Detection.Kind.DOCUMENT) found++
            println(
                String.format(
                    Locale.ROOT, "%-24s %5dx%-5d %s",
                    photo.name, rgb.cols(), rgb.rows(),
                    detection?.let { "${it.kind.name.padEnd(8)} ${describe(it.quad)}" } ?: "NOTHING  (full photo used as is)",
                ),
            )
            // Full pipeline result (flatten/deskew + "Làm sáng"), as the app would export it.
            val flat = PerspectiveCorrector.warp(rgb, quad ?: Quad.FULL, PaperPreset.AUTO, 3600)
            val enhanced = Enhancer.apply(flat, FilterType.ENHANCE, 0f, 0f, cleanBorders = true)
            val enhancedBgr = Mat()
            Imgproc.cvtColor(enhanced, enhancedBgr, Imgproc.COLOR_RGB2BGR)
            Imgcodecs.imwrite(File(out, photo.nameWithoutExtension + "_result.jpg").path, enhancedBgr)
            listOf(flat, enhanced, enhancedBgr).forEach { it.release() }

            if (quad != null) {
                val pts = quad.points().map { Point(it.x * bgr.cols().toDouble(), it.y * bgr.rows().toDouble()) }
                Imgproc.polylines(bgr, listOf(MatOfPoint(*pts.toTypedArray())), true, Scalar(245.0, 165.0, 66.0), 8)
            }
            Imgcodecs.imwrite(File(out, photo.nameWithoutExtension + "_detect.jpg").path, bgr)
            bgr.release()
            rgb.release()
        }
        println("document outline found in ${found}/${photos.size}; overlays in ${out.absolutePath}")
    }

    private fun describe(q: Quad): String {
        val area = polygonArea(q)
        return q.points().joinToString(" ") { String.format(Locale.ROOT, "(%.3f,%.3f)", it.x, it.y) } +
            String.format(Locale.ROOT, "  area=%.0f%%", area * 100)
    }

    private fun polygonArea(q: Quad): Double {
        val p = q.points()
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return kotlin.math.abs(s) / 2
    }
}
