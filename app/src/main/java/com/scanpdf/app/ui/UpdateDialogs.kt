package com.scanpdf.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun UpdateDialogs(
    state: UpdateUi,
    currentVersion: String,
    onDownload: (com.scanpdf.app.update.ReleaseInfo) -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        UpdateUi.Hidden -> Unit

        UpdateUi.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Đang kiểm tra cập nhật") },
            text = { CircularProgressIndicator() },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
        )

        is UpdateUi.Available -> {
            val release = state.release
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Có bản mới ${release.version}") },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Bạn đang dùng bản $currentVersion. Dung lượng tải: ${megabytes(release.apk.sizeBytes)}.")
                        if (release.prerelease) {
                            Text("Đây là bản thử nghiệm.", color = MaterialTheme.colorScheme.tertiary)
                        }
                        if (release.notes.isNotBlank()) {
                            Text(release.notes.take(2000), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { onDownload(release) }) { Text("Cập nhật") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Để sau") } },
            )
        }

        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {}, // only the cancel button closes it
            title = { Text("Đang tải bản ${state.release.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(state.progress * 100).toInt()}% / ${megabytes(state.release.apk.sizeBytes)}")
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
        )

        is UpdateUi.NeedsPermission -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Cho phép cài bản cập nhật") },
            text = {
                Text(
                    "Bản ${state.release.version} đã tải xong. Lần đầu cập nhật, hãy bật " +
                        "\"Cho phép từ nguồn này\" cho ScanPDF rồi quay lại app, quá trình cài sẽ tự tiếp tục.",
                )
            },
            confirmButton = { TextButton(onClick = onOpenPermissionSettings) { Text("Mở cài đặt") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Để sau") } },
        )
    }
}

private fun megabytes(bytes: Long): String =
    if (bytes <= 0) "?" else String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
