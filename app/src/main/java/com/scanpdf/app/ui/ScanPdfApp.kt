package com.scanpdf.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

@Composable
fun ScanPdfApp(vm: ScanViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // System photo picker; falls back to the document picker on devices without it.
    // No storage permission and no Google Play services needed.
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.importImages(uris)
    }
    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        // Consume only after showing: consuming first would change the key and cancel this effect.
        snackbar.showSnackbar(message)
        vm.consumeMessage()
    }

    Box(Modifier.fillMaxSize()) {
        val currentEditor = editor
        if (currentEditor != null) {
            BackHandler { vm.closeEditor() }
            EditScreen(
                editor = currentEditor,
                onBack = vm::closeEditor,
                onDone = vm::saveEditor,
                onEdit = vm::updateEdit,
                onRedetect = vm::redetectCorners,
                onFullImage = vm::useFullImage,
            )
        } else {
            HomeScreen(
                pages = state.pages,
                onImport = {
                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onOpen = vm::openEditor,
                onDelete = vm::deletePage,
                onMove = vm::movePage,
                onSavePdf = { createPdf.launch(vm.suggestedFileName()) },
                onSharePdf = { vm.exportForShare { file -> context.sharePdf(file) } },
            )
        }

        // App-level so messages also appear on the edit screen.
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 80.dp),
        )

        state.busy?.let { BusyOverlay(it) }
    }
}

@Composable
private fun BusyOverlay(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // Swallow touches while busy.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Color.White)
            Text(text, color = Color.White)
        }
    }
}

private fun Context.sharePdf(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, "Chia sẻ PDF"))
}
