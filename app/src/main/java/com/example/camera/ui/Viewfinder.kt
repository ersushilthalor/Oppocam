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
    floatingWindowBlurStrength: Float = 24.0f,
    onSurfaceTextureAvailable: (SurfaceTexture?) -> Unit,
    onSurfaceTextureSizeChanged: ((SurfaceTexture, Int, Int) -> Unit)? = null,
    onTapToFocus: (Offset, Float, Float) -> Unit,
    onZoomChange: (Float) -> Unit,
    onExposureCompensationChange: (Int) -> Unit = {},
    onToggleLock: () -> Unit = {},
    currentExposureCompensation: Int = 0,
    onFrameLuminanceStats: ((com.example.camera.engine.FrameLuminanceStats) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var currentScale by remember { mutableFloatStateOf(1.0f) }
    var isZoomBarVisible by remember { mutableStateOf(false) }
    var zoomHideJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("viewfinder_container")
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        // Enforce fixed aspect ratios strictly dictated by mode:
        // - Photo mode: fixed 3:4 (portrait 3:4 -> height / width = 4 / 3)
        // - Portrait mode: fixed 3:4 (portrait 3:4 -> height / width = 4 / 3)
        // - All other modes (Video, Cinema, Night, Dolly Zoom, More, etc.): fixed 9:16 (portrait 9:16 -> height / width = 16 / 9)
        val targetRatio = when (cameraMode) {
            CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.HUMAN_VISION -> 4f / 3f
            else -> 16f / 9f
        }

        // Viewfinder spans dimensions dictated strictly by the mode's native aspect ratio
        val (targetWidth, targetHeight) = if (containerWidth * targetRatio <= containerHeight) {
            containerWidth to (containerWidth * targetRatio)
        } else {
            (containerHeight / targetRatio) to containerHeight
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = targetWidth, height = targetHeight)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            var changed = false
                            if (zoom != 1f) {
                                currentScale = (currentScale * zoom).coerceIn(0.5f, 10.0f)
                                changed = true
                            }
                            // Horizontal swipe: Right to Left (pan.x < 0) zooms in; Left to Right (pan.x > 0) zooms out
                            if (abs(pan.x) > abs(pan.y) && abs(pan.x) > 1.5f) {
                                val zoomDelta = -pan.x / 140f
                                currentScale = (currentScale + zoomDelta).coerceIn(0.5f, 10.0f)
                                changed = true
                            }
                            if (changed) {
                                onZoomChange(currentScale)
                                isZoomBarVisible = true
                                zoomHideJob?.cancel()
                                zoomHideJob = coroutineScope.launch {
                                    delay(1000)
                                    isZoomBarVisible = false
                                }
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
                                onToggleLock()
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
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    onSurfaceTextureAvailable(st)
                                    onSurfaceTextureSizeChanged?.invoke(st, w, h)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
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

                                    if (cameraMode == CameraMode.CINEMA && onFrameLuminanceStats != null) {
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
                        val effectiveLut = activeLut ?: cinemaConfig?.selectedLut

                        val colorMatrix = android.graphics.ColorMatrix()
                        var hasFilter = false

                        if (cameraMode == CameraMode.CINEMA && cinemaConfig != null) {
                            val cinemaMatrix = com.example.camera.engine.CinemaColorPipeline.computeCinemaColorMatrix(
                                config = cinemaConfig,
                                rec2020Params = rec2020AutoToneParams
                            )
                            if (cinemaMatrix != null) {
                                colorMatrix.postConcat(cinemaMatrix)
                                hasFilter = true
                            }
                        } else if (cameraMode == CameraMode.PHOTO && activePhotoFilter != null && activePhotoFilter != PhotoFilter.ORIGINAL) {
                            val filterMat = activePhotoFilter.toAndroidColorMatrix()
                            if (filterMat != null) {
                                colorMatrix.postConcat(filterMat)
                                hasFilter = true
                            }
                        } else if (cameraMode == CameraMode.PORTRAIT && portraitConfig != null) {
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

                // Minimal Zoom Bar HUD overlay (auto-hides after 1s of inactivity)
                AnimatedVisibility(
                    visible = isZoomBarVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 76.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xDD111827),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        modifier = Modifier.testTag("viewfinder_minimal_zoom_bar")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = String.format(java.util.Locale.US, "%.1f×", currentScale),
                                color = Color(0xFFFFD54F),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            // Sleek minimal slider track indicator
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.25f))
                            ) {
                                val normProgress = ((currentScale - 0.5f) / (10.0f - 0.5f)).coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(normProgress)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color(0xFFFFD54F))
                                )
                            }
                        }
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

            // Lock Indicator Badge (tap to toggle lock)
            if (isAeLocked || isAfLocked) {
                Row(
                    modifier = Modifier
                        .offset(y = 44.dp)
                        .clip(CircleShape)
                        .background(Color(0xEE000000))
                        .border(1.dp, Color(0xFFFFD54F).copy(alpha = 0.5f), CircleShape)
                        .clickable { onLockClick() }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (isAeLocked && isAfLocked) "AE/AF LOCK" else if (isAeLocked) "AE LOCK" else "AF LOCK",
                        color = Color(0xFFFFD54F),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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


