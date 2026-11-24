package com.scanpdf.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.scanpdf.app.model.FilterType
import com.scanpdf.app.model.PageEdit
import com.scanpdf.app.model.PaperPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    editor: EditorState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onEdit: ((PageEdit) -> PageEdit) -> Unit,
    onRedetect: () -> Unit,
    onFullImage: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chỉnh trang") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = { TextButton(onClick = onDone) { Text("Xong") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("1. Căn góc") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("2. Làm sáng") })
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF202124))
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (tab == 0) {
                    QuadEditor(
                        bitmap = editor.source,
                        quad = editor.edit.quad,
                        onQuadChange = { q -> onEdit { it.copy(quad = q) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val preview = editor.preview
                    if (preview == null) {
                        CircularProgressIndicator()
                    } else {
                        val bitmap = remember(preview) { preview.asImageBitmap() }
                        Image(bitmap, contentDescription = "Xem trước", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (tab == 0) {
                    CropControls(editor, onEdit, onRedetect, onFullImage)
                } else {
                    EnhanceControls(editor.edit, onEdit)
                }
            }
        }
    }
}

@Composable
private fun CropControls(
    editor: EditorState,
    onEdit: ((PageEdit) -> PageEdit) -> Unit,
    onRedetect: () -> Unit,
    onFullImage: () -> Unit,
) {
    if (!editor.cornersDetected) {
        Text(
            "Ảnh không thấy đủ 4 góc tờ giấy nên app chỉ xoay thẳng dòng chữ. " +
                "Có thể kéo các chấm tròn tới góc tài liệu. Lần sau khi chụp, hãy chừa một ít nền " +
                "quanh tờ giấy để app tự cắt và nắn phẳng.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text("Khổ giấy", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaperPreset.entries.forEach { preset ->
            FilterChip(
                selected = editor.edit.preset == preset,
                onClick = { onEdit { it.copy(preset = preset) } },
                label = { Text(preset.label) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRedetect) { Text("Tự nhận góc") }
        OutlinedButton(onClick = onFullImage) { Text("Toàn ảnh") }
    }
}

@Composable
private fun EnhanceControls(edit: PageEdit, onEdit: ((PageEdit) -> PageEdit) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterType.entries.forEach { filter ->
            FilterChip(
                selected = edit.filter == filter,
                onClick = { onEdit { it.copy(filter = filter) } },
                label = { Text(filter.label) },
            )
        }
    }
    LabeledSlider("Độ sáng", edit.brightness) { v -> onEdit { it.copy(brightness = v) } }
    LabeledSlider("Tương phản", edit.contrast) { v -> onEdit { it.copy(contrast = v) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onEdit { it.copy(rotation = (it.rotation + 270) % 360) } }) { Text("⟲ Xoay trái") }
        OutlinedButton(onClick = { onEdit { it.copy(rotation = (it.rotation + 90) % 360) } }) { Text("⟳ Xoay phải") }
        TextButton(onClick = { onEdit { it.copy(brightness = 0f, contrast = 0f) } }) { Text("Đặt lại") }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = -0.5f..0.5f,
            modifier = Modifier.weight(1f),
        )
    }
}
