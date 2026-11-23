package com.scanpdf.app.pdf

import java.io.Closeable
import java.io.OutputStream
import java.util.Locale

/** Rectangle in PDF points (1/72 inch), origin at the bottom-left of the page. */
data class PdfRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Minimal PDF 1.4 writer for image-only documents.
 *
 * Each JPEG is embedded as-is (DCTDecode), so there is no re-compression and files stay small —
 * the same idea scanner apps use instead of a general-purpose PDF library. Pure Kotlin/JVM:
 * no Android dependency, fully unit-testable.
 */
class JpegPdfWriter(output: OutputStream) : Closeable {

    private val out = CountingOutputStream(output)
    private val offsets = HashMap<Int, Long>()
    private val pageIds = ArrayList<Int>()
    private var nextId = 3 // 1 = Catalog, 2 = Pages (written last)
    private var closed = false

    init {
        writeAscii("%PDF-1.4\n")
        // Binary marker so transfer tools treat the file as binary.
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    val pageCount: Int get() = pageIds.size

    fun addPage(jpeg: ByteArray, pageWidthPt: Float, pageHeightPt: Float, imageRect: PdfRect) {
        check(!closed) { "writer is closed" }
        val info = JpegInfo.parse(jpeg)

        val imageId = nextId++
        val colorSpace = when (info.components) {
            1 -> "/DeviceGray"
            3 -> "/DeviceRGB"
            4 -> "/DeviceCMYK /Decode [1 0 1 0 1 0 1 0]" // Adobe JPEGs store CMYK inverted
            else -> throw IllegalArgumentException("Unsupported JPEG with ${info.components} components")
        }
        beginObject(imageId)
        writeAscii(
            "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                "/ColorSpace $colorSpace /BitsPerComponent ${info.bitsPerComponent} " +
                "/Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n",
        )
        out.write(jpeg)
        writeAscii("\nendstream\nendobj\n")

        val content = "q ${n(imageRect.width)} 0 0 ${n(imageRect.height)} ${n(imageRect.x)} ${n(imageRect.y)} cm /Im0 Do Q\n"
            .toByteArray(Charsets.US_ASCII)
        val contentId = nextId++
        beginObject(contentId)
        writeAscii("<< /Length ${content.size} >>\nstream\n")
        out.write(content)
        writeAscii("endstream\nendobj\n")

        val pageId = nextId++
        beginObject(pageId)
        writeAscii(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(pageWidthPt)} ${n(pageHeightPt)}] " +
                "/Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>\nendobj\n",
        )
        pageIds += pageId
    }

    override fun close() {
        if (closed) return
        closed = true

        beginObject(2)
        writeAscii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>\nendobj\n")
        beginObject(1)
        writeAscii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        val size = nextId
        val xrefOffset = out.count
        val xref = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (id in 1 until size) {
            val offset = offsets[id] ?: error("object $id was never written")
            xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
        }
        writeAscii(xref.toString())
        writeAscii("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        out.flush()
        out.close()
    }

    private fun beginObject(id: Int) {
        offsets[id] = out.count
        writeAscii("$id 0 obj\n")
    }

    private fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

    /** Locale-independent number: never "595,28" even on a Vietnamese device. */
    private fun n(v: Float): String = String.format(Locale.ROOT, "%.2f", v)

    private class CountingOutputStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            inner.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            count += len
        }

        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }
}

/** Reads dimensions and colour components from a JPEG's SOF header. */
internal data class JpegInfo(val width: Int, val height: Int, val components: Int, val bitsPerComponent: Int) {
    companion object {
        fun parse(jpeg: ByteArray): JpegInfo {
            require(jpeg.size > 4 && u8(jpeg, 0) == 0xFF && u8(jpeg, 1) == 0xD8) { "Not a JPEG" }
            var i = 2
            while (i + 9 < jpeg.size) {
                if (u8(jpeg, i) != 0xFF) {
                    i++
                    continue
                }
                val marker = u8(jpeg, i + 1)
                if (marker == 0xFF) { // fill byte
                    i++
                    continue
                }
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                    i += 2
                    continue
                }
                val length = (u8(jpeg, i + 2) shl 8) or u8(jpeg, i + 3)
                val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isSof) {
                    return JpegInfo(
                        bitsPerComponent = u8(jpeg, i + 4),
                        height = (u8(jpeg, i + 5) shl 8) or u8(jpeg, i + 6),
                        width = (u8(jpeg, i + 7) shl 8) or u8(jpeg, i + 8),
                        components = u8(jpeg, i + 9),
                    )
                }
                i += 2 + length
            }
            throw IllegalArgumentException("JPEG has no SOF header")
        }

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    }
}
