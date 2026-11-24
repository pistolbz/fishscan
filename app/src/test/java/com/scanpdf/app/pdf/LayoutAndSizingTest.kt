package com.scanpdf.app.pdf

import com.scanpdf.app.imaging.OutputSizing
import com.scanpdf.app.model.PaperPreset
import com.scanpdf.app.model.PdfPageSize
import com.scanpdf.app.model.PdfSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutAndSizingTest {

    private val mm = PageLayout.PT_PER_MM
    private val a4w = 210 * mm
    private val a4h = 297 * mm
    private val m = PageLayout.MARGIN_PT

    @Test
    fun a4ScanOnA4WithoutMarginFillsThePage() {
        val l = PageLayout.compute(PaperPreset.A4, 2480, 3508, PdfSettings(PdfPageSize.A4, margin = false))
        assertEquals(a4w, l.pageWidth, 0.5f)
        assertEquals(a4h, l.pageHeight, 0.5f)
        assertEquals(a4w, l.image.width, 1f)
        assertEquals(a4h, l.image.height, 1f)
    }

    @Test
    fun photoIsCentredInsideTheMarginsOfAWhiteA4Page() {
        // 3:4 photo on A4 with margins: limited by the width, centred vertically, aspect kept.
        val l = PageLayout.compute(PaperPreset.AUTO, 3000, 4000, PdfSettings(PdfPageSize.A4, margin = true))
        assertEquals(a4w, l.pageWidth, 0.5f)
        assertEquals(a4h, l.pageHeight, 0.5f)
        assertEquals(a4w - 2 * m, l.image.width, 0.5f)
        assertEquals(0.75f, l.image.width / l.image.height, 0.001f)
        assertEquals(m, l.image.x, 0.5f)
        assertEquals(l.pageHeight / 2, l.image.y + l.image.height / 2, 0.5f)
        assertTrue(l.image.y >= m - 0.01f)
    }

    @Test
    fun wideImageGetsALandscapePageOfTheChosenSize() {
        val l = PageLayout.compute(PaperPreset.AUTO, 4000, 3000, PdfSettings(PdfPageSize.LETTER, margin = true))
        assertEquals(279.4f * mm, l.pageWidth, 0.5f)
        assertEquals(215.9f * mm, l.pageHeight, 0.5f)
        assertEquals(l.pageWidth / 2, l.image.x + l.image.width / 2, 0.5f)
        assertEquals(l.pageHeight / 2, l.image.y + l.image.height / 2, 0.5f)
    }

    @Test
    fun everyPortraitPageGetsTheSameFixedSize() {
        val pages = listOf(
            PaperPreset.A4 to (2480 to 3508),
            PaperPreset.A5 to (1748 to 2480),
            PaperPreset.LETTER to (2550 to 3300),
            PaperPreset.AUTO to (1000 to 1600),
        )
        val sizes = pages.map { (preset, wh) ->
            PageLayout.compute(preset, wh.first, wh.second, PdfSettings(PdfPageSize.A4, true)).let { it.pageWidth to it.pageHeight }
        }.toSet()
        assertEquals(1, sizes.size)
    }

    @Test
    fun idCardStaysRealSizeCentredOnPortraitA4() {
        val l = PageLayout.compute(PaperPreset.ID_CARD, 1011, 638, PdfSettings(PdfPageSize.A4, margin = true))
        // Portrait sheet even though the card image is landscape.
        assertEquals(a4w, l.pageWidth, 0.5f)
        assertEquals(a4h, l.pageHeight, 0.5f)
        assertEquals(85.6f * mm, l.image.width, 0.5f)
        assertEquals(53.98f * mm, l.image.height, 0.5f)
        assertEquals(l.pageWidth / 2, l.image.x + l.image.width / 2, 0.5f)
        assertEquals(l.pageHeight / 2, l.image.y + l.image.height / 2, 0.5f)
    }

    @Test
    fun flexiblePageTakesTheImageSize() {
        val free = PageLayout.compute(PaperPreset.AUTO, 3000, 4000, PdfSettings(PdfPageSize.FLEXIBLE, margin = false))
        assertEquals(3000 / 300f * 72f, free.pageWidth, 0.5f) // pixels at 300 DPI
        assertEquals(4000 / 300f * 72f, free.pageHeight, 0.5f)
        assertEquals(PdfRect(0f, 0f, free.pageWidth, free.pageHeight), free.image)

        val card = PageLayout.compute(PaperPreset.ID_CARD, 1011, 638, PdfSettings(PdfPageSize.FLEXIBLE, margin = false))
        assertEquals(85.6f * mm, card.pageWidth, 0.5f)
        assertEquals(53.98f * mm, card.pageHeight, 0.5f)

        // Margins add a white border around the image.
        val bordered = PageLayout.compute(PaperPreset.A4, 2480, 3508, PdfSettings(PdfPageSize.FLEXIBLE, margin = true))
        assertEquals(a4w + 2 * m, bordered.pageWidth, 0.5f)
        assertEquals(m, bordered.image.x, 0.01f)
        assertEquals(m, bordered.image.y, 0.01f)
    }

    @Test
    fun outputSizingUsesPresetDpiAndRespectsCap() {
        assertEquals(2480 to 3508, OutputSizing.compute(900.0, 1300.0, PaperPreset.A4, 4000)) // 300 DPI
        assertEquals(3508 to 2480, OutputSizing.compute(1300.0, 900.0, PaperPreset.A4, 4000))
        val (w, h) = OutputSizing.compute(900.0, 1300.0, PaperPreset.A4, 1400)
        assertEquals(1400, h)
        assertEquals(990, w)
        assertEquals(1011, OutputSizing.compute(1000.0, 640.0, PaperPreset.ID_CARD, 4000).first) // 300 DPI
        assertEquals(800 to 1200, OutputSizing.compute(800.0, 1200.0, PaperPreset.AUTO, 4000))
    }
}
