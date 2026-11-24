package com.scanpdf.app.pdf

import com.scanpdf.app.model.PaperPreset
import com.scanpdf.app.model.PdfPageSize
import com.scanpdf.app.model.PdfSettings
import kotlin.math.min

data class PdfPageLayout(val pageWidth: Float, val pageHeight: Float, val image: PdfRect)

/** Decides PDF page size and where the image goes. Pure math, unit-tested. */
object PageLayout {

    const val PT_PER_MM = 72f / 25.4f
    /** White border when margins are on: the 20 pt CamScanner uses (~7 mm). */
    const val MARGIN_PT = 20f
    /** Resolution used to turn a free-form ("Tự do") image into a physical size. */
    private const val FLEXIBLE_DPI = 300f

    /**
     * - [PdfPageSize.FLEXIBLE]: the page takes the image's physical size (paper/card presets use their
     *   real size, free-form images their pixels at 300 DPI); margins add a white border around it.
     * - Fixed sizes (A4, Letter…): every page has that size. The image is scaled to fit inside the
     *   margins and centred on the white page. Page orientation follows the image.
     * - Cards are always printed at their real size (portrait page), so a printout matches the card.
     */
    fun compute(
        preset: PaperPreset,
        imageWidthPx: Int,
        imageHeightPx: Int,
        settings: PdfSettings = PdfSettings(),
    ): PdfPageLayout {
        val landscape = imageWidthPx > imageHeightPx
        val margin = if (settings.margin) MARGIN_PT else 0f

        // Physical size of the image itself.
        val (imgWpt, imgHpt) = if (preset.shortMm != null && preset.longMm != null) {
            val long = preset.longMm * PT_PER_MM
            val short = preset.shortMm * PT_PER_MM
            if (landscape) long to short else short to long
        } else {
            imageWidthPx / FLEXIBLE_DPI * 72f to imageHeightPx / FLEXIBLE_DPI * 72f
        }

        val size = settings.pageSize
        if (size.shortMm == null || size.longMm == null) {
            val pageW = imgWpt + 2 * margin
            val pageH = imgHpt + 2 * margin
            return PdfPageLayout(pageW, pageH, PdfRect(margin, margin, imgWpt, imgHpt))
        }

        // A card on a landscape sheet looks odd and prints badly: keep the sheet portrait.
        val pageLandscape = landscape && !preset.isCard
        val pageW = (if (pageLandscape) size.longMm else size.shortMm) * PT_PER_MM
        val pageH = (if (pageLandscape) size.shortMm else size.longMm) * PT_PER_MM
        val boxW = pageW - 2 * margin
        val boxH = pageH - 2 * margin

        val (w, h) = if (preset.isCard) {
            // Real size, shrunk only if the card would not fit (e.g. tiny page + margins).
            val s = min(1f, min(boxW / imgWpt, boxH / imgHpt))
            imgWpt * s to imgHpt * s
        } else {
            val s = min(boxW / imageWidthPx, boxH / imageHeightPx)
            imageWidthPx * s to imageHeightPx * s
        }
        return PdfPageLayout(pageW, pageH, PdfRect((pageW - w) / 2f, (pageH - h) / 2f, w, h))
    }
}
