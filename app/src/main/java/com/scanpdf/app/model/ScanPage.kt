package com.scanpdf.app.model

import java.io.File

data class ScanPage(
    val id: String,
    /** The photo as taken/imported. Never modified (non-destructive editing). */
    val sourceFile: File,
    /** Small rendered preview; its name changes with every edit, so caches never show a stale one. */
    val thumbFile: File,
    val edit: PageEdit,
    /** True when the detector found all four corners of the document. */
    val cornersDetected: Boolean,
)

/** When and how the document's PDF was last generated. */
data class PdfInfo(val createdAt: Long, val pageCount: Int, val settings: PdfSettings?)

data class ScanDocument(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<ScanPage>,
    val pdf: PdfInfo?,
    /** Folder holding the photos, thumbnails, document.json and document.pdf. */
    val dir: File,
    /** Page size / margins chosen for this document's PDF. */
    val pdfSettings: PdfSettings = PdfSettings(),
) {
    val pdfFile: File get() = File(dir, "document.pdf")

    /** The PDF exists, nothing changed since it was made and it used the current settings. */
    val isPdfFresh: Boolean
        get() = pdf != null && pdf.createdAt >= updatedAt && pdf.pageCount == pages.size &&
            pdf.settings == pdfSettings && pdfFile.exists()
}

data class DocumentSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pageCount: Int,
    val cover: File?,
)
