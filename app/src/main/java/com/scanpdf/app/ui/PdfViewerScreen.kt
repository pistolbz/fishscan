package com.scanpdf.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.scanpdf.app.model.PdfSettings
import java.io.File

/** Thread-safe wrapper around Android's PdfRenderer (which allows one open page at a time). */
private class PdfPages(file: File) {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = Mutex()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Page width/height ratio, for placeholders before rendering. */
    val aspects: List<Float> = (0 until pageCount).map { i ->
        renderer.openPage(i).use { it.width.toFloat() / it.height }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(AndroidColor.WHITE) // PdfRenderer leaves the background transparent
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    suspend fun close() = lock.withLock {
        closed = true
        renderer.close()
        descriptor.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    file: File,
    title: String,
    settings: PdfSettings,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onChangeSettings: (PdfSettings) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    // Key on the modification time so a re-exported PDF is reopened.
    val pages = remember(file.path, file.lastModified()) { runCatching { PdfPages(file) }.getOrNull() }
    DisposableEffect(pages) {
        onDispose { pages?.let { p -> CoroutineScope(Dispatchers.IO).launch { p.close() } } }
    }
    var zoomed by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pages != null) "$title · ${pages.pageCount} trang" else title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    // Current paper size; tap to switch (the PDF is rebuilt).
                    TextButton(onClick = { choosing = true }) { Text(settings.pageSize.label) }
                    IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Chia sẻ PDF") }
                    TextButton(onClick = onSave) { Text("Lưu") }
                },
            )
        },
    ) { padding ->
        if (pages == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Không mở được file PDF")
            }
            return@Scaffold
        }
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF3C3C3C)),
        ) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtMost(1600)
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(pages.pageCount) { index ->
                    PdfPageImage(
                        pages, index, widthPx,
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(pages.aspects[index])
                            .clickable { zoomed = index },
                    )
                }
            }
        }
    }

    zoomed?.let { index ->
        if (pages != null) ZoomDialog(pages, index, onDismiss = { zoomed = null })
    }
    if (choosing) {
        PdfOptionsDialog(initial = settings, confirmLabel = "Tạo lại PDF", onDismiss = { choosing = false }) {
            choosing = false
            if (it != settings) onChangeSettings(it)
        }
    }
}

@Composable
private fun PdfPageImage(pages: PdfPages, index: Int, widthPx: Int, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, pages, index, widthPx) { value = pages.render(index, widthPx) }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b == null) {
            CircularProgressIndicator()
        } else {
            Image(b.asImageBitmap(), contentDescription = "Trang ${index + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(Color(0x99000000))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Full-screen page with pinch-to-zoom, pan and double-tap zoom. */
@Composable
private fun ZoomDialog(pages: PdfPages, index: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            // Twice the screen width so text stays sharp when zoomed in.
            val widthPx = with(LocalDensity.current) { (maxWidth * 2).roundToPx() }.coerceAtMost(3000)
            val bitmap by produceState<Bitmap?>(null, pages, index) { value = pages.render(index, widthPx) }
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val b = bitmap
            if (b == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Image(
                    b.asImageBitmap(),
                    contentDescription = "Trang ${index + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                                onTap = { if (scale <= 1f) onDismiss() },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Đóng", tint = Color.White)
            }
        }
    }
}
