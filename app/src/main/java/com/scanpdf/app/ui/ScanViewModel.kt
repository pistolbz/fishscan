package com.scanpdf.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanpdf.app.imaging.DocumentDetector
import com.scanpdf.app.imaging.ImageLoader
import com.scanpdf.app.imaging.PageRenderer
import com.scanpdf.app.model.DocumentSummary
import com.scanpdf.app.model.PageEdit
import com.scanpdf.app.model.PdfPageSize
import com.scanpdf.app.model.PdfSettings
import com.scanpdf.app.model.Quad
import com.scanpdf.app.model.ScanDocument
import com.scanpdf.app.pdf.PdfExporter
import com.scanpdf.app.storage.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Library : Screen
    data class Document(val docId: String) : Screen
    data class Camera(val docId: String) : Screen
    data class Pdf(val docId: String) : Screen
}

data class UiState(
    val screen: Screen = Screen.Library,
    val library: List<DocumentSummary> = emptyList(),
    /** The open document (Document, Camera, Pdf screens). */
    val document: ScanDocument? = null,
    /** Camera photos still being processed in the background. */
    val pendingCaptures: Int = 0,
    /** Non-null while a long task runs; shown in a blocking overlay. */
    val busy: String? = null,
    /** One-shot message for the snackbar. */
    val message: String? = null,
)

data class EditorState(
    val pageId: String,
    /** Upright photo at preview resolution, used by the corner editor. */
    val source: Bitmap,
    val edit: PageEdit,
    val cornersDetected: Boolean,
    /** Flattened + filtered result for the second tab; null while rendering. */
    val preview: Bitmap? = null,
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "ScanPDF"
        const val PREVIEW_SOURCE_SIDE = 1600
        const val PREVIEW_PAGE_SIDE = 1400
        const val THUMB_SIDE = 360
    }

    private val store = DocumentStore(File(app.filesDir, "documents"))
    private val storeLock = Mutex()
    private val prefs = app.getSharedPreferences("pdf", android.content.Context.MODE_PRIVATE)

    /** PDF layout last chosen by the user; new documents start with it. */
    private fun lastPdfSettings(): PdfSettings = PdfSettings(
        pageSize = PdfPageSize.entries.firstOrNull { it.name == prefs.getString("pageSize", null) } ?: PdfPageSize.A4,
        margin = prefs.getBoolean("margin", true),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private var previewJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Temporary files only; documents live in filesDir.
            listOf("imports", "exports", "capture").forEach { File(app.cacheDir, it).deleteRecursively() }
        }
        refreshLibrary()
    }

    /** Serialised, off-main-thread access to the document store. */
    private suspend fun <T> io(block: DocumentStore.() -> T): T =
        storeLock.withLock { withContext(Dispatchers.IO) { store.block() } }

    /** Updates the open document, unless the user meanwhile opened another one. */
    private fun applyDocument(doc: ScanDocument) =
        _state.update { if (it.document?.id == doc.id) it.copy(document = doc) else it }

    // ---------- Navigation ----------

    fun back() {
        if (_editor.value != null) {
            closeEditor()
            return
        }
        when (val screen = _state.value.screen) {
            Screen.Library -> Unit
            is Screen.Document -> closeDocument()
            is Screen.Camera -> leaveCamera()
            is Screen.Pdf -> _state.update { it.copy(screen = Screen.Document(screen.docId)) }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            val docs = io { list() }
            _state.update { it.copy(library = docs) }
        }
    }

    fun openDocument(id: String) {
        viewModelScope.launch {
            val doc = io { load(id) }
            if (doc == null) {
                showMessage("Không mở được tài liệu")
                refreshLibrary()
                return@launch
            }
            _state.update { it.copy(document = doc, screen = Screen.Document(id)) }
        }
    }

    fun closeDocument() {
        val s = _state.value
        val doc = s.document
        viewModelScope.launch {
            // An empty document (e.g. camera opened then closed) is not worth keeping.
            if (doc != null && doc.pages.isEmpty() && s.pendingCaptures == 0) io { delete(doc.id) }
            _state.update { it.copy(document = null, screen = Screen.Library) }
            refreshLibrary()
        }
    }

    fun startCameraInNewDocument() {
        viewModelScope.launch {
            val doc = io { create(defaultTitle(), lastPdfSettings()) }
            _state.update { it.copy(document = doc, screen = Screen.Camera(doc.id)) }
        }
    }

    fun openCamera() {
        val doc = _state.value.document ?: return
        _state.update { it.copy(screen = Screen.Camera(doc.id)) }
    }

    fun leaveCamera() {
        val s = _state.value
        val doc = s.document ?: return
        if (doc.pages.isEmpty() && s.pendingCaptures == 0) {
            closeDocument()
        } else {
            _state.update { it.copy(screen = Screen.Document(doc.id)) }
        }
    }

    // ---------- Adding pages ----------

    fun importIntoNewDocument(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val doc = io { create(defaultTitle(), lastPdfSettings()) }
            _state.update { it.copy(document = doc, screen = Screen.Document(doc.id)) }
            importImages(uris)
        }
    }

    fun importImages(uris: List<Uri>) {
        val docId = _state.value.document?.id ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            var missing = 0
            uris.forEachIndexed { index, uri ->
                setBusy("Đang nhận diện ảnh ${index + 1}/${uris.size}…")
                try {
                    val file = withContext(Dispatchers.IO) { ImageLoader.copyToPrivateStorage(getApplication(), uri) }
                    val prepared = withContext(Dispatchers.Default) { preparePage(file) }
                    if (!prepared.detected) missing++
                    applyDocument(io { addPage(docId, file, prepared.edit, prepared.detected, prepared.thumb) })
                } catch (e: Exception) {
                    Log.e(TAG, "import failed: $uri", e)
                    failed++
                }
            }
            setBusy(null)
            val ok = uris.size - failed
            showMessage(
                buildString {
                    append("Đã thêm $ok ảnh")
                    if (failed > 0) append(", lỗi $failed ảnh")
                    if (missing > 0) append(". $missing ảnh không thấy đủ 4 góc giấy")
                },
            )
        }
    }

    /** A photo just taken by the in-app camera; processed in the background while shooting continues. */
    fun addCapturedPhoto(file: File) {
        val docId = _state.value.document?.id ?: return
        _state.update { it.copy(pendingCaptures = it.pendingCaptures + 1) }
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.Default) { preparePage(file) }
                applyDocument(io { addPage(docId, file, prepared.edit, prepared.detected, prepared.thumb) })
            } catch (e: Exception) {
                Log.e(TAG, "capture processing failed", e)
                showMessage("Không xử lý được ảnh vừa chụp")
            } finally {
                _state.update { it.copy(pendingCaptures = it.pendingCaptures - 1) }
            }
        }
    }

    private class PreparedPage(val edit: PageEdit, val detected: Boolean, val thumb: ByteArray)

    private fun preparePage(file: File): PreparedPage {
        val source = ImageLoader.load(file, PREVIEW_SOURCE_SIDE)
        try {
            val detection = PageRenderer.detectCorners(source)
            // A DESKEW result still straightens the page even though no corners were found.
            val edit = PageEdit(quad = detection?.quad ?: Quad.FULL)
            val thumb = PageRenderer.render(source, edit, THUMB_SIDE)
            val detected = detection?.kind == DocumentDetector.Detection.Kind.DOCUMENT
            return PreparedPage(edit, detected, thumb.toJpeg())
        } finally {
            source.recycle()
        }
    }

    // ---------- Document & page list ----------

    fun deletePage(pageId: String) = mutateDocument { deletePage(it, pageId) }

    fun movePage(pageId: String, delta: Int) = mutateDocument { movePage(it, pageId, delta) }

    fun renameDocument(title: String) = mutateDocument { rename(it, title) }

    fun renameFromLibrary(id: String, title: String) {
        viewModelScope.launch {
            io { rename(id, title) }
            refreshLibrary()
        }
    }

    fun deleteCurrentDocument() {
        val doc = _state.value.document ?: return
        viewModelScope.launch {
            io { delete(doc.id) }
            _state.update { it.copy(document = null, screen = Screen.Library) }
            refreshLibrary()
            showMessage("Đã xóa tài liệu")
        }
    }

    fun reportError(text: String) = showMessage(text)

    fun deleteFromLibrary(id: String) {
        viewModelScope.launch {
            io { delete(id) }
            refreshLibrary()
            showMessage("Đã xóa tài liệu")
        }
    }

    private fun mutateDocument(op: DocumentStore.(docId: String) -> ScanDocument) {
        val docId = _state.value.document?.id ?: return
        viewModelScope.launch {
            try {
                applyDocument(io { op(docId) })
            } catch (e: Exception) {
                Log.e(TAG, "document update failed", e)
                showMessage("Không lưu được thay đổi")
            }
        }
    }

    // ---------- Editor ----------

    @OptIn(FlowPreview::class)
    fun openEditor(pageId: String) {
        val page = _state.value.document?.pages?.firstOrNull { it.id == pageId } ?: return
        viewModelScope.launch {
            setBusy("Đang mở ảnh…")
            val source = try {
                withContext(Dispatchers.Default) { ImageLoader.load(page.sourceFile, PREVIEW_SOURCE_SIDE) }
            } catch (e: Exception) {
                Log.e(TAG, "open failed", e)
                setBusy(null)
                showMessage("Không mở được ảnh")
                return@launch
            }
            setBusy(null)
            _editor.value = EditorState(page.id, source, page.edit, page.cornersDetected)

            previewJob?.cancel()
            previewJob = viewModelScope.launch {
                _editor.filterNotNull()
                    .map { it.source to it.edit }
                    .distinctUntilChanged()
                    .debounce(120)
                    .collectLatest { (src, edit) ->
                        val rendered = withContext(Dispatchers.Default) { PageRenderer.render(src, edit, PREVIEW_PAGE_SIDE) }
                        _editor.update { e -> if (e != null && e.edit == edit) e.copy(preview = rendered) else e }
                    }
            }
        }
    }

    fun updateEdit(transform: (PageEdit) -> PageEdit) {
        _editor.update { e -> e?.copy(edit = transform(e.edit)) }
    }

    fun redetectCorners() {
        val e = _editor.value ?: return
        viewModelScope.launch {
            val detection = withContext(Dispatchers.Default) { PageRenderer.detectCorners(e.source) }
            val found = detection?.kind == DocumentDetector.Detection.Kind.DOCUMENT
            if (!found) showMessage("Không thấy đủ 4 góc tờ giấy, hãy kéo các góc bằng tay")
            _editor.update { it?.copy(edit = it.edit.copy(quad = detection?.quad ?: Quad.FULL), cornersDetected = found) }
        }
    }

    fun useFullImage() = updateEdit { it.copy(quad = Quad.FULL) }

    fun saveEditor() {
        val e = _editor.value ?: return
        val docId = _state.value.document?.id ?: return
        viewModelScope.launch {
            val thumb = withContext(Dispatchers.Default) { PageRenderer.render(e.source, e.edit, THUMB_SIDE).toJpeg() }
            val detected = e.cornersDetected || e.edit.quad != Quad.FULL
            applyDocument(io { updatePage(docId, e.pageId, e.edit, detected, thumb) })
            closeEditor()
        }
    }

    fun closeEditor() {
        previewJob?.cancel()
        previewJob = null
        _editor.value = null
    }

    // ---------- PDF ----------

    fun suggestedFileName(): String {
        val title = _state.value.document?.title ?: "scan"
        return title.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "scan" } + ".pdf"
    }

    /** Builds the PDF if the document changed since the last one, then opens the viewer. */
    fun createAndViewPdf() {
        viewModelScope.launch {
            val doc = ensurePdf() ?: return@launch
            _state.update { it.copy(screen = Screen.Pdf(doc.id)) }
        }
    }

    /**
     * Applies a page size / margin choice (remembered for new documents), rebuilds the PDF and shows
     * it. Also used from the viewer to switch the paper size.
     */
    fun createPdf(settings: PdfSettings) {
        val doc = _state.value.document ?: return
        prefs.edit().putString("pageSize", settings.pageSize.name).putBoolean("margin", settings.margin).apply()
        viewModelScope.launch {
            if (settings != doc.pdfSettings) applyDocument(io { setPdfSettings(doc.id, settings) })
            val fresh = ensurePdf() ?: return@launch
            _state.update { it.copy(screen = Screen.Pdf(fresh.id)) }
        }
    }

    fun savePdfTo(uri: Uri) {
        viewModelScope.launch {
            val doc = ensurePdf() ?: return@launch
            try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Không ghi được file")
                    out.use { o -> doc.pdfFile.inputStream().use { it.copyTo(o) } }
                }
                showMessage("Đã lưu PDF (${doc.pages.size} trang)")
            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
                showMessage("Lưu PDF thất bại: ${e.message}")
            }
        }
    }

    /** Shares a copy named after the document, so the recipient sees a meaningful file name. */
    fun sharePdf(onReady: (File) -> Unit) {
        viewModelScope.launch {
            val doc = ensurePdf() ?: return@launch
            val copy = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "exports").apply {
                    deleteRecursively()
                    mkdirs()
                }
                doc.pdfFile.copyTo(File(dir, suggestedFileName()), overwrite = true)
            }
            onReady(copy)
        }
    }

    private suspend fun ensurePdf(): ScanDocument? {
        val doc = _state.value.document ?: return null
        if (doc.pages.isEmpty()) {
            showMessage("Tài liệu chưa có trang nào")
            return null
        }
        if (doc.isPdfFresh) return doc
        setBusy("Đang tạo PDF 0/${doc.pages.size}…")
        return try {
            withContext(Dispatchers.Default) {
                // Write next to the target, then replace it, so a failed export never leaves a broken PDF.
                val tmp = File(doc.dir, "document.pdf.tmp")
                tmp.outputStream().use { out ->
                    PdfExporter.export(doc.pages, out, doc.pdfSettings) { done, total -> setBusy("Đang tạo PDF $done/$total…") }
                }
                if (!tmp.renameTo(doc.pdfFile)) {
                    tmp.copyTo(doc.pdfFile, overwrite = true)
                    tmp.delete()
                }
            }
            io { markPdfExported(doc.id) }.also(::applyDocument)
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            showMessage("Tạo PDF thất bại: ${e.message}")
            null
        } finally {
            setBusy(null)
        }
    }

    // ---------- Misc ----------

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun setBusy(text: String?) = _state.update { it.copy(busy = text) }

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }

    private fun defaultTitle(): String =
        "Tài liệu " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date())

    private fun Bitmap.toJpeg(): ByteArray = ByteArrayOutputStream().use { buffer ->
        compress(Bitmap.CompressFormat.JPEG, 80, buffer)
        buffer.toByteArray()
    }
}
