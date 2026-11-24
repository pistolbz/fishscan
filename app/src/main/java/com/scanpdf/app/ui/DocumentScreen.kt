package com.scanpdf.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scanpdf.app.model.ScanDocument
import com.scanpdf.app.model.ScanPage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    document: ScanDocument,
    pendingCaptures: Int,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onCamera: () -> Unit,
    onImport: () -> Unit,
    onOpenPage: (String) -> Unit,
    onDeletePage: (String) -> Unit,
    onMovePage: (String, Int) -> Unit,
    onPdf: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val pages = document.pages

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        document.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Thư viện") }
                },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Thêm") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Đổi tên") }, onClick = {
                            menuOpen = false
                            renaming = true
                        })
                        DropdownMenuItem(text = { Text("Xóa tài liệu") }, onClick = {
                            menuOpen = false
                            deleting = true
                        })
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onCamera) { Text("Chụp thêm") }
                    OutlinedButton(onClick = onImport) { Text("Nhập ảnh") }
                    Box(Modifier.weight(1f))
                    Button(onClick = onPdf, enabled = pages.isNotEmpty() && pendingCaptures == 0) {
                        Text(if (document.isPdfFresh) "Xem PDF" else "Tạo PDF")
                    }
                }
            }
        },
    ) { padding ->
        if (pages.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (pendingCaptures > 0) "Đang xử lý ảnh vừa chụp…" else "Tài liệu chưa có trang nào.\nChụp hoặc nhập ảnh để thêm trang.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(Modifier.padding(padding)) {
                Text(
                    buildString {
                        append("${pages.size} trang")
                        if (pendingCaptures > 0) append(" · đang xử lý $pendingCaptures ảnh")
                        if (document.isPdfFresh) append(" · đã có PDF")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                        PageCard(
                            page = page,
                            number = index + 1,
                            isFirst = index == 0,
                            isLast = index == pages.lastIndex,
                            onOpen = { onOpenPage(page.id) },
                            onDelete = { onDeletePage(page.id) },
                            onMove = { delta -> onMovePage(page.id, delta) },
                        )
                    }
                }
            }
        }
    }

    if (renaming) {
        RenameDialog(document.title, onDismiss = { renaming = false }) {
            onRename(it)
            renaming = false
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Xóa tài liệu?") },
            text = { Text("\"${document.title}\" và ${pages.size} trang ảnh sẽ bị xóa khỏi máy.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Hủy") } },
        )
    }
}

@Composable
private fun PageCard(
    page: ScanPage,
    number: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Card(onClick = onOpen) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .background(Color(0xFFE0E0E0)),
        ) {
            FileImage(page.thumbFile, contentDescription = "Trang $number", modifier = Modifier.fillMaxSize().padding(6.dp))
            Text(
                "$number",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (!page.cornersDetected) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = "Chưa nhận đủ góc",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Lên trước")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Xóa trang") }
            IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Ra sau")
            }
        }
    }
}
