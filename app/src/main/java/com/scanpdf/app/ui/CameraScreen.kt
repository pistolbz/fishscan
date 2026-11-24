package com.scanpdf.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scanpdf.app.camera.AutoCaptureController
import com.scanpdf.app.camera.CameraFrames
import com.scanpdf.app.camera.CaptureHint
import com.scanpdf.app.imaging.DocumentDetector
import com.scanpdf.app.model.Quad
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One analysed preview frame: outline (normalized, upright) and the frame's width/height ratio. */
private data class FrameResult(val quad: Quad?, val aspect: Float, val hint: CaptureHint)

@Composable
fun CameraScreen(
    pageCount: Int,
    pendingCount: Int,
    lastThumb: File?,
    onCaptured: (File) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) request.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "ScanPDF cần quyền camera để chụp tài liệu. Ảnh chỉ lưu trong máy.",
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = {
                if (asked) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    request.launch(Manifest.permission.CAMERA)
                }
            }) { Text(if (asked) "Mở cài đặt quyền" else "Cho phép camera") }
            TextButton(onClick = onDone) { Text("Quay lại", color = Color.White) }
        }
        return
    }

    CameraContent(pageCount, pendingCount, lastThumb, onCaptured, onDone, onError)
}

@Composable
private fun CameraContent(
    pageCount: Int,
    pendingCount: Int,
    lastThumb: File?,
    onCaptured: (File) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember { AutoCaptureController() }
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnError by rememberUpdatedState(onError)

    val previewView = remember {
        PreviewView(context).apply {
            // FIT_CENTER + 4:3 everywhere: preview and analysis frames cover exactly the same area,
            // so the outline can be drawn with a simple scale.
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val ratio43 = remember {
        AspectRatioStrategy(androidx.camera.core.AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            // Like CamScanner: favour a fast shutter; the photo is still full resolution.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio43)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
            .build()
    }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio43)
                    .setResolutionStrategy(
                        ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var frame by remember { mutableStateOf(FrameResult(null, 0.75f, CaptureHint.SEARCH)) }
    var autoMode by rememberSaveable { mutableStateOf(true) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var lastFocusAt by remember { mutableLongStateOf(0L) }
    var focusRing by remember { mutableStateOf<Offset?>(null) }
    val flash = remember { Animatable(0f) }

    /** Maps a normalized point of the (upright) frame into PreviewView coordinates. */
    fun frameRect(aspect: Float): Rect {
        val vw = previewView.width.toFloat()
        val vh = previewView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return Rect.Zero
        val w = minOf(vw, vh * aspect)
        val h = w / aspect
        return Rect((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f)
    }

    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun takePicture() {
        if (capturing) return
        capturing = true
        val shotQuad = frame.quad
        scope.launch {
            flash.snapTo(0.8f)
            flash.animateTo(0f, tween(250))
        }
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    synchronized(controller) { controller.onCaptured(shotQuad) }
                    capturing = false
                    currentOnCaptured(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("ScanPDF", "capture failed", exception)
                    synchronized(controller) { controller.onCaptured(null) }
                    capturing = false
                    currentOnError("Chụp thất bại, hãy thử lại")
                }
            },
        )
    }

    val currentTakePicture by rememberUpdatedState(::takePicture)
    val currentAutoMode by rememberUpdatedState(autoMode)

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val gray = CameraFrames.uprightGray(image)
                val aspect = gray.cols().toFloat() / gray.rows()
                val detection = try {
                    DocumentDetector.detectGray(gray, allowDeskew = false)
                } finally {
                    gray.release()
                }
                val quad = detection?.quad
                val now = android.os.SystemClock.elapsedRealtime()
                val (hint, focusNow) = synchronized(controller) {
                    controller.update(quad, now) to controller.justBecameStable(now)
                }
                mainExecutor.execute {
                    frame = FrameResult(quad, aspect, hint)
                    // Focus on the page centre as soon as it is held still (CamScanner focuses on the
                    // document rather than trusting continuous AF); the 800 ms hold covers the focus time.
                    if (focusNow && quad != null && now - lastFocusAt > 2000) {
                        lastFocusAt = now
                        val r = frameRect(aspect)
                        val cx = quad.points().map { it.x }.average().toFloat()
                        val cy = quad.points().map { it.y }.average().toFloat()
                        focusAt(r.left + cx * r.width, r.top + cy * r.height)
                    }
                    if (hint == CaptureHint.CAPTURE && currentAutoMode) currentTakePicture()
                }
            } catch (e: Exception) {
                Log.w("ScanPDF", "frame analysis failed", e)
            } finally {
                image.close()
            }
        }
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build())
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
                camera?.cameraControl?.enableTorch(torch)
            } catch (e: Exception) {
                Log.e("ScanPDF", "camera start failed", e)
                currentOnError("Không mở được camera")
            }
        }, mainExecutor)
        onDispose {
            analysis.clearAnalyzer()
            runCatching { providerFuture.get().unbindAll() }
            camera = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    // Smoothly animated outline (8 coordinates), so it glides instead of jumping between frames.
    val target = frame.quad
    val coords = target?.points()?.flatMap { listOf(it.x, it.y) }
    val animated = (0 until 8).map { i ->
        animateFloatAsState(coords?.get(i) ?: 0.5f, tween(120), label = "corner$i").value
    }
    val outlineAlpha by animateFloatAsState(if (target != null) 1f else 0f, tween(200), label = "outline")
    val hint = rememberSteadyHint(frame.hint)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        focusRing = pos
                        focusAt(pos.x, pos.y)
                    }
                },
        ) {
            val r = frameRect(frame.aspect)
            if (outlineAlpha > 0.01f && r.width > 0f) {
                val color = when (hint) {
                    CaptureHint.CLOSER -> Color(0xFFFFB300)
                    CaptureHint.CAPTURE, CaptureHint.NEXT_PAGE -> Color(0xFF66BB6A)
                    else -> Color(0xFF42A5F5)
                }.copy(alpha = outlineAlpha)
                val pts = (0 until 4).map { Offset(r.left + animated[it * 2] * r.width, r.top + animated[it * 2 + 1] * r.height) }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                    close()
                }
                drawPath(path, color.copy(alpha = 0.22f * outlineAlpha))
                drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
            }
            focusRing?.let { drawCircle(Color.White, radius = 36.dp.toPx(), center = it, style = Stroke(2.dp.toPx())) }
            if (flash.value > 0f) drawRect(Color.White.copy(alpha = flash.value))
        }
        LaunchedEffect(focusRing) {
            if (focusRing != null) {
                kotlinx.coroutines.delay(900)
                focusRing = null
            }
        }

        // Top bar.
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Đóng", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                TextButton(onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }) { Text(if (torch) "Đèn: Bật" else "Đèn: Tắt", color = Color.White) }
            }
            TextButton(onClick = { autoMode = !autoMode }) {
                Text(if (autoMode) "Tự chụp: Bật" else "Tự chụp: Tắt", color = if (autoMode) Color(0xFF66BB6A) else Color.White)
            }
        }

        // Guidance pill.
        Text(
            hintText(hint, autoMode),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp)
                .background(Color(0x99000000), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )

        // Bottom bar: last page + count, shutter, done.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                    .clickable(enabled = pageCount > 0, onClick = onDone),
                contentAlignment = Alignment.Center,
            ) {
                if (lastThumb != null) {
                    FileImage(lastThumb, contentDescription = "Trang vừa chụp", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (pendingCount > 0) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                val total = pageCount + pendingCount
                if (total > 0) {
                    Text(
                        "$total",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }

            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .background(if (capturing) Color.Gray else Color.White, CircleShape)
                    .clickable(enabled = !capturing) { takePicture() },
            )

            TextButton(onClick = onDone, modifier = Modifier.width(72.dp)) { Text("Xong", color = Color.White) }
        }
    }
}

/** Keeps each hint on screen for a moment so the text does not flicker (CamScanner holds tips ~1 s). */
@Composable
private fun rememberSteadyHint(hint: CaptureHint): CaptureHint {
    var shown by remember { mutableStateOf(hint) }
    var shownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(hint) {
        if (hint == shown) return@LaunchedEffect
        if (hint != CaptureHint.CAPTURE) {
            val wait = 700 - (android.os.SystemClock.elapsedRealtime() - shownAt)
            if (wait > 0) kotlinx.coroutines.delay(wait)
        }
        shown = hint
        shownAt = android.os.SystemClock.elapsedRealtime()
    }
    return shown
}

private fun hintText(hint: CaptureHint, auto: Boolean): String = when (hint) {
    CaptureHint.SEARCH -> "Đưa cả tờ giấy vào khung, chừa một ít nền xung quanh"
    CaptureHint.CLOSER -> "Lại gần hơn"
    CaptureHint.HOLD_STILL -> if (auto) "Giữ yên máy…" else "Đã thấy tài liệu, bấm nút để chụp"
    CaptureHint.CAPTURE -> if (auto) "Đang chụp…" else "Đã thấy tài liệu, bấm nút để chụp"
    CaptureHint.NEXT_PAGE -> "Đã chụp. Đặt trang tiếp theo vào khung"
}
