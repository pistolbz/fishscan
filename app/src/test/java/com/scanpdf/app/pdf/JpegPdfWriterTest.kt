package com.scanpdf.app.pdf

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.util.Locale

class JpegPdfWriterTest {

    companion object {
        // Android unit tests compile against android.jar (no java.awt / ImageIO),
        // so sample JPEGs are produced with desktop OpenCV instead.
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        private val RGB = CvType.CV_8UC3
        private val GRAY = CvType.CV_8UC1
    }

    private lateinit var savedLocale: Locale

    @Before
    fun useVietnameseLocale() {
        // Decimal comma locale: numbers inside the PDF must still use '.'.
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("vi-VN"))
    }

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun jpeg(w: Int, h: Int, type: Int): ByteArray {
        val img = Mat(h, w, type, Scalar.all(255.0))
        Imgproc.rectangle(img, Point(w / 10.0, h / 10.0), Point(w * 0.6, h * 0.15), Scalar.all(0.0), -1)
        val buffer = MatOfByte()
        Imgcodecs.imencode(".jpg", img, buffer)
        img.release()
        return buffer.toArray().also { buffer.release() }
    }

    @Test
    fun jpegInfoReadsSizeAndComponents() {
        val rgb = JpegInfo.parse(jpeg(321, 123, RGB))
        assertEquals(JpegInfo(321, 123, 3, 8), rgb)
        val gray = JpegInfo.parse(jpeg(50, 70, GRAY))
        assertEquals(1, gray.components)
    }

    @Test
    fun writesStructurallyValidPdf() {
        val page1 = jpeg(827, 1170, RGB)
        val page2 = jpeg(600, 400, GRAY)
        val buffer = ByteArrayOutputStream()
        JpegPdfWriter(buffer).use { w ->
            w.addPage(page1, 595.28f, 841.89f, PdfRect(0f, 0f, 595.28f, 841.89f))
            w.addPage(page2, 841.89f, 595.28f, PdfRect(10.5f, 20.25f, 820f, 546.67f))
            assertEquals(2, w.pageCount)
        }
        val bytes = buffer.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)

        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/MediaBox [0 0 595.28 841.89]"))
        assertTrue(text.contains("/ColorSpace /DeviceGray"))
        assertTrue(text.contains("/ColorSpace /DeviceRGB"))
        // Exact '.'-decimals above prove the vi-VN default locale did not leak into the PDF syntax.
        assertTrue(text.contains("10.50 20.25 cm"))

        // The JPEGs must be embedded byte-for-byte (no re-encoding).
        assertTrue(indexOf(bytes, page1) > 0)
        assertTrue(indexOf(bytes, page2) > 0)

        // Every xref entry must point exactly at "N 0 obj".
        val startXref = Regex("startxref\\n(\\d+)\\n%%EOF").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref\n", startXref))
        val header = Regex("xref\\n0 (\\d+)\\n").find(text, startXref)!!
        val count = header.groupValues[1].toInt()
        var cursor = header.range.last + 1
        for (id in 0 until count) {
            val entry = text.substring(cursor, cursor + 20)
            assertEquals("entry $id must be 20 bytes", 20, entry.length)
            if (id > 0) {
                val offset = entry.substring(0, 10).toInt()
                assertTrue("object $id at $offset", text.startsWith("$id 0 obj\n", offset))
            }
            cursor += 20
        }
        val pageObjects = Regex("/Type /Page ").findAll(text).count()
        assertEquals(2, pageObjects)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
