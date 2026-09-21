package com.example.camera.tracking.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.example.camera.tracking.engine.AspectRatioCropEngine
import com.example.camera.tracking.engine.CropController
import com.example.camera.tracking.model.CropWindow
import com.example.camera.tracking.model.GimbalState
import com.example.camera.tracking.model.TrackedSubject
import com.example.camera.tracking.model.TrackingAspectRatio
import com.example.camera.tracking.model.TrackingStatus
import kotlin.math.roundToInt

/**
 * High-performance Viewfinder rendering the real-time 3x tracked digital crop.
 * Guarantees ABSOLUTELY ZERO horizontal or vertical stretching.
 * Maintains physically true proportions, exact aspect-ratio alignment,
 * and boundary safety.
 */
@Composable
fun TrackingViewfinder(
    currentFrame: Bitmap?,
    cropWindow: CropWindow,
    cropController: CropController,
    trackingStatus: TrackingStatus,
    activeSubject: TrackedSubject?,
    allDetections: List<TrackedSubject>,
    flashFeedback: Boolean,
    aspectRatio: TrackingAspectRatio = TrackingAspectRatio.RATIO_9_16,
    isGimbalEnabled: Boolean = false,
    gimbalState: GimbalState = GimbalState(),
    isCinematicPanActive: Boolean = false,
    cinematicPanProgress: Float = 0f,
    onTapToTrack: (normX: Float, normY: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val nativePaint = remember {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
    }

    var lastDstRectF by remember { mutableStateOf<android.graphics.RectF?>(null) }
    var lastSrcCropRect by remember { mutableStateOf<Rect?>(null) }
    var lastFrameW by remember { mutableIntStateOf(0) }
    var lastFrameH by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black)
            .testTag("tracking_viewfinder")
            .pointerInput(cropWindow, aspectRatio) {
                detectTapGestures { tapOffset ->
                    val dst = lastDstRectF
                    val src = lastSrcCropRect
                    val fw = lastFrameW
                    val fh = lastFrameH
                    if (dst != null && src != null && fw > 0 && fh > 0 && dst.width() > 0f && dst.height() > 0f) {
                        val normInDstX = ((tapOffset.x - dst.left) / dst.width()).coerceIn(0f, 1f)
                        val normInDstY = ((tapOffset.y - dst.top) / dst.height()).coerceIn(0f, 1f)
                        val srcX = ((src.left + normInDstX * src.width()) / fw.toFloat()).coerceIn(0f, 1f)
                        val srcY = ((src.top + normInDstY * src.height()) / fh.toFloat()).coerceIn(0f, 1f)
                        onTapToTrack(srcX, srcY)
                    } else {
                        val vfX = (tapOffset.x / size.width).coerceIn(0f, 1f)
                        val vfY = (tapOffset.y / size.height).coerceIn(0f, 1f)
                        onTapToTrack(vfX, vfY)
                    }
                }
            }
    ) {
        val viewWidthPx = constraints.maxWidth.toFloat()
        val viewHeightPx = constraints.maxHeight.toFloat()
        val viewAspect = viewWidthPx / viewHeightPx.coerceAtLeast(1f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (currentFrame != null && !currentFrame.isRecycled) {
                val chosenAspect = aspectRatio.ratioValue ?: viewAspect

                // 1. Calculate EXACT source crop rectangle without distortion
                val srcCropRect = AspectRatioCropEngine.calculateSourceCropRect(
                    srcWidth = currentFrame.width,
                    srcHeight = currentFrame.height,
                    cropWindow = cropWindow,
                    targetAspect = chosenAspect
                )

                // 2. Compute destination rectangle preserving aspect ratio (zero stretch)
                val cropAspect = srcCropRect.width().toFloat() / srcCropRect.height().toFloat()
                val dstRectF = AspectRatioCropEngine.calculateAspectPreservingDstRect(
                    viewWidth = size.width,
                    viewHeight = size.height,
                    cropAspect = cropAspect
                )

                lastDstRectF = dstRectF
                lastSrcCropRect = srcCropRect
                lastFrameW = currentFrame.width
                lastFrameH = currentFrame.height

                val dstRect = Rect(
                    dstRectF.left.roundToInt(),
                    dstRectF.top.roundToInt(),
                    dstRectF.right.roundToInt(),
                    dstRectF.bottom.roundToInt()
                )

                // 3. Draw cropped camera frame directly into canvas
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawBitmap(
                        currentFrame,
                        srcCropRect,
                        dstRect,
                        nativePaint
                    )
                }

                // Normalized crop bounds inside the source frame for accurate overlay placement
                val srcCropNormRect = android.graphics.RectF(
                    srcCropRect.left.toFloat() / currentFrame.width,
                    srcCropRect.top.toFloat() / currentFrame.height,
                    srcCropRect.right.toFloat() / currentFrame.width,
                    srcCropRect.bottom.toFloat() / currentFrame.height
                )

                // Aspect ratio framing mask (darken letterbox / pillarbox outside dstRect)
                if (dstRectF.top > 0f) {
                    drawRect(Color.Black.copy(alpha = 0.85f), Offset.Zero, Size(size.width, dstRectF.top))
                }
                if (dstRectF.bottom < size.height) {
                    drawRect(Color.Black.copy(alpha = 0.85f), Offset(0f, dstRectF.bottom), Size(size.width, size.height - dstRectF.bottom))
                }
                if (dstRectF.left > 0f) {
                    drawRect(Color.Black.copy(alpha = 0.85f), Offset.Zero, Size(dstRectF.left, size.height))
                }
                if (dstRectF.right < size.width) {
                    drawRect(Color.Black.copy(alpha = 0.85f), Offset(dstRectF.right, 0f), Size(size.width - dstRectF.right, size.height))
                }

                // Framing border
                drawRect(
                    color = Color(0x6638BDF8),
                    topLeft = Offset(dstRectF.left, dstRectF.top),
                    size = Size(dstRectF.width(), dstRectF.height()),
                    style = Stroke(width = 2f)
                )

                // 4. Draw other detected candidate subjects in scene with subtle outline
                allDetections.forEach { candidate ->
                    if (candidate.trackingId != activeSubject?.trackingId) {
                        drawCandidateBox(
                            candidate = candidate,
                            srcCropNormRect = srcCropNormRect,
                            dstRectF = dstRectF
                        )
                    }
                }

                // 5. Draw locked active subject tracking reticle
                if (activeSubject != null && trackingStatus != TrackingStatus.IDLE) {
                    drawActiveTrackingReticle(
                        subject = activeSubject,
                        status = trackingStatus,
                        srcCropNormRect = srcCropNormRect,
                        dstRectF = dstRectF
                    )
                }

                // 6. Draw Digital Gimbal artificial horizon & crosshairs if active
                if (isGimbalEnabled) {
                    drawGimbalHud(dstRectF, gimbalState)
                }

                // 7. Draw Cinematic Pan HUD if active
                if (isCinematicPanActive) {
                    drawCinematicPanHud(dstRectF, cinematicPanProgress)
                }

                // 8. Draw edge boundary indicator if crop is clamped
                drawBoundaryIndicators(cropWindow, dstRectF)
            } else {
                // Standby placeholder
                drawRect(Color(0xFF0F172A))
            }
        }

        // Shutter flash feedback
        AnimatedVisibility(
            visible = flashFeedback,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.85f))
            )
        }
    }
}

private fun DrawScope.drawCandidateBox(
    candidate: TrackedSubject,
    srcCropNormRect: android.graphics.RectF,
    dstRectF: android.graphics.RectF
) {
    // Map from source [0..1] to visible crop rectangle in dstRectF
    val normLeft = (candidate.bounds.left - srcCropNormRect.left) / srcCropNormRect.width()
    val normTop = (candidate.bounds.top - srcCropNormRect.top) / srcCropNormRect.height()
    val normRight = (candidate.bounds.right - srcCropNormRect.left) / srcCropNormRect.width()
    val normBottom = (candidate.bounds.bottom - srcCropNormRect.top) / srcCropNormRect.height()

    // Only draw if within visible crop region
    if (normRight < 0f || normLeft > 1f || normBottom < 0f || normTop > 1f) return

    val x = dstRectF.left + normLeft.coerceIn(0f, 1f) * dstRectF.width()
    val y = dstRectF.top + normTop.coerceIn(0f, 1f) * dstRectF.height()
    val w = ((normRight - normLeft) * dstRectF.width()).coerceAtLeast(20f)
    val h = ((normBottom - normTop) * dstRectF.height()).coerceAtLeast(20f)

    drawRect(
        color = Color(0x66FFFFFF),
        topLeft = Offset(x, y),
        size = Size(w, h),
        style = Stroke(
            width = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
        )
    )
}

private fun DrawScope.drawActiveTrackingReticle(
    subject: TrackedSubject,
    status: TrackingStatus,
    srcCropNormRect: android.graphics.RectF,
    dstRectF: android.graphics.RectF
) {
    // Map from source coordinates to current crop window in dstRectF
    val normLeft = (subject.bounds.left - srcCropNormRect.left) / srcCropNormRect.width()
    val normTop = (subject.bounds.top - srcCropNormRect.top) / srcCropNormRect.height()
    val normRight = (subject.bounds.right - srcCropNormRect.left) / srcCropNormRect.width()
    val normBottom = (subject.bounds.bottom - srcCropNormRect.top) / srcCropNormRect.height()

    val x = dstRectF.left + normLeft * dstRectF.width()
    val y = dstRectF.top + normTop * dstRectF.height()
    val w = (normRight - normLeft) * dstRectF.width()
    val h = (normBottom - normTop) * dstRectF.height()

    val reticleColor = when (status) {
        TrackingStatus.TRACKING_LOCKED -> Color(0xFF00E5FF) // Electric Cyan
        TrackingStatus.OCCLUDED_PREDICTING -> Color(0xFFFFD54F) // Subtle Amber
        TrackingStatus.LOST -> Color(0xFFFF8A80) // Soft Red
        else -> Color(0xFF38BDF8)
    }

    val cornerLen = (w.coerceAtMost(h) * 0.20f).coerceIn(12f, 24f)
    val strokeW = 2f

    // Top-Left Corner
    drawLine(reticleColor.copy(alpha = 0.85f), Offset(x, y), Offset(x + cornerLen, y), strokeWidth = strokeW)
    drawLine(reticleColor.copy(alpha = 0.85f), Offset(x, y), Offset(x, y + cornerLen), strokeWidth = strokeW)

    // Top-Right Corner
    drawLine(reticleColor.copy(alpha = 0.85f), Offset(x + w, y), Offset(x + w - cornerLen, y), strokeWidth = strokeW)
    drawLine(reticleColor.copy(alpha = 0.85f), Offset(x + w, y), Offset(x + w, y + cornerLen), strokeWidth = strokeW)

    // Bottom-Left Corner
    drawLine(reticleColor.copy(alpha = 0.85f), Offset(x, y + h), Offset(x + cornerLen, y + h), strokeWidth = strokeW)
    drawLine(reticleColor.copy(alpha = 0.85f), Offset(x, y + h), Offset(x, y + h - cornerLen), strokeWidth = strokeW)

    // Bottom-Right Corner
    drawLine(reticleColor.copy(alpha = 0.85f), Offset(x + w, y + h), Offset(x + w - cornerLen, y + h), strokeWidth = strokeW)
    drawLine(reticleColor.copy(alpha = 0.85f), Offset(x + w, y + h), Offset(x + w, y + h - cornerLen), strokeWidth = strokeW)

    // Subtle Center Dot
    val cx = x + w / 2f
    val cy = y + h / 2f
    drawCircle(
        color = reticleColor.copy(alpha = 0.7f),
        radius = 2.5f,
        center = Offset(cx, cy)
    )
}

private fun DrawScope.drawBoundaryIndicators(
    cropWindow: CropWindow,
    dstRectF: android.graphics.RectF
) {
    val margin = 0.02f
    val edgeColor = Color(0x66FF5252)

    // Left Edge
    if (cropWindow.left <= margin) {
        drawRect(
            color = edgeColor,
            topLeft = Offset(dstRectF.left, dstRectF.top),
            size = Size(6f, dstRectF.height())
        )
    }
    // Right Edge
    if (cropWindow.right >= 1f - margin) {
        drawRect(
            color = edgeColor,
            topLeft = Offset(dstRectF.right - 6f, dstRectF.top),
            size = Size(6f, dstRectF.height())
        )
    }
    // Top Edge
    if (cropWindow.top <= margin) {
        drawRect(
            color = edgeColor,
            topLeft = Offset(dstRectF.left, dstRectF.top),
            size = Size(dstRectF.width(), 6f)
        )
    }
    // Bottom Edge
    if (cropWindow.bottom >= 1f - margin) {
        drawRect(
            color = edgeColor,
            topLeft = Offset(dstRectF.left, dstRectF.bottom - 6f),
            size = Size(dstRectF.width(), 6f)
        )
    }
}

private fun DrawScope.drawGimbalHud(
    dstRectF: android.graphics.RectF,
    gimbalState: GimbalState
) {
    if (dstRectF.width() <= 10f || dstRectF.height() <= 10f) return
    val centerX = dstRectF.centerX()
    val centerY = dstRectF.centerY()
    if (!centerX.isFinite() || !centerY.isFinite()) return

    try {
        // 1. Center Gimbal Crosshair
        val crosshairColor = Color(0x9910B981)
        val len = 20f
        drawLine(crosshairColor, Offset(centerX - len, centerY), Offset(centerX + len, centerY), strokeWidth = 2f)
        drawLine(crosshairColor, Offset(centerX, centerY - len), Offset(centerX, centerY + len), strokeWidth = 2f)
        drawCircle(crosshairColor, radius = 5f, center = Offset(centerX, centerY), style = Stroke(1.5f))

        // 2. Artificial Horizon Level Bar
        val safeRoll = if (gimbalState.rollAngle.isFinite()) gimbalState.rollAngle else 0f
        val angleRad = Math.toRadians(safeRoll.toDouble()).toFloat()
        val barHalfLen = (dstRectF.width() * 0.22f).coerceIn(10f, dstRectF.width() * 0.45f)
        val cosA = kotlin.math.cos(angleRad) * barHalfLen
        val sinA = kotlin.math.sin(angleRad) * barHalfLen
        val horizonColor = if (kotlin.math.abs(safeRoll) < 2.0f) Color(0xFF10B981) else Color(0xFFF59E0B)

        val startX = centerX - cosA
        val startY = centerY - sinA
        val endX = centerX + cosA
        val endY = centerY + sinA

        if (startX.isFinite() && startY.isFinite() && endX.isFinite() && endY.isFinite()) {
            val startOffset = Offset(startX, startY)
            val endOffset = Offset(endX, endY)

            // Horizon line
            drawLine(
                color = horizonColor.copy(alpha = 0.75f),
                start = startOffset,
                end = endOffset,
                strokeWidth = 2.5f
            )

            // Small pitch angle ticks
            val tickLen = 8f
            val perpX = -kotlin.math.sin(angleRad) * tickLen
            val perpY = kotlin.math.cos(angleRad) * tickLen
            if (perpX.isFinite() && perpY.isFinite()) {
                drawLine(horizonColor, startOffset, Offset(startOffset.x + perpX, startOffset.y + perpY), 2f)
                drawLine(horizonColor, endOffset, Offset(endOffset.x + perpX, endOffset.y + perpY), 2f)
            }
        }

        // Center bubble showing shake compensation
        val safeOffsetX = if (gimbalState.offsetX.isFinite()) gimbalState.offsetX else 0f
        val safeOffsetY = if (gimbalState.offsetY.isFinite()) gimbalState.offsetY else 0f
        val bubbleX = (centerX - (safeOffsetX * dstRectF.width() * 1.5f)).coerceIn(dstRectF.left + 8f, dstRectF.right - 8f)
        val bubbleY = (centerY - (safeOffsetY * dstRectF.height() * 1.5f)).coerceIn(dstRectF.top + 8f, dstRectF.bottom - 8f)
        if (bubbleX.isFinite() && bubbleY.isFinite()) {
            drawCircle(Color(0xCC10B981), radius = 6f, center = Offset(bubbleX, bubbleY))
        }
    } catch (_: Exception) {
        // Safe fallback - avoid canvas crash
    }
}

private fun DrawScope.drawCinematicPanHud(
    dstRectF: android.graphics.RectF,
    progress: Float
) {
    val progressClamped = progress.coerceIn(0f, 1f)
    val sweepX = dstRectF.left + dstRectF.width() * progressClamped
    val y = dstRectF.bottom - 40f

    // Pan sweep guide line
    drawLine(
        color = Color(0x886366F1),
        start = Offset(dstRectF.left + 20f, y),
        end = Offset(dstRectF.right - 20f, y),
        strokeWidth = 3f
    )

    // Active sweep cursor
    drawCircle(
        color = Color(0xFF818CF8),
        radius = 8f,
        center = Offset(sweepX.coerceIn(dstRectF.left + 20f, dstRectF.right - 20f), y)
    )

    // Sweep vertical scanner beam
    drawLine(
        color = Color(0x55818CF8),
        start = Offset(sweepX, dstRectF.top),
        end = Offset(sweepX, dstRectF.bottom),
        strokeWidth = 1.5f
    )
}
