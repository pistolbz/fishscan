package com.scanpdf.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scanpdf.app.model.ScanPage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    pages: List<ScanPage>,
    versionLabel: String,
    onCheckUpdate: () -> Unit,
    onImport: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    onSavePdf: () -> Unit,
    onSharePdf: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pages.isEmpty()) "ScanPDF" else "ScanPDF · ${pages.size} trang") },
                actions = {
                    if (pages.isNotEmpty()) {
                        IconButton(onClick = onSharePdf) { Icon(Icons.Filled.Share, contentDescription = "Chia sẻ PDF") }
                        TextButton(onClick = onSavePdf) { Text("Lưu PDF") }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Thêm")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Kiểm tra cập nhật") },
                            onClick = {
                                menuOpen = false
                                onCheckUpdate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Phiên bản $versionLabel") },
                            onClick = {},
                            enabled = false,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImport,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Nhập ảnh") },
            )
        },
    ) { padding ->
        if (pages.isEmpty()) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Chưa có trang nào.\nNhấn \"Nhập ảnh\" để chọn ảnh tài liệu, CCCD hoặc danh thiếp từ máy.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding),
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                    PageCard(
                        page = page,
                        number = index + 1,
                        isFirst = index == 0,
                        isLast = index == pages.lastIndex,
                        onOpen = { onOpen(page.id) },
                        onDelete = { onDelete(page.id) },
                        onMove = { delta -> onMove(page.id, delta) },
                    )
                }
            }
        }
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
            val thumb = remember(page.thumbnail) { page.thumbnail.asImageBitmap() }
            Image(thumb, contentDescription = "Trang $number", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
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
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Lên trước")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Xóa trang")
                }
                IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Ra sau")
                }
            }
        }
    }
}
