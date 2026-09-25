package com.example.camera.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Size as CameraSize
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import kotlin.math.pow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.Bitmap
import com.example.camera.model.CameraMode
import com.example.camera.model.CinemaColorProfile
import com.example.camera.model.CinemaConfig
import com.example.camera.model.CinematicLut
import com.example.camera.model.GridType
import com.example.camera.model.LogBitDepth
import com.example.camera.model.PhotoFilter
import com.example.camera.model.PortraitConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun Viewfinder(
    aspectRatio: Float,
    gridType: GridType,
    focusRingPoint: Offset?,
    isAeLocked: Boolean,
    isAfLocked: Boolean,
    isFrontCamera: Boolean = false,
    cameraMode: CameraMode = CameraMode.PHOTO,
    previewBufferSize: CameraSize? = null,
    sensorOrientation: Int = 90,
    activePhotoFilter: PhotoFilter? = null,
    activeLut: CinematicLut? = null,
    isLutPreviewEnabled: Boolean = false,
    cinemaConfig: CinemaConfig? = null,
    portraitConfig: PortraitConfig? = null,
    videoAdjustments: com.example.camera.model.VideoAdjustments? = null,
    rec2020AutoToneParams: com.example.camera.engine.Rec2020AutoToneParams? = null,
    proSaturation: Float = 0f,
    proContrast: Float = 1.0f,
    proHighlights: Float = 0f,
    proShadows: Float = 0f,
    isProModeActive: Boolean = false,
    floatingWindowBlurStrength: Float = 24.0f,
    onSurfaceTextureAvailable: (SurfaceTexture?) -> Unit,
    onSurfaceTextureSizeChanged: ((SurfaceTexture, Int, Int) -> Unit)? = null,
    onTapToFocus: (Offset, Float, Float) -> Unit,
    onZoomChange: (Float) -> Unit,
    currentZoom: Float = 1.0f,
    minZoom: Float = 0.5f,
    maxZoom: Float = 10.0f,
    onZoomPresetTap: (Float) -> Unit = {},
    onExposureCompensationChange: (Int) -> Unit = {},
    onToggleLock: () -> Unit = {},
    currentExposureCompensation: Int = 0,
    onFrameLuminanceStats: ((com.example.camera.engine.FrameLuminanceStats) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var currentScale by remember(currentZoom) { mutableFloatStateOf(currentZoom) }

    LaunchedEffect(currentZoom) {
        currentScale = currentZoom
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("viewfinder_container")
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        // Native viewfinder uses a stable 9:16 portrait frame across all modes
        val targetRatio = 16f / 9f
        val currentTargetRatio by rememberUpdatedState(targetRatio)
        val currentPreviewBufferSize by rememberUpdatedState(previewBufferSize)

        // Viewfinder spans dimensions dictated by the 9:16 portrait frame
        val (targetWidth, targetHeight) = if (containerWidth * targetRatio <= containerHeight) {
            containerWidth to (containerWidth * targetRatio)
        } else {
            (containerHeight / targetRatio) to containerHeight
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = if (isProModeActive) Alignment.TopCenter else Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .then(if (isProModeActive) Modifier.padding(top = 52.dp) else Modifier)
                    .size(width = targetWidth, height = targetHeight)
                    .clipToBounds()
                    .pointerInput(minZoom, maxZoom) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            var changed = false
                            var updated = currentScale
                            if (zoom != 1f) {
                                updated = (updated * zoom).coerceIn(minZoom, maxZoom)
                                changed = true
                            }
                            if (abs(pan.x) > abs(pan.y) * 1.15f && abs(pan.x) > 1.5f) {
                                val factor = 1.0f - (pan.x / 260f)
                                updated = (updated * factor).coerceIn(minZoom, maxZoom)
                                changed = true
                            }
                            if (changed) {
                                val rounded = (updated * 10f).roundToInt() / 10f
                                currentScale = rounded
                                onZoomChange(rounded)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                onTapToFocus(offset, normX, normY)
                            },
                            onLongPress = { offset ->
                                val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                onTapToFocus(offset, normX, normY)
                            }
                        )
                    }
            ) {
                // 100% Native Camera2 TextureView Preview:
                // Correctly match Camera2 buffer dimensions with the preview view dimensions
                // using proper center-crop/fit transform so the preview occupies the intended
                // aspect ratio area without the huge black region.
                AndroidView(
                    factory = { context ->
                        var lastLumaSampleTime = 0L
                        var lumaSampleBitmap: Bitmap? = null
                        TextureView(context).apply {
                            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                                val newW = right - left
                                val newH = bottom - top
                                if (newW > 0 && newH > 0) {
                                    updateTextureViewTransform(this, currentPreviewBufferSize, currentTargetRatio)
                                }
                            }
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    updateTextureViewTransform(this@apply, currentPreviewBufferSize, currentTargetRatio)
                                    onSurfaceTextureAvailable(st)
                                    onSurfaceTextureSizeChanged?.invoke(st, w, h)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    updateTextureViewTransform(this@apply, currentPreviewBufferSize, currentTargetRatio)
                                    onSurfaceTextureSizeChanged?.invoke(st, w, h)
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    onSurfaceTextureAvailable(null)
                                    try {
                                        lumaSampleBitmap?.recycle()
                                        lumaSampleBitmap = null
                                    } catch (ignored: Exception) {}
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                    // Real-time backdrop blur sampling for all floating windows & popups across the app
                                    if (com.example.camera.ui.components.BackdropBlurManager.isWindowActive) {
                                        com.example.camera.ui.components.BackdropBlurManager.onViewfinderFrame(this@apply, floatingWindowBlurStrength)
                                    }

                                    if ((cameraMode == CameraMode.CINEMA || cameraMode == CameraMode.PHOTO) && onFrameLuminanceStats != null) {
                                        val now = android.os.SystemClock.uptimeMillis()
                                        if (now - lastLumaSampleTime >= 100L) { // 10fps analysis rate
                                            lastLumaSampleTime = now
                                            try {
                                                if (lumaSampleBitmap == null || lumaSampleBitmap?.isRecycled == true) {
                                                    lumaSampleBitmap = Bitmap.createBitmap(
                                                        com.example.camera.engine.FrameLuminanceAnalyzer.SAMPLE_WIDTH,
                                                        com.example.camera.engine.FrameLuminanceAnalyzer.SAMPLE_HEIGHT,
                                                        Bitmap.Config.ARGB_8888
                                                    )
                                                }
                                                lumaSampleBitmap?.let { bmp ->
                                                    getBitmap(bmp)
                                                    val stats = com.example.camera.engine.FrameLuminanceAnalyzer.analyzeBitmap(bmp)
                                                    if (stats != null) {
                                                        onFrameLuminanceStats.invoke(stats)
                                                    }
                                                }
                                            } catch (ignored: Exception) {}
                                        }
                                    }
                                }
                            }
                        }
                    },
                    update = { textureView ->
                        // Dynamically synchronize TextureView transformation with current dimensions and buffer size
                        val viewW = textureView.width.toFloat()
                        val viewH = textureView.height.toFloat()
                        if (viewW > 0f && viewH > 0f) {
                            updateTextureViewTransform(textureView, previewBufferSize, targetRatio)
                        }

                        val effectiveLut = activeLut ?: cinemaConfig?.selectedLut

                        val colorMatrix = android.graphics.ColorMatrix()
                        var hasFilter = false

                        if (cameraMode == CameraMode.PHOTO) {
                            // Completely isolate Photo Mode from video adjustments and video pipelines
                            com.example.camera.engine.VideoAdjustmentsPipeline.clearAdjustments(textureView)
                            if (activePhotoFilter != null && activePhotoFilter != PhotoFilter.ORIGINAL) {
                                val filterMat = activePhotoFilter.toAndroidColorMatrix()
                                if (filterMat != null) {
                                    colorMatrix.postConcat(filterMat)
                                    hasFilter = true
                                }
                            }
                            // Real-time Pro adjustments: Saturation
                            if (proSaturation != 0f) {
                                val satMat = android.graphics.ColorMatrix().apply {
                                    setSaturation((1f + proSaturation / 100f).coerceIn(0f, 3f))
                                }
                                colorMatrix.postConcat(satMat)
                                hasFilter = true
                            }
                            // Real-time Pro adjustments: Contrast & Highlights / Shadows
                            if (proContrast != 1.0f || proHighlights != 0f || proShadows != 0f) {
                                val c = proContrast.coerceIn(0.5f, 2.0f)
                                val b = ((proHighlights + proShadows) / 4f)
                                val t = (1f - c) * 128f + b
                                val contrastMat = android.graphics.ColorMatrix(floatArrayOf(
                                    c, 0f, 0f, 0f, t,
                                    0f, c, 0f, 0f, t,
                                    0f, 0f, c, 0f, t,
                                    0f, 0f, 0f, 1f, 0f
                                ))
                                colorMatrix.postConcat(contrastMat)
                                hasFilter = true
                            }
                        } else if (cameraMode == CameraMode.CINEMA) {
                            // Cinema mode renders the authentic 3D LUT and color grading directly
                            // on the GPU in CameraStreamCompositor. No UI-layer ColorMatrix RenderEffect is applied.
                            com.example.camera.engine.VideoAdjustmentsPipeline.clearAdjustments(textureView)
                        } else if (cameraMode == CameraMode.PORTRAIT && portraitConfig != null) {
                            com.example.camera.engine.VideoAdjustmentsPipeline.clearAdjustments(textureView)
                            val style = portraitConfig.selectedStyle
                            if (style.isZeissOptical) {
                                // Real ZEISS T* anti-reflective micro-contrast & deep clean blacks
                                val zeissMat = android.graphics.ColorMatrix(floatArrayOf(
                                    1.05f, 0.01f, -0.01f, 0f, -2f,
                                    0.01f, 1.04f, -0.01f, 0f, -2f,
                                    -0.01f, -0.01f, 1.03f, 0f, -1f,
                                    0f, 0f, 0f, 1f, 0f
                                ))
                                colorMatrix.postConcat(zeissMat)
                                hasFilter = true
                            } else if (style.isLeicaOptical) {
                                // Real Leica 3D Pop: Rich organic midtones, velvety blacks, authentic European skin tonality
                                val leicaMat = android.graphics.ColorMatrix(floatArrayOf(
                                    1.06f, -0.01f, -0.01f, 0f, -3f,
                                    -0.01f, 1.05f, -0.01f, 0f, -3f,
                                    -0.01f, -0.01f, 1.04f, 0f, -2f,
                                    0f, 0f, 0f, 1f, 0f
                                ))
                                colorMatrix.postConcat(leicaMat)
                                hasFilter = true
                            }
                        } else if (cameraMode == CameraMode.VIDEO) {
                            com.example.camera.engine.VideoAdjustmentsPipeline.applyToView(textureView, videoAdjustments)
                            return@AndroidView
                        } else {
                            com.example.camera.engine.VideoAdjustmentsPipeline.clearAdjustments(textureView)
                        }

                        if (hasFilter) {
                            val paint = android.graphics.Paint()
                            paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
                            textureView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
                        } else {
                            textureView.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Clean Cinematic LUT Active Badge
                val badgeLut = activeLut ?: cinemaConfig?.selectedLut
                if (cameraMode == CameraMode.CINEMA && badgeLut != null && badgeLut != CinematicLut.NONE) {
                    val displayLabel = if (badgeLut == CinematicLut.CUSTOM) {
                        cinemaConfig?.customLutName ?: badgeLut.label
                    } else {
                        badgeLut.label
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC0D0F18))
                            .border(1.dp, badgeLut.accentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(badgeLut.accentColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = displayLabel.uppercase(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                } else if (cameraMode == CameraMode.PORTRAIT && portraitConfig != null && (portraitConfig.selectedStyle.isZeissOptical || portraitConfig.selectedStyle.isLeicaOptical)) {
                    val style = portraitConfig.selectedStyle
                    val badgeColor = if (style.isZeissOptical) Color(0xFF0070D2) else Color(0xFFE00000)
                    val badgeName = if (style.isZeissOptical) "ZEISS T*" else "LEICA"
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xDD0D0F18))
                            .border(1.dp, badgeColor.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "$badgeName • ${style.title}".uppercase(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                } else if (cameraMode == CameraMode.PHOTO && activePhotoFilter != null && activePhotoFilter != PhotoFilter.ORIGINAL) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC111318))
                            .border(1.dp, activePhotoFilter.swatchColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "FILTER: ${activePhotoFilter.displayName}",
                            color = activePhotoFilter.swatchColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Grid Overlay
                if (gridType != GridType.NONE) {
                    CameraGridOverlay(gridType = gridType, modifier = Modifier.fillMaxSize())
                }

                // Tap to focus animated ring
                AnimatedVisibility(
                    visible = focusRingPoint != null,
                    enter = fadeIn() + scaleIn(initialScale = 1.3f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f)
                ) {
                    focusRingPoint?.let { point ->
                        FocusRingIndicator(
                            point = point,
                            isAeLocked = isAeLocked,
                            isAfLocked = isAfLocked,
                            exposureCompensation = currentExposureCompensation,
                            onExposureChange = onExposureCompensationChange,
                            onLockClick = onToggleLock
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FocusRingIndicator(
    point: Offset,
    isAeLocked: Boolean,
    isAfLocked: Boolean,
    exposureCompensation: Int = 0,
    onExposureChange: (Int) -> Unit = {},
    onLockClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "focusPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val density = androidx.compose.ui.platform.LocalDensity.current
    val ringSizePx = with(density) { 72.dp.toPx() }
    val offsetX = with(density) { (point.x - ringSizePx / 2).toDp() }
    val offsetY = with(density) { (point.y - ringSizePx / 2).toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .offset(x = offsetX, y = offsetY)
                .size(72.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val ringColor = if (isAeLocked || isAfLocked) Color(0xFFFFD54F) else Color(0xFFFFEB3B)
                drawCircle(
                    color = ringColor.copy(alpha = alpha),
                    radius = size.minDimension / 2f,
                    style = Stroke(width = 2.dp.toPx())
                )
                // Small crosshair in center
                drawLine(
                    color = ringColor.copy(alpha = 0.8f),
                    start = Offset(size.width / 2f - 6.dp.toPx(), size.height / 2f),
                    end = Offset(size.width / 2f + 6.dp.toPx(), size.height / 2f),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = ringColor.copy(alpha = 0.8f),
                    start = Offset(size.width / 2f, size.height / 2f - 6.dp.toPx()),
                    end = Offset(size.width / 2f, size.height / 2f + 6.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }
    }
}

@Composable
fun CameraGridOverlay(
    gridType: GridType,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val gridColor = Color.White.copy(alpha = 0.35f)
        val strokeWidth = 1.dp.toPx()

        when (gridType) {
            GridType.THIRDS -> {
                // Vertical lines
                drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth)
                drawLine(gridColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), strokeWidth)
                // Horizontal lines
                drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth)
                drawLine(gridColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), strokeWidth)
            }
            GridType.GOLDEN -> {
                val phi = 0.618f
                val left = w * (1f - phi)
                val right = w * phi
                val top = h * (1f - phi)
                val bottom = h * phi

                drawLine(gridColor, Offset(left, 0f), Offset(left, h), strokeWidth)
                drawLine(gridColor, Offset(right, 0f), Offset(right, h), strokeWidth)
                drawLine(gridColor, Offset(0f, top), Offset(w, top), strokeWidth)
                drawLine(gridColor, Offset(0f, bottom), Offset(w, bottom), strokeWidth)
            }
            GridType.SQUARE -> {
                val squareDim = minOf(w, h)
                val startX = (w - squareDim) / 2f
                val startY = (h - squareDim) / 2f
                drawRect(
                    color = gridColor,
                    topLeft = Offset(startX, startY),
                    size = Size(squareDim, squareDim),
                    style = Stroke(strokeWidth)
                )
            }
            GridType.LEVEL -> {
                // Center horizon line with dashed styling
                drawLine(
                    color = Color(0xFF64FFDA).copy(alpha = 0.75f),
                    start = Offset(w * 0.2f, h / 2f),
                    end = Offset(w * 0.8f, h / 2f),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
                )
                // Center level dot
                drawCircle(
                    color = Color(0xFF64FFDA),
                    radius = 3.dp.toPx(),
                    center = Offset(w / 2f, h / 2f)
                )
            }
            GridType.NONE -> {}
        }
    }
}

/**
 * Synchronizes the TextureView transformation matrix with the active camera buffer dimensions
 * and the Viewfinder layout aspect ratio.
 * Ensures uniform scaling (center-crop without distortion or non-uniform stretching)
 * and resets to identity when buffer aspect ratio matches the view aspect ratio.
 */
private fun updateTextureViewTransform(
    textureView: TextureView,
    previewBufferSize: CameraSize?,
    targetRatio: Float
) {
    val viewW = textureView.width.toFloat()
    val viewH = textureView.height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return

    val matrix = Matrix()
    val bufAspect = if (previewBufferSize != null && previewBufferSize.width > 0 && previewBufferSize.height > 0) {
        val bufPortraitW = minOf(previewBufferSize.width, previewBufferSize.height).toFloat()
        val bufPortraitH = maxOf(previewBufferSize.width, previewBufferSize.height).toFloat()
        bufPortraitH / bufPortraitW
    } else {
        4f / 3f // Native camera sensor preview is 3:4 portrait
    }

    val viewAspect = viewH / viewW
    val centerX = viewW / 2f
    val centerY = viewH / 2f

    // When view and buffer aspect ratios differ, apply uniform center-crop scaling
    // to strictly preserve original aspect ratio and prevent vertical stretching or distortion.
    if (kotlin.math.abs(bufAspect - viewAspect) > 0.005f) {
        if (viewAspect > bufAspect) {
            // View is taller than buffer (e.g. 9:16 view with 3:4 sensor buffer):
            // Scale horizontally around center to center-crop without vertical elongation.
            val scaleX = viewAspect / bufAspect
            val scaleY = 1.0f
            matrix.setScale(scaleX, scaleY, centerX, centerY)
        } else {
            // View is wider than buffer: scale vertically around center to center-crop.
            val scaleX = 1.0f
            val scaleY = bufAspect / viewAspect
            matrix.setScale(scaleX, scaleY, centerX, centerY)
        }
    }
    textureView.setTransform(matrix)
}


