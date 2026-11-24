package com.scanpdf.app.model

/** Paper size of the generated PDF (short side × long side, mm). */
enum class PdfPageSize(val label: String, val shortMm: Float?, val longMm: Float?) {
    /** Every page takes the size of its own image (CamScanner's default when no size is set). */
    FLEXIBLE("Theo ảnh", null, null),
    A4("A4", 210f, 297f),
    A5("A5", 148f, 210f),
    LETTER("Letter", 215.9f, 279.4f),
    LEGAL("Legal", 215.9f, 355.6f),
}

/**
 * How pages are laid out in the PDF. With a fixed size every page has that size and the image is
 * centred on the white page; [margin] keeps a white border around it, like CamScanner's margin option.
 */
data class PdfSettings(
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val margin: Boolean = true,
) {
    val label: String
        get() = pageSize.label + if (margin) " · có lề" else ""
}
