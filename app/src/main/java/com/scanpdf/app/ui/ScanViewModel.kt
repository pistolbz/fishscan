package com.scanpdf.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanpdf.app.imaging.ImageLoader
import com.scanpdf.app.imaging.PageRenderer
import com.scanpdf.app.model.PageEdit
import com.scanpdf.app.model.Quad
import com.scanpdf.app.model.ScanPage
import com.scanpdf.app.pdf.PdfExporter
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
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val pages: List<ScanPage> = emptyList(),
    /** Non-null while a long task runs; shown in a blocking overlay. */
    val busy: String? = null,
    /** One-shot message for the snackbar. */
    val message: String? = null,
)

data class EditorState(
    val pageId: Long,
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

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private var nextId = 1L
    private var previewJob: Job? = null

    init {
        // Pages live only for this session, so leftovers from earlier sessions can go.
        viewModelScope.launch(Dispatchers.IO) {
            File(app.cacheDir, "imports").deleteRecursively()
            File(app.cacheDir, "exports").deleteRecursively()
        }
    }

    // ---------- Import ----------

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            uris.forEachIndexed { index, uri ->
                setBusy("Đang nhận diện ảnh ${index + 1}/${uris.size}…")
                try {
                    val page = withContext(Dispatchers.Default) { createPage(uri) }
                    _state.update { it.copy(pages = it.pages + page) }
                } catch (e: Exception) {
                    Log.e(TAG, "import failed: $uri", e)
                    failed++
                }
            }
            setBusy(null)
            val ok = uris.size - failed
            val missing = _state.value.pages.takeLast(ok).count { !it.cornersDetected }
            showMessage(
                buildString {
                    append("Đã nhập $ok ảnh")
                    if (failed > 0) append(", lỗi $failed ảnh")
                    if (missing > 0) append(". $missing ảnh chưa nhận đủ góc, hãy chỉnh tay")
                },
            )
        }
    }

    private fun createPage(uri: Uri): ScanPage {
        val file = ImageLoader.copyToPrivateStorage(getApplication(), uri)
        val source = ImageLoader.load(file, PREVIEW_SOURCE_SIDE)
        val detected = PageRenderer.detectCorners(source)
        val edit = PageEdit(quad = detected ?: Quad.FULL)
        val thumb = PageRenderer.render(source, edit, THUMB_SIDE)
        source.recycle()
        return ScanPage(nextId(), file, edit, detected != null, thumb)
    }

    @Synchronized
    private fun nextId() = nextId++

    // ---------- Page list ----------

    fun deletePage(id: Long) {
        val page = _state.value.pages.firstOrNull { it.id == id } ?: return
        _state.update { s -> s.copy(pages = s.pages.filterNot { it.id == id }) }
        viewModelScope.launch(Dispatchers.IO) { page.sourceFile.delete() }
    }

    fun movePage(id: Long, delta: Int) {
        _state.update { s ->
            val list = s.pages.toMutableList()
            val from = list.indexOfFirst { it.id == id }
            val to = from + delta
            if (from < 0 || to !in list.indices) return@update s
            list.add(to, list.removeAt(from))
            s.copy(pages = list)
        }
    }

    // ---------- Editor ----------

    @OptIn(FlowPreview::class)
    fun openEditor(id: Long) {
        val page = _state.value.pages.firstOrNull { it.id == id } ?: return
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
                        val rendered = withContext(Dispatchers.Default) {
                            PageRenderer.render(src, edit, PREVIEW_PAGE_SIDE)
                        }
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
            val quad = withContext(Dispatchers.Default) { PageRenderer.detectCorners(e.source) }
            if (quad == null) showMessage("Không tìm thấy đủ 4 góc, hãy kéo các góc bằng tay")
            _editor.update { it?.copy(edit = it.edit.copy(quad = quad ?: Quad.FULL), cornersDetected = quad != null) }
        }
    }

    fun useFullImage() = updateEdit { it.copy(quad = Quad.FULL) }

    fun saveEditor() {
        val e = _editor.value ?: return
        viewModelScope.launch {
            val thumb = withContext(Dispatchers.Default) { PageRenderer.render(e.source, e.edit, THUMB_SIDE) }
            _state.update { s ->
                s.copy(
                    pages = s.pages.map {
                        if (it.id == e.pageId) {
                            it.copy(edit = e.edit, thumbnail = thumb, cornersDetected = it.cornersDetected || e.edit.quad != Quad.FULL)
                        } else {
                            it
                        }
                    },
                )
            }
            closeEditor()
        }
    }

    fun closeEditor() {
        previewJob?.cancel()
        previewJob = null
        _editor.value = null
    }

    // ---------- Export ----------

    fun suggestedFileName(): String =
        "scan_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date()) + ".pdf"

    fun exportTo(uri: Uri) {
        val pages = _state.value.pages
        if (pages.isEmpty()) return
        viewModelScope.launch {
            val ok = runExport(pages) {
                val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Không ghi được file")
                stream.use { out -> PdfExporter.export(pages, out, ::onExportProgress) }
            }
            if (ok) showMessage("Đã lưu PDF (${pages.size} trang)")
        }
    }

    /** Writes the PDF into the app cache and hands the file to [onReady] (for the share sheet). */
    fun exportForShare(onReady: (File) -> Unit) {
        val pages = _state.value.pages
        if (pages.isEmpty()) return
        viewModelScope.launch {
            val file = File(File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }, suggestedFileName())
            val ok = runExport(pages) {
                file.outputStream().use { out -> PdfExporter.export(pages, out, ::onExportProgress) }
            }
            if (ok) onReady(file)
        }
    }

    private suspend fun runExport(pages: List<ScanPage>, block: () -> Unit): Boolean {
        setBusy("Đang tạo PDF 0/${pages.size}…")
        return try {
            withContext(Dispatchers.Default) { block() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            showMessage("Xuất PDF thất bại: ${e.message}")
            false
        } finally {
            setBusy(null)
        }
    }

    private fun onExportProgress(done: Int, total: Int) = setBusy("Đang tạo PDF $done/$total…")

    // ---------- Misc ----------

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun setBusy(text: String?) = _state.update { it.copy(busy = text) }

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }
}
