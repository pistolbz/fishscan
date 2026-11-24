package com.scanpdf.app.pdf

import com.scanpdf.app.imaging.OutputSizing
import com.scanpdf.app.model.PaperPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutAndSizingTest {

    private val mm = PageLayout.PT_PER_MM

    @Test
    fun a4PortraitFillsA4Page() {
        val l = PageLayout.compute(PaperPreset.A4, 1654, 2339)
        assertEquals(210 * mm, l.pageWidth, 0.5f)
        assertEquals(297 * mm, l.pageHeight, 0.5f)
        assertEquals(l.pageWidth, l.image.width, 1f)
        assertEquals(l.pageHeight, l.image.height, 1f)
    }

    @Test
    fun landscapeImageGetsLandscapePage() {
        val l = PageLayout.compute(PaperPreset.A4, 2339, 1654)
        assertTrue(l.pageWidth > l.pageHeight)
    }

    @Test
    fun idCardIsPrintedAtRealSizeOnA4() {
        val l = PageLayout.compute(PaperPreset.ID_CARD, 1011, 638)
        assertEquals(210 * mm, l.pageWidth, 0.5f)
        assertEquals(297 * mm, l.pageHeight, 0.5f)
        assertEquals(85.6f * mm, l.image.width, 0.5f)
        assertEquals(53.98f * mm, l.image.height, 0.5f)
        assertEquals((l.pageWidth - l.image.width) / 2, l.image.x, 0.5f) // centred
        assertTrue(l.image.y + l.image.height < l.pageHeight) // inside the page, near the top
    }

    @Test
    fun autoFitsInsideA4KeepingAspect() {
        val l = PageLayout.compute(PaperPreset.AUTO, 1000, 1000)
        assertEquals(l.image.width, l.image.height, 0.5f)
        assertTrue(l.image.width <= l.pageWidth + 0.01f)
        assertEquals(l.pageHeight / 2, l.image.y + l.image.height / 2, 0.5f)
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
