package com.scanpdf.app.pdf

import com.scanpdf.app.model.PaperPreset
import kotlin.math.min

data class PdfPageLayout(val pageWidth: Float, val pageHeight: Float, val image: PdfRect)

/** Decides PDF page size and where the image goes. Pure math, unit-tested. */
object PageLayout {

    const val PT_PER_MM = 72f / 25.4f
    private const val A4_SHORT_MM = 210f
    private const val A4_LONG_MM = 297f
    private const val CARD_TOP_MARGIN_MM = 20f

    /**
     * - Paper presets (A4, A5, Letter): page has that size, image fills it.
     * - Cards: A4 portrait page, card printed at its real size near the top, so a printed copy
     *   matches the original card.
     * - [PaperPreset.AUTO]: A4 page, image fitted and centred.
     * Page orientation follows the image (landscape image → landscape page).
     */
    fun compute(preset: PaperPreset, imageWidthPx: Int, imageHeightPx: Int): PdfPageLayout {
        val landscape = imageWidthPx > imageHeightPx

        if (preset.isCard) {
            val pageW = A4_SHORT_MM * PT_PER_MM
            val pageH = A4_LONG_MM * PT_PER_MM
            val cardLong = preset.longMm!! * PT_PER_MM
            val cardShort = preset.shortMm!! * PT_PER_MM
            val w = if (landscape) cardLong else cardShort
            val h = if (landscape) cardShort else cardLong
            val x = (pageW - w) / 2f
            val y = pageH - CARD_TOP_MARGIN_MM * PT_PER_MM - h
            return PdfPageLayout(pageW, pageH, PdfRect(x, y, w, h))
        }

        val shortMm = preset.shortMm ?: A4_SHORT_MM
        val longMm = preset.longMm ?: A4_LONG_MM
        val pageW = (if (landscape) longMm else shortMm) * PT_PER_MM
        val pageH = (if (landscape) shortMm else longMm) * PT_PER_MM

        val scale = min(pageW / imageWidthPx, pageH / imageHeightPx)
        val w = imageWidthPx * scale
        val h = imageHeightPx * scale
        return PdfPageLayout(pageW, pageH, PdfRect((pageW - w) / 2f, (pageH - h) / 2f, w, h))
    }
}
