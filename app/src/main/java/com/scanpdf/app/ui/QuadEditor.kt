package com.scanpdf.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.scanpdf.app.model.Pt
import com.scanpdf.app.model.Quad
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows the photo with the document outline. Each corner can be dragged; while dragging,
 * a magnifier shows the area under the finger so the corner can be placed precisely.
 */
@Composable
fun QuadEditor(
    bitmap: Bitmap,
    quad: Quad,
    onQuadChange: (Quad) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF42A5F5),
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val currentQuad by rememberUpdatedState(quad)
    val currentOnChange by rememberUpdatedState(onQuadChange)
    var activeCorner by remember { mutableIntStateOf(-1) }
    var dragPoint by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val grabRadiusPx = with(density) { 40.dp.toPx() }

    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min(boxW / image.width, boxH / image.height)
        val drawW = image.width * scale
        val drawH = image.height * scale
        val left = (boxW - drawW) / 2f
        val top = (boxH - drawH) / 2f

        fun toScreen(p: Pt) = Offset(left + p.x * drawW, top + p.y * drawH)
        fun toNormalized(o: Offset) = Pt(
            ((o.x - left) / drawW).coerceIn(0f, 1f),
            ((o.y - top) / drawH).coerceIn(0f, 1f),
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(left, top, drawW, drawH) {
                    detectDragGestures(
                        onDragStart = { touch ->
                            val corners = currentQuad.points().map(::toScreen)
                            val nearest = corners.indices.minBy { (corners[it] - touch).getDistance() }
                            if ((corners[nearest] - touch).getDistance() <= grabRadiusPx) {
                                activeCorner = nearest
                                dragPoint = corners[nearest]
                            }
                        },
                        onDrag = { change, amount ->
                            if (activeCorner >= 0) {
                                change.consume()
                                // Track the corner locally so it never lags behind the finger.
                                dragPoint = toScreen(toNormalized(dragPoint + amount))
                                currentOnChange(currentQuad.withPoint(activeCorner, toNormalized(dragPoint)))
                            }
                        },
                        onDragEnd = { activeCorner = -1 },
                        onDragCancel = { activeCorner = -1 },
                    )
                },
        ) {
            val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
            val dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt())
            drawImage(image, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.Medium)

            val corners = quad.points().map(::toScreen)
            val outline = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                for (i in 1..3) lineTo(corners[i].x, corners[i].y)
                close()
            }
            drawPath(outline, accent.copy(alpha = 0.18f))
            drawPath(outline, accent, style = Stroke(width = 2.dp.toPx()))
            corners.forEachIndexed { i, c ->
                val r = if (i == activeCorner) 14.dp.toPx() else 11.dp.toPx()
                drawCircle(Color.White, radius = r, center = c)
                drawCircle(accent, radius = r, center = c, style = Stroke(width = 3.dp.toPx()))
            }

            if (activeCorner >= 0) {
                drawMagnifier(corners[activeCorner], outline, dstOffset, dstSize, image, accent)
            }
        }
    }
}

private fun DrawScope.drawMagnifier(
    focus: Offset,
    outline: Path,
    dstOffset: IntOffset,
    dstSize: IntSize,
    image: androidx.compose.ui.graphics.ImageBitmap,
    accent: Color,
) {
    val zoom = 2.5f
    val radius = 56.dp.toPx()
    val margin = 12.dp.toPx()
    // Keep the loupe away from the finger: top-left, or top-right when the finger is there.
    val onLeft = focus.x < size.width / 2f && focus.y < radius * 2 + margin * 2
    val center = Offset(
        if (onLeft) size.width - margin - radius else margin + radius,
        margin + radius,
    )
    val circle = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(center, radius))
    }
    clipPath(circle) {
        drawRect(Color.Black, topLeft = center - Offset(radius, radius), size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2))
        withTransform({
            translate(center.x - focus.x * zoom, center.y - focus.y * zoom)
            scale(zoom, zoom, pivot = Offset.Zero)
        }) {
            drawImage(image, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.High)
            drawPath(outline, accent, style = Stroke(width = 2.dp.toPx() / zoom))
        }
    }
    drawCircle(Color.White, radius = radius, center = center, style = Stroke(width = 3.dp.toPx()))
    val arm = 10.dp.toPx()
    drawLine(accent, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = 2.dp.toPx())
    drawLine(accent, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = 2.dp.toPx())
}
