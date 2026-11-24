package com.scanpdf.app.storage

import com.scanpdf.app.model.DocumentSummary
import com.scanpdf.app.model.FilterType
import com.scanpdf.app.model.PageEdit
import com.scanpdf.app.model.PaperPreset
import com.scanpdf.app.model.PdfInfo
import com.scanpdf.app.model.Pt
import com.scanpdf.app.model.Quad
import com.scanpdf.app.model.ScanDocument
import com.scanpdf.app.model.ScanPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Document library on disk (app-private storage, excluded from backup):
 *
 *   <root>/<docId>/document.json      title, timestamps, pages with their edit parameters
 *   <root>/<docId>/src_<pageId>.img   the original photo (never modified)
 *   <root>/<docId>/thumb_<pageId>_<n>.jpg
 *   <root>/<docId>/document.pdf       last exported PDF
 *
 * Plain JSON instead of a database: small, dependency-free and unit-testable on the JVM.
 * Blocking I/O; callers serialise access and run it off the main thread.
 */
class DocumentStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {

    init {
        root.mkdirs()
    }

    fun list(): List<DocumentSummary> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.updatedAt }
            .map { DocumentSummary(it.id, it.title, it.updatedAt, it.pages.size, it.pages.firstOrNull()?.thumbFile) }

    fun create(title: String): ScanDocument {
        val id = UUID.randomUUID().toString()
        val now = clock()
        val doc = ScanDocument(id, title, now, now, emptyList(), null, File(root, id).apply { mkdirs() })
        save(doc)
        return doc
    }

    fun load(id: String): ScanDocument? {
        val dir = File(root, id)
        val json = File(dir, JSON).takeIf { it.isFile } ?: return null
        return try {
            fromJson(JSONObject(json.readText()), dir)
        } catch (_: Exception) {
            null // A corrupt entry must not break the whole library.
        }
    }

    /** Moves [source] into the document folder and appends a page. */
    fun addPage(docId: String, source: File, edit: PageEdit, detected: Boolean, thumbJpeg: ByteArray): ScanDocument {
        val doc = require(docId)
        val pageId = UUID.randomUUID().toString().substring(0, 8)
        val stored = File(doc.dir, "src_$pageId.img")
        moveOrCopy(source, stored)
        val thumb = writeThumb(doc.dir, pageId, thumbJpeg)
        return touchAndSave(doc.copy(pages = doc.pages + ScanPage(pageId, stored, thumb, edit, detected)))
    }

    fun updatePage(docId: String, pageId: String, edit: PageEdit, detected: Boolean, thumbJpeg: ByteArray): ScanDocument {
        val doc = require(docId)
        val old = doc.pages.firstOrNull { it.id == pageId } ?: return doc
        val thumb = writeThumb(doc.dir, pageId, thumbJpeg)
        if (thumb != old.thumbFile) old.thumbFile.delete()
        val pages = doc.pages.map { if (it.id == pageId) it.copy(edit = edit, cornersDetected = detected, thumbFile = thumb) else it }
        return touchAndSave(doc.copy(pages = pages))
    }

    fun deletePage(docId: String, pageId: String): ScanDocument {
        val doc = require(docId)
        val page = doc.pages.firstOrNull { it.id == pageId } ?: return doc
        page.sourceFile.delete()
        page.thumbFile.delete()
        return touchAndSave(doc.copy(pages = doc.pages - page))
    }

    fun movePage(docId: String, pageId: String, delta: Int): ScanDocument {
        val doc = require(docId)
        val list = doc.pages.toMutableList()
        val from = list.indexOfFirst { it.id == pageId }
        val to = from + delta
        if (from < 0 || to !in list.indices) return doc
        list.add(to, list.removeAt(from))
        return touchAndSave(doc.copy(pages = list))
    }

    fun rename(docId: String, title: String): ScanDocument =
        touchAndSave(require(docId).copy(title = title.trim().ifEmpty { "Tài liệu" }))

    /** Records that [ScanDocument.pdfFile] now matches the document. Does not count as a change. */
    fun markPdfExported(docId: String): ScanDocument {
        val doc = require(docId)
        val updated = doc.copy(pdf = PdfInfo(createdAt = maxOf(clock(), doc.updatedAt), pageCount = doc.pages.size))
        save(updated)
        return updated
    }

    fun delete(docId: String) {
        File(root, docId).deleteRecursively()
    }

    private fun require(id: String): ScanDocument = load(id) ?: throw IllegalStateException("Không tìm thấy tài liệu $id")

    private fun touchAndSave(doc: ScanDocument): ScanDocument {
        // Strictly increasing, so a PDF made in the same millisecond is never mistaken for fresh.
        val updated = doc.copy(updatedAt = maxOf(clock(), doc.updatedAt + 1, (doc.pdf?.createdAt ?: 0) + 1))
        save(updated)
        return updated
    }

    private fun save(doc: ScanDocument) {
        val target = File(doc.dir, JSON)
        val tmp = File(doc.dir, "$JSON.tmp")
        tmp.writeText(toJson(doc).toString())
        // Atomic replace: a crash mid-write never leaves a half-written document.json.
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun writeThumb(dir: File, pageId: String, jpeg: ByteArray): File {
        var n = 0
        var file: File
        do {
            file = File(dir, "thumb_${pageId}_$n.jpg")
            n++
        } while (file.exists())
        file.writeBytes(jpeg)
        return file
    }

    private fun moveOrCopy(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    companion object {
        private const val JSON = "document.json"
        private const val FORMAT_VERSION = 1

        internal fun toJson(doc: ScanDocument): JSONObject = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("id", doc.id)
            put("title", doc.title)
            put("createdAt", doc.createdAt)
            put("updatedAt", doc.updatedAt)
            put("pdf", doc.pdf?.let { JSONObject().put("createdAt", it.createdAt).put("pageCount", it.pageCount) } ?: JSONObject.NULL)
            put("pages", JSONArray().apply { doc.pages.forEach { put(pageJson(it)) } })
        }

        internal fun fromJson(json: JSONObject, dir: File): ScanDocument {
            val pagesJson = json.getJSONArray("pages")
            val pages = (0 until pagesJson.length()).map { i ->
                val p = pagesJson.getJSONObject(i)
                ScanPage(
                    id = p.getString("id"),
                    sourceFile = File(dir, p.getString("source")),
                    thumbFile = File(dir, p.getString("thumb")),
                    edit = editFromJson(p.getJSONObject("edit")),
                    cornersDetected = p.optBoolean("detected", false),
                )
            }
            val pdf = json.optJSONObject("pdf")?.let { PdfInfo(it.getLong("createdAt"), it.getInt("pageCount")) }
            return ScanDocument(
                id = json.getString("id"),
                title = json.getString("title"),
                createdAt = json.getLong("createdAt"),
                updatedAt = json.getLong("updatedAt"),
                pages = pages,
                pdf = pdf,
                dir = dir,
            )
        }

        private fun pageJson(p: ScanPage) = JSONObject().apply {
            put("id", p.id)
            put("source", p.sourceFile.name)
            put("thumb", p.thumbFile.name)
            put("detected", p.cornersDetected)
            put("edit", JSONObject().apply {
                put("quad", JSONArray().apply { p.edit.quad.points().forEach { put(it.x.toDouble()); put(it.y.toDouble()) } })
                put("preset", p.edit.preset.name)
                put("filter", p.edit.filter.name)
                put("brightness", p.edit.brightness.toDouble())
                put("contrast", p.edit.contrast.toDouble())
                put("rotation", p.edit.rotation)
            })
        }

        private fun editFromJson(e: JSONObject): PageEdit {
            val q = e.getJSONArray("quad")
            fun pt(i: Int) = Pt(q.getDouble(i * 2).toFloat(), q.getDouble(i * 2 + 1).toFloat())
            return PageEdit(
                quad = Quad(pt(0), pt(1), pt(2), pt(3)),
                preset = enumOr(e.optString("preset"), PaperPreset.AUTO),
                filter = enumOr(e.optString("filter"), FilterType.ENHANCE),
                brightness = e.optDouble("brightness", 0.0).toFloat(),
                contrast = e.optDouble("contrast", 0.0).toFloat(),
                rotation = e.optInt("rotation", 0),
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
