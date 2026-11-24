package com.scanpdf.app.camera

import com.scanpdf.app.model.Pt
import com.scanpdf.app.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureControllerTest {

    /** A page covering ~70 % of the frame, optionally shifted. */
    private fun page(dx: Float = 0f, dy: Float = 0f, size: Float = 0.7f): Quad {
        val l = 0.15f + dx
        val t = 0.15f + dy
        return Quad(Pt(l, t), Pt(l + size, t), Pt(l + size, t + size * 1.2f), Pt(l, t + size * 1.2f))
    }

    /** Feeds frames every 33 ms (30 fps) and returns the hints. */
    private fun AutoCaptureController.feed(frames: List<Quad?>, startMs: Long = 0): List<CaptureHint> =
        frames.mapIndexed { i, q -> update(q, startMs + i * 33L) }

    @Test
    fun noDocumentMeansSearch() {
        assertEquals(CaptureHint.SEARCH, AutoCaptureController().update(null, 0))
    }

    @Test
    fun smallDocumentAsksToComeCloser() {
        // Bounding box covers 30 % of the frame: CamScanner's rule wants at least half.
        assertEquals(CaptureHint.CLOSER, AutoCaptureController().update(page(size = 0.3f), 0))
    }

    @Test
    fun capturesAfterBeingStillFor800ms() {
        val c = AutoCaptureController()
        val hints = c.feed(List(40) { page() }) // 1.3 s of a still page
        val firstCapture = hints.indexOf(CaptureHint.CAPTURE)
        assertTrue("should capture", firstCapture > 0)
        // Window fills after 5 frames, then 800 ms ≈ 25 frames of stillness.
        val elapsedMs = firstCapture * 33L
        assertTrue("captured too early at ${elapsedMs}ms", elapsedMs >= 800)
        assertTrue("captured too late at ${elapsedMs}ms", elapsedMs <= 1000)
        assertTrue(hints.take(firstCapture).all { it == CaptureHint.HOLD_STILL })
    }

    @Test
    fun handShakeAboveThresholdPreventsCapture() {
        val c = AutoCaptureController()
        // Alternating 4 % jumps: always "hold still", never capture.
        val hints = c.feed(List(60) { i -> page(dx = if (i % 2 == 0) 0f else 0.04f) })
        assertTrue(hints.none { it == CaptureHint.CAPTURE })
        assertEquals(CaptureHint.HOLD_STILL, hints.last())
    }

    @Test
    fun smallJitterBelowThresholdStillCaptures() {
        val c = AutoCaptureController()
        val hints = c.feed(List(40) { i -> page(dx = if (i % 2 == 0) 0f else 0.008f) })
        assertTrue(hints.contains(CaptureHint.CAPTURE))
    }

    @Test
    fun samePageIsNotCapturedTwice() {
        val c = AutoCaptureController()
        c.feed(List(40) { page() })
        c.onCaptured(page())
        val after = c.feed(List(60) { page() }, startMs = 2000)
        assertTrue("page lying still must not be re-captured", after.all { it == CaptureHint.NEXT_PAGE })
    }

    @Test
    fun nextPageIsCapturedAfterThePageWasTurned() {
        val c = AutoCaptureController()
        c.feed(List(40) { page() })
        c.onCaptured(page())
        // Turning the page: outline lost for a moment, then the new page lies still.
        val turn = c.feed(List(5) { null } + List(40) { page(dx = 0.01f) }, startMs = 2000)
        assertTrue(turn.contains(CaptureHint.CAPTURE))
    }

    @Test
    fun bigMoveAlsoReArms() {
        val c = AutoCaptureController()
        c.onCaptured(page())
        assertEquals(CaptureHint.NEXT_PAGE, c.update(page(dx = 0.05f), 0))
        // Moved by more than 10 % of the frame: treated as a new page.
        assertTrue(c.update(page(dx = -0.12f), 33) != CaptureHint.NEXT_PAGE)
    }

    @Test
    fun geometryHelpers() {
        assertEquals(0.84f, AutoCaptureController.coverage(page()), 1e-4f) // 0.7 wide, 0.84 tall
        assertEquals(0f, AutoCaptureController.maxDeviationFromMean(List(3) { page() }), 1e-6f)
        assertEquals(0.02f, AutoCaptureController.maxDeviationFromMean(listOf(page(), page(dx = 0.04f))), 1e-4f)
    }
}
