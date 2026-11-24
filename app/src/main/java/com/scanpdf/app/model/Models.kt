package com.scanpdf.app.model

/** Point in normalized image coordinates: (0,0) = top-left, (1,1) = bottom-right of the oriented image. */
data class Pt(val x: Float, val y: Float)

/** Document corners in normalized coordinates, ordered clockwise from top-left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    fun points(): List<Pt> = listOf(tl, tr, br, bl)

    fun withPoint(index: Int, p: Pt): Quad = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        3 -> copy(bl = p)
        else -> throw IndexOutOfBoundsException("corner $index")
    }

    companion object {
        val FULL = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
    }
}

/**
 * Physical paper/card sizes (short side × long side, in millimetres).
 * [AUTO] keeps the proportions measured from the photo.
 * Cards are printed at real size on an A4 page.
 */
enum class PaperPreset(
    val label: String,
    val shortMm: Float?,
    val longMm: Float?,
    val isCard: Boolean,
) {
    AUTO("Tự do", null, null, false),
    A4("A4", 210f, 297f, false),
    A5("A5", 148f, 210f, false),
    LETTER("Letter", 215.9f, 279.4f, false),
    ID_CARD("CCCD / thẻ", 53.98f, 85.6f, true),
    BUSINESS_CARD("Danh thiếp", 54f, 90f, true);

    /** long / short, or null for [AUTO]. */
    val aspect: Float?
        get() = if (shortMm != null && longMm != null) longMm / shortMm else null

    /** Target resolution for the flattened image (A4 → 2480×3508 px), like a flatbed scanner. */
    val dpi: Int
        get() = 300
}

enum class FilterType(val label: String) {
    ORIGINAL("Gốc"),
    ENHANCE("Làm sáng"),
    GRAY("Xám"),
    BW("Trắng đen"),
}

/**
 * Everything needed to re-render a page from its source photo. The source is never modified,
 * so a page can be re-edited at any time (non-destructive editing).
 */
data class PageEdit(
    val quad: Quad = Quad.FULL,
    val preset: PaperPreset = PaperPreset.AUTO,
    val filter: FilterType = FilterType.ENHANCE,
    /** -0.5 .. 0.5 */
    val brightness: Float = 0f,
    /** -0.5 .. 0.5 */
    val contrast: Float = 0f,
    /** Clockwise, multiple of 90. Applied after flattening. */
    val rotation: Int = 0,
)
