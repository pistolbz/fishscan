package com.scanpdf.app.storage

import com.scanpdf.app.model.FilterType
import com.scanpdf.app.model.PageEdit
import com.scanpdf.app.model.PaperPreset
import com.scanpdf.app.model.PdfPageSize
import com.scanpdf.app.model.PdfSettings
import com.scanpdf.app.model.Pt
import com.scanpdf.app.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun store() = DocumentStore(File(tmp.root, "documents")) { now }

    private fun photo(name: String): File = tmp.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private val edit = PageEdit(
        quad = Quad(Pt(0.1f, 0.2f), Pt(0.9f, 0.15f), Pt(0.95f, 0.9f), Pt(0.05f, 0.85f)),
        preset = PaperPreset.A4,
        filter = FilterType.BW,
        brightness = 0.1f,
        contrast = -0.2f,
        rotation = 90,
    )

    @Test
    fun pagesAndEditsSurviveReload() {
        val s = store()
        val doc = s.create("Hợp đồng")
        now = 2_000
        s.addPage(doc.id, photo("a.jpg"), edit, detected = true, thumbJpeg = byteArrayOf(9))
        s.addPage(doc.id, photo("b.jpg"), PageEdit(), detected = false, thumbJpeg = byteArrayOf(8))

        val loaded = store().load(doc.id)!! // fresh instance = read back from disk
        assertEquals("Hợp đồng", loaded.title)
        assertEquals(2, loaded.pages.size)
        assertEquals(edit, loaded.pages[0].edit)
        assertTrue(loaded.pages[0].cornersDetected)
        assertFalse(loaded.pages[1].cornersDetected)
        assertTrue("photo moved into the document folder", loaded.pages[0].sourceFile.isFile)
        assertEquals(doc.dir, loaded.pages[0].sourceFile.parentFile)
        assertFalse("original temp photo is gone", File(tmp.root, "a.jpg").exists())
        assertEquals(listOf<Byte>(9), loaded.pages[0].thumbFile.readBytes().toList())
    }

    @Test
    fun listIsSortedByLastChangeWithCover() {
        val s = store()
        val a = s.create("A")
        now = 2_000
        val b = s.create("B")
        now = 3_000
        s.addPage(a.id, photo("p.jpg"), PageEdit(), true, byteArrayOf(1))
        val list = s.list()
        assertEquals(listOf("A", "B"), list.map { it.title })
        assertEquals(1, list[0].pageCount)
        assertTrue(list[0].cover!!.isFile)
        assertNull(list[1].cover)
    }

    @Test
    fun reorderDeleteAndRename() {
        val s = store()
        val doc = s.create("X")
        val p1 = s.addPage(doc.id, photo("1.jpg"), PageEdit(), true, byteArrayOf(1)).pages.last()
        val p2 = s.addPage(doc.id, photo("2.jpg"), PageEdit(), true, byteArrayOf(2)).pages.last()
        assertEquals(listOf(p2.id, p1.id), s.movePage(doc.id, p2.id, -1).pages.map { it.id })
        assertEquals(listOf(p2.id, p1.id), s.movePage(doc.id, p2.id, -1).pages.map { it.id }) // already first
        val afterDelete = s.deletePage(doc.id, p2.id)
        assertEquals(listOf(p1.id), afterDelete.pages.map { it.id })
        assertFalse(p2.sourceFile.exists())
        assertFalse(p2.thumbFile.exists())
        assertEquals("Hồ sơ mới", s.rename(doc.id, "  Hồ sơ mới  ").title)
        assertEquals("Tài liệu", s.rename(doc.id, "   ").title)
    }

    @Test
    fun editingAPageReplacesItsThumbnail() {
        val s = store()
        val doc = s.create("X")
        val page = s.addPage(doc.id, photo("1.jpg"), PageEdit(), false, byteArrayOf(1)).pages.single()
        val updated = s.updatePage(doc.id, page.id, edit, true, byteArrayOf(5)).pages.single()
        assertEquals(edit, updated.edit)
        assertTrue(updated.cornersDetected)
        assertNotEquals("new name so image caches never show the old one", page.thumbFile, updated.thumbFile)
        assertFalse(page.thumbFile.exists())
        assertEquals(listOf<Byte>(5), updated.thumbFile.readBytes().toList())
    }

    @Test
    fun pdfIsStaleAfterAnyChangeEvenInTheSameMillisecond() {
        val s = store()
        val doc = s.create("X")
        val withPage = s.addPage(doc.id, photo("1.jpg"), PageEdit(), true, byteArrayOf(1))
        withPage.pdfFile.writeText("%PDF")
        val exported = s.markPdfExported(doc.id)
        assertTrue(exported.isPdfFresh)
        assertTrue("survives reload", s.load(doc.id)!!.isPdfFresh)

        // Same clock value as the export: the change must still invalidate the PDF.
        val renamed = s.rename(doc.id, "Y")
        assertFalse(renamed.isPdfFresh)
    }

    @Test
    fun pdfSettingsPersistAndChangingThemMakesThePdfStale() {
        val s = store()
        val letter = PdfSettings(PdfPageSize.LETTER, margin = false)
        val doc = s.create("X", letter)
        assertEquals(letter, s.load(doc.id)!!.pdfSettings)

        s.addPage(doc.id, photo("1.jpg"), PageEdit(), true, byteArrayOf(1)).pdfFile.writeText("%PDF")
        assertTrue(s.markPdfExported(doc.id).isPdfFresh)

        val a4 = PdfSettings(PdfPageSize.A4, margin = true)
        val changed = s.setPdfSettings(doc.id, a4)
        assertFalse("other paper size → PDF must be rebuilt", changed.isPdfFresh)
        assertEquals(a4, s.load(doc.id)!!.pdfSettings)
        assertFalse(s.load(doc.id)!!.isPdfFresh)
        assertTrue(s.markPdfExported(doc.id).isPdfFresh)
    }

    @Test
    fun documentsSavedBeforePdfSettingsExistedStillLoad() {
        val s = store()
        val doc = s.create("Old")
        s.addPage(doc.id, photo("1.jpg"), PageEdit(), true, byteArrayOf(1))
        s.markPdfExported(doc.id)
        // Strip the fields added in 0.3.1, as a 0.3.0 file would look.
        val json = File(doc.dir, "document.json")
        val obj = org.json.JSONObject(json.readText())
        obj.remove("pdfSettings")
        obj.getJSONObject("pdf").remove("settings")
        json.writeText(obj.toString())

        val loaded = s.load(doc.id)!!
        assertEquals(PdfSettings(), loaded.pdfSettings)
        assertFalse("unknown settings of the old PDF → rebuild once", loaded.isPdfFresh)
    }

    @Test
    fun corruptDocumentDoesNotBreakTheLibrary() {
        val s = store()
        s.create("Good")
        val bad = s.create("Bad")
        File(bad.dir, "document.json").writeText("{ not json")
        assertEquals(listOf("Good"), s.list().map { it.title })
        assertNull(s.load(bad.id))
    }

    @Test
    fun deleteRemovesEverything() {
        val s = store()
        val doc = s.create("X")
        s.addPage(doc.id, photo("1.jpg"), PageEdit(), true, byteArrayOf(1))
        s.delete(doc.id)
        assertFalse(doc.dir.exists())
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun unknownEnumValuesFallBackToDefaults() {
        val s = store()
        val doc = s.create("X")
        s.addPage(doc.id, photo("1.jpg"), edit, true, byteArrayOf(1))
        val json = File(doc.dir, "document.json")
        json.writeText(json.readText().replace("\"A4\"", "\"A3_FUTURE\"").replace("\"BW\"", "\"SEPIA\""))
        val page = s.load(doc.id)!!.pages.single()
        assertEquals(PaperPreset.AUTO, page.edit.preset)
        assertEquals(FilterType.ENHANCE, page.edit.filter)
    }
}
