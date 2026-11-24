package com.scanpdf.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.scanpdf.app.model.PaperPreset
import com.scanpdf.app.model.PdfPageSize
import com.scanpdf.app.model.PdfSettings
import com.scanpdf.app.pdf.PageLayout

/** Page size + margin picker shown before creating (or re-creating) a PDF. */
@Composable
fun PdfOptionsDialog(
    initial: PdfSettings,
    confirmLabel: String = "Tạo PDF",
    onDismiss: () -> Unit,
    onConfirm: (PdfSettings) -> Unit,
) {
    var settings by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Khổ giấy PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdfPageSize.entries.forEach { size ->
                        FilterChip(
                            selected = settings.pageSize == size,
                            onClick = { settings = settings.copy(pageSize = size) },
                            label = { Text(size.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Lề trắng quanh ảnh", Modifier.weight(1f))
                    Switch(checked = settings.margin, onCheckedChange = { settings = settings.copy(margin = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LayoutPreview(settings, Modifier.width(72.dp).height(96.dp))
                    Text(
                        if (settings.pageSize == PdfPageSize.FLEXIBLE) {
                            "Mỗi trang PDF có đúng kích thước của ảnh."
                        } else {
                            "Mọi trang là khổ ${settings.pageSize.label}, ảnh được căn giữa trên trang trắng. " +
                                "Thẻ (CCCD, danh thiếp) giữ đúng kích thước thật."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(settings) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

/** Miniature page using the real layout math, with a typical 3:4 photo. */
@Composable
private fun LayoutPreview(settings: PdfSettings, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val photo = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Canvas(modifier) {
        val layout = PageLayout.compute(PaperPreset.AUTO, 3000, 4000, settings)
        val s = minOf(size.width / layout.pageWidth, size.height / layout.pageHeight)
        val pw = layout.pageWidth * s
        val ph = layout.pageHeight * s
        val origin = Offset((size.width - pw) / 2f, (size.height - ph) / 2f)
        drawRect(Color.White, origin, Size(pw, ph))
        drawRect(outline, origin, Size(pw, ph), style = Stroke(1.dp.toPx()))
        val img = layout.image
        // PDF y grows upwards; flip to screen coordinates.
        val top = layout.pageHeight - img.y - img.height
        drawRect(photo, origin + Offset(img.x * s, top * s), Size(img.width * s, img.height * s))
    }
}
