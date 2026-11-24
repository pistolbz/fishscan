package com.scanpdf.app.pdf

import android.graphics.Bitmap
import com.scanpdf.app.imaging.ImageLoader
import com.scanpdf.app.imaging.PageRenderer
import com.scanpdf.app.model.PdfSettings
import com.scanpdf.app.model.ScanPage
import java.io.ByteArrayOutputStream
import java.io.OutputStream

object PdfExporter {

    /** Long side the source photo is decoded at for export: full resolution of a 12 MP photo. */
    private const val SOURCE_MAX_SIDE = 4096
    /** Cap on the flattened page; A4 at 300 DPI is 3508 px. */
    private const val PAGE_MAX_SIDE = 3600
    /** Pages are mostly pure white after enhancement, so 80 is visually lossless and ~20% smaller. */
    private const val JPEG_QUALITY = 80

    /** Renders pages one at a time (bounded memory) and streams them into [output]. Blocking. */
    fun export(
        pages: List<ScanPage>,
        output: OutputStream,
        settings: PdfSettings,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        JpegPdfWriter(output).use { writer ->
            pages.forEachIndexed { index, page ->
                val source = ImageLoader.load(page.sourceFile, SOURCE_MAX_SIDE)
                val rendered = PageRenderer.render(source, page.edit, PAGE_MAX_SIDE)
                source.recycle()

                val jpeg = ByteArrayOutputStream().use { buffer ->
                    rendered.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)
                    buffer.toByteArray()
                }
                val layout = PageLayout.compute(page.edit.preset, rendered.width, rendered.height, settings)
                rendered.recycle()

                writer.addPage(jpeg, layout.pageWidth, layout.pageHeight, layout.image)
                onProgress(index + 1, pages.size)
            }
        }
    }
}
