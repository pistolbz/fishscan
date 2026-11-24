package com.scanpdf.app.camera

import com.scanpdf.app.model.Pt
import com.scanpdf.app.model.Quad
import kotlin.math.hypot
import kotlin.math.max

enum class CaptureHint {
    /** No document outline in view. */
    SEARCH,
    /** Document found but too small: move closer. */
    CLOSER,
    /** Document found, waiting for the phone to stop moving. */
    HOLD_STILL,
    /** Stable long enough: take the picture now. */
    CAPTURE,
    /** Just captured this page; waiting for the next page (outline lost or clearly changed). */
    NEXT_PAGE,
}

/**
 * Auto-capture state machine for the camera preview, fed with the outline found in each frame:
 * SEARCH → CLOSER → HOLD_STILL → (stable for [stableMs]) → CAPTURE → NEXT_PAGE → …
 *
 * Parameters follow what CamScanner ships (observed in its preview handler): a 5-frame window,
 * stability = every corner within ~1.5 % of the frame from its average position, the document's
 * bounding box covering at least half the frame, and 800 ms of stillness before shooting.
 *
 * After a capture it only re-arms once the outline disappears or moves a lot (the user turned the
 * page), so a page left lying under the phone is not captured twice. Pure Kotlin, unit-tested.
 */
class AutoCaptureController(
    /** Larger side of the outline's bounding box relative to the frame. */
    private val minCoverage: Float = 0.5f,
    private val stableMs: Long = 800,
    /** Max distance of any corner from its average over the window (fraction of the frame). */
    private val maxJitter: Float = 0.015f,
    /** Movement from the captured outline that counts as "a new page". */
    private val rearmMove: Float = 0.10f,
    private val window: Int = 5,
) {
    private val recent = ArrayDeque<Quad>()
    private var stableSince = -1L
    private var armed = true
    private var captured: Quad? = null

    fun update(quad: Quad?, nowMs: Long): CaptureHint {
        if (quad == null) {
            clearMotion()
            armed = true
            return CaptureHint.SEARCH
        }
        if (!armed) {
            val last = captured
            if (last == null || maxCornerMove(last, quad) > rearmMove) armed = true else return CaptureHint.NEXT_PAGE
        }
        if (coverage(quad) < minCoverage) {
            clearMotion()
            return CaptureHint.CLOSER
        }

        recent.addLast(quad)
        while (recent.size > window) recent.removeFirst()
        val steady = recent.size == window && maxDeviationFromMean(recent) <= maxJitter
        if (!steady) {
            stableSince = -1L
            return CaptureHint.HOLD_STILL
        }
        if (stableSince < 0) stableSince = nowMs
        return if (nowMs - stableSince >= stableMs) CaptureHint.CAPTURE else CaptureHint.HOLD_STILL
    }

    /** True once per stable period: the moment to focus on the page before the shot. */
    fun justBecameStable(nowMs: Long): Boolean = stableSince == nowMs

    /** Call after a photo was taken (automatically or with the shutter button). */
    fun onCaptured(quad: Quad?) {
        captured = quad
        armed = false
        clearMotion()
    }

    /** Fraction 0..1 of the hold time already elapsed, for a progress indicator. */
    fun holdProgress(nowMs: Long): Float =
        if (stableSince < 0) 0f else ((nowMs - stableSince).toFloat() / stableMs).coerceIn(0f, 1f)

    private fun clearMotion() {
        recent.clear()
        stableSince = -1L
    }

    companion object {
        fun maxCornerMove(a: Quad, b: Quad): Float =
            a.points().zip(b.points()).maxOf { (p, q) -> hypot(p.x - q.x, p.y - q.y) }

        /** Largest distance of any corner from that corner's average position over [quads]. */
        fun maxDeviationFromMean(quads: Collection<Quad>): Float {
            val n = quads.size
            val mean = (0 until 4).map { i ->
                Pt(quads.sumOf { it.points()[i].x.toDouble() }.toFloat() / n, quads.sumOf { it.points()[i].y.toDouble() }.toFloat() / n)
            }
            return quads.maxOf { q -> q.points().zip(mean).maxOf { (p, m) -> hypot(p.x - m.x, p.y - m.y) } }
        }

        /** Larger side of the bounding box, as a fraction of the frame (CamScanner's "big enough" test). */
        fun coverage(q: Quad): Float {
            val xs = q.points().map { it.x }
            val ys = q.points().map { it.y }
            return max(xs.max() - xs.min(), ys.max() - ys.min())
        }
    }
}
