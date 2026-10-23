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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.Bitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.model.CameraMode
import com.example.camera.model.CinemaColorProfile
import com.example.camera.model.CinemaConfig
import com.example.camera.model.CinematicLut
import com.example.camera.model.GridType
import com.example.camera.model.LogBitDepth
import com.example.camera.model.PhotoFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import com.hinnka.mycamera.camera.AspectRatio as PhotonAspectRatio
import com.hinnka.mycamera.camera.PreviewMeteringMapper
import com.hinnka.mycamera.camera.CameraCoordinateRect
import com.hinnka.mycamera.camera.PreviewMeteringZoomMode

fun resolvePhotonAspectRatio(
    aspectRatio: Float,
    cameraMode: CameraMode,
    cinemaConfig: CinemaConfig?
): PhotonAspectRatio {
    if (aspectRatio <= 0f) return PhotonAspectRatio.FULL
    if (kotlin.math.abs(aspectRatio - 1.0f) < 0.05f) return PhotonAspectRatio.RATIO_1_1
    if (kotlin.math.abs(aspectRatio - 4f / 3f) < 0.05f || kotlin.math.abs(aspectRatio - 3f / 4f) < 0.05f) return PhotonAspectRatio.RATIO_4_3
    if (kotlin.math.abs(aspectRatio - 16f / 9f) < 0.05f || kotlin.math.abs(aspectRatio - 9f / 16f) < 0.05f) return PhotonAspectRatio.RATIO_16_9
    if (kotlin.math.abs(aspectRatio - 3f / 2f) < 0.05f || kotlin.math.abs(aspectRatio - 2f / 3f) < 0.05f) return PhotonAspectRatio.RATIO_3_2
    if (kotlin.math.abs(aspectRatio - 21f / 9f) < 0.05f || kotlin.math.abs(aspectRatio - 9f / 21f) < 0.05f) return PhotonAspectRatio.RATIO_21_9
    if (kotlin.math.abs(aspectRatio - 65f / 24f) < 0.05f || kotlin.math.abs(aspectRatio - 24f / 65f) < 0.05f) return PhotonAspectRatio.XPAN

    return when (cameraMode) {
        CameraMode.VIDEO -> PhotonAspectRatio.RATIO_16_9
        CameraMode.CINEMA -> {
            val cinemaRatio = cinemaConfig?.aspectRatio?.ratioValue ?: 16f / 9f
            when {
                kotlin.math.abs(cinemaRatio - 1.43f) < 0.05f -> PhotonAspectRatio.RATIO_4_3
                kotlin.math.abs(cinemaRatio - 2.39f) < 0.05f -> PhotonAspectRatio.RATIO_21_9
                else -> PhotonAspectRatio.RATIO_16_9
            }
        }
        else -> PhotonAspectRatio.RATIO_4_3
    }
}

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
    /** Buffer geometry for the main/front TextureView stream. */
    mainPreviewBufferSize: CameraSize? = null,
    /** Buffer geometry for the separately streaming ultra-wide preview layer. */
    ultraWidePreviewBufferSize: CameraSize? = null,
    sensorOrientation: Int = 90,
    activePhotoFilter: PhotoFilter? = null,
    activeLut: CinematicLut? = null,
    isLutPreviewEnabled: Boolean = false,
    cinemaConfig: CinemaConfig? = null,
    videoAdjustments: com.example.camera.model.VideoAdjustments? = null,
    isGpuRelayPreviewActive: Boolean = false,
    proSaturation: Float = 0f,
    proContrast: Float = 1.0f,
    proHighlights: Float = 0f,
    proShadows: Float = 0f,
    isProModeActive: Boolean = false,
    floatingWindowBlurStrength: Float = 24.0f,
    isSettingsOpen: Boolean = false,
    viewfinderCornerRadiusDp: Int = 0,
    isCameraReady: Boolean = false,
    onSurfaceTextureAvailable: (SurfaceTexture?, Int, Int) -> Unit = { _, _, _ -> },
    onSurfaceTextureSizeChanged: ((SurfaceTexture, Int, Int) -> Unit)? = null,
    isUsingUltraWideSurface: Boolean = false,
    previewOverlapState: com.example.camera.engine.PreviewOverlapState = com.example.camera.engine.PreviewOverlapState(
        isOverlapping = false,
        mainAlpha = if (isUsingUltraWideSurface) 0f else 1f,
        ultraWideAlpha = if (isUsingUltraWideSurface) 1f else 0f
    ),
    onUltraWideSurfaceTextureAvailable: ((SurfaceTexture?, Int, Int) -> Unit)? = null,
    onUltraWideSurfaceTextureSizeChanged: ((SurfaceTexture, Int, Int) -> Unit)? = null,
    onPreviewFrameAvailable: ((com.example.camera.engine.PreviewStreamSource) -> Unit)? = null,
    onTapToFocus: (Offset, Float, Float) -> Unit,
    onZoomChange: (Float) -> Unit,
    currentZoom: Float = 1.0f,
    minZoom: Float = 0.5f,
    maxZoom: Float = 20.0f,
    onZoomPresetTap: (Float) -> Unit = {},
    onExposureCompensationChange: (Int) -> Unit = {},
    onToggleLock: () -> Unit = {},
    currentExposureCompensation: Int = 0,
    onFrameLuminanceStats: ((com.example.camera.engine.FrameLuminanceStats) -> Unit)? = null,
    stabilizationMode: com.example.camera.model.VideoStabilizationMode = com.example.camera.model.VideoStabilizationMode.EIS,
    doubleTapAction: String = "FLIP",
    onDoubleTapFlip: () -> Unit = {},
    onDoubleTapLock: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentScale by remember { mutableFloatStateOf(currentZoom) }

    var textureViewInstance by remember { mutableStateOf<TextureView?>(null) }
    var ultraWideTextureViewInstance by remember { mutableStateOf<TextureView?>(null) }
    val currentIsUsingUltraWideSurface by rememberUpdatedState(isUsingUltraWideSurface)
    val currentPreviewOverlapState by rememberUpdatedState(previewOverlapState)
    val currentCameraMode by rememberUpdatedState(cameraMode)
    val currentCinemaConfig by rememberUpdatedState(cinemaConfig)
    val currentOnFrameLuminanceStats by rememberUpdatedState(onFrameLuminanceStats)
    val currentFloatingWindowBlurStrength by rememberUpdatedState(floatingWindowBlurStrength)
    val ultraWideFramingCalib = remember {
        com.example.camera.engine.CameraOpticalCalibration.getUltraWideFramingCalibration()
    }
    val currentUwCalib = remember(currentZoom, ultraWideFramingCalib) {
        com.example.camera.engine.CameraOpticalCalibration.interpolateUltraWideFraming(
            uiZoom = currentZoom,
            calib = ultraWideFramingCalib
        )
    }

    LaunchedEffect(isUsingUltraWideSurface, textureViewInstance, ultraWideTextureViewInstance) {
        val activeTv = if (isUsingUltraWideSurface) ultraWideTextureViewInstance else textureViewInstance
        if (activeTv != null) {
            com.example.camera.ui.components.BackdropBlurManager.registerViewfinder(activeTv)
        }
    }



    LaunchedEffect(currentZoom) {
        currentScale = currentZoom
    }

    val targetRatioCalc = if (aspectRatio > 0f) {
        aspectRatio
    } else when (cameraMode) {
        CameraMode.CINEMA -> cinemaConfig?.aspectRatio?.ratioValue ?: 16f / 9f
        CameraMode.VIDEO -> 16f / 9f
        CameraMode.PHOTO -> 4f / 3f
        else -> 4f / 3f
    }

    val applyTransformToView = rememberUpdatedState { tv: TextureView?, isUltraWide: Boolean, w: Int, h: Int ->
        if (tv == null) return@rememberUpdatedState
        val viewW = if (w > 0) w else tv.width
        val viewH = if (h > 0) h else tv.height
        if (viewW <= 0 || viewH <= 0) return@rememberUpdatedState
        val calib = if (isUltraWide) currentUwCalib else com.example.camera.engine.CameraOpticalCalibration.OpticalFramingCalibration()
        // Main and ultra-wide TextureViews can be fed by different Camera2 output sizes.
        // Use the buffer geometry belonging to this stream, not the currently selected lens.
        val transformBufferSize = if (isUltraWide) {
            ultraWidePreviewBufferSize ?: previewBufferSize
        } else {
            mainPreviewBufferSize ?: previewBufferSize
        }
        val computedTargetRatio = if (viewW > 0 && viewH > 0) {
            val isLandscape = viewW > viewH
            if (isLandscape) {
                viewW.toFloat() / viewH.toFloat()
            } else {
                viewH.toFloat() / viewW.toFloat()
            }
        } else {
            targetRatioCalc
        }
        updateTextureViewTransform(
            textureView = tv,
            previewBufferSize = transformBufferSize,
            targetRatio = computedTargetRatio,
            sensorOrientation = sensorOrientation,
            viewWidth = viewW,
            viewHeight = viewH,
            opticalScale = calib.scaleCorrection,
            opticalOffsetX = calib.offsetXNorm,
            opticalOffsetY = calib.offsetYNorm
        )
    }
    LaunchedEffect(
        isSettingsOpen,
        textureViewInstance,
        ultraWideTextureViewInstance,
        previewBufferSize,
        mainPreviewBufferSize,
        ultraWidePreviewBufferSize,
        targetRatioCalc,
        cameraMode,
        sensorOrientation,
        isUsingUltraWideSurface
    ) {
        if (!isSettingsOpen) {
            // Apply after Compose has committed the restored layout. This also refreshes
            // matrices when Camera2 changes a stream's buffer size without resizing the view.
            withFrameNanos { }
            listOfNotNull(textureViewInstance, ultraWideTextureViewInstance).forEach { tv ->
                val isUw = (tv === ultraWideTextureViewInstance)
                if (tv.width > 0 && tv.height > 0) {
                    applyTransformToView.value(tv, isUw, tv.width, tv.height)
                }
            }
        }
    }

    // Dynamic preview stream visibility and smooth overlap blending
    LaunchedEffect(previewOverlapState, isUsingUltraWideSurface) {
        if (previewOverlapState.isOverlapping) {
            textureViewInstance?.let { tv ->
                tv.alpha = previewOverlapState.mainAlpha
                val mainOnTop = previewOverlapState.topSource == com.example.camera.engine.PreviewStreamSource.MAIN
                tv.translationZ = if (mainOnTop) 2f else 1f
                (tv.parent as? android.view.View)?.translationZ = if (mainOnTop) 2f else 1f
                tv.visibility = android.view.View.VISIBLE
            }
            ultraWideTextureViewInstance?.let { tv ->
                tv.alpha = previewOverlapState.ultraWideAlpha
                val ultraWideOnTop = previewOverlapState.topSource == com.example.camera.engine.PreviewStreamSource.ULTRAWIDE
                tv.translationZ = if (ultraWideOnTop) 2f else 1f
                (tv.parent as? android.view.View)?.translationZ = if (ultraWideOnTop) 2f else 1f
                tv.visibility = android.view.View.VISIBLE
            }
        } else {
            val isUw = isUsingUltraWideSurface
            textureViewInstance?.let { tv ->
                val mainAlpha = if (!isUw) 1f else 0f
                tv.alpha = mainAlpha
                tv.translationZ = if (!isUw) 2f else 0f
                (tv.parent as? android.view.View)?.translationZ = if (!isUw) 2f else 0f
                tv.visibility = android.view.View.VISIBLE
            }
            ultraWideTextureViewInstance?.let { tv ->
                val uwAlpha = if (isUw) 1f else 0f
                tv.alpha = uwAlpha
                tv.translationZ = if (isUw) 2f else 0f
                (tv.parent as? android.view.View)?.translationZ = if (isUw) 2f else 0f
                tv.visibility = android.view.View.VISIBLE
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("viewfinder_container")
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val isLandscapeContainer = containerWidth > containerHeight
        val density = androidx.compose.ui.platform.LocalDensity.current
        val topSafePadding = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val navBarBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        val availableHeight = (containerHeight - navBarBottom).coerceAtLeast(0.dp)
        val topBarBottom = 52.dp + topSafePadding

        val photonRatio = resolvePhotonAspectRatio(aspectRatio, cameraMode, cinemaConfig)
        val isXpan = (photonRatio == PhotonAspectRatio.XPAN)
        val isFull = (photonRatio == PhotonAspectRatio.FULL)
        val isSquare = (photonRatio == PhotonAspectRatio.RATIO_1_1)
        val isFourThree = (photonRatio == PhotonAspectRatio.RATIO_4_3)

        val viewfinderFramePadding = if (isXpan) 8.dp else 0.dp
        val sideControlSpace = if (isXpan) 16.dp else 0.dp
        val maxCardWidth = (containerWidth - sideControlSpace * 2).coerceAtLeast(viewfinderFramePadding)
        val maxCardHeight = if (isXpan) {
            (availableHeight - topBarBottom - 32.dp).coerceAtLeast(viewfinderFramePadding)
        } else {
            availableHeight
        }

        val maxViewfinderContentWidth = (maxCardWidth - viewfinderFramePadding).coerceAtLeast(0.dp)
        val maxViewfinderContentHeight = (maxCardHeight - viewfinderFramePadding).coerceAtLeast(0.dp)

        val (targetWidth, targetHeight, viewfinderTopOffset) = if (isLandscapeContainer) {
            val landscapeRatio = when (photonRatio) {
                PhotonAspectRatio.FULL -> containerWidth / containerHeight
                PhotonAspectRatio.XPAN -> 65f / 24f
                PhotonAspectRatio.RATIO_1_1 -> 1.0f
                PhotonAspectRatio.RATIO_4_3 -> 4f / 3f
                PhotonAspectRatio.RATIO_16_9 -> 16f / 9f
                PhotonAspectRatio.RATIO_3_2 -> 3f / 2f
                PhotonAspectRatio.RATIO_21_9 -> 21f / 9f
                else -> if (targetRatioCalc > 0f) maxOf(targetRatioCalc, 1f / targetRatioCalc) else 4f / 3f
            }
            if (isFull) {
                Triple(containerWidth, containerHeight, 0.dp)
            } else {
                val contentH = maxViewfinderContentHeight
                val contentW = minOf(maxViewfinderContentWidth, contentH * landscapeRatio)
                val cardW = contentW + viewfinderFramePadding
                val cardH = contentW / landscapeRatio + viewfinderFramePadding
                val top = ((containerHeight - cardH) / 2).coerceAtLeast(0.dp)
                Triple(cardW, cardH, top)
            }
        } else {
            // Portrait orientation: portraitRatio is width / height
            val portraitRatio = when (photonRatio) {
                PhotonAspectRatio.FULL -> containerWidth / containerHeight
                PhotonAspectRatio.RATIO_1_1 -> 1.0f
                PhotonAspectRatio.RATIO_4_3 -> 3f / 4f
                PhotonAspectRatio.RATIO_16_9 -> 9f / 16f
                PhotonAspectRatio.RATIO_3_2 -> 2f / 3f
                PhotonAspectRatio.RATIO_21_9 -> 9f / 21f
                PhotonAspectRatio.XPAN -> 24f / 65f
                else -> {
                    val r = if (targetRatioCalc > 0f) targetRatioCalc else 4f / 3f
                    if (r > 1.0f) 1f / r else r
                }
            }

            if (isFull) {
                Triple(containerWidth, containerHeight, 0.dp)
            } else if (isXpan) {
                val xpanW = (containerWidth - sideControlSpace * 2).coerceAtLeast(100.dp)
                val xpanH = xpanW / (65f / 24f)
                val top = ((availableHeight - xpanH) / 2).coerceAtLeast(topBarBottom)
                Triple(xpanW, xpanH, top)
            } else {
                val contentW = minOf(
                    maxViewfinderContentWidth,
                    maxViewfinderContentHeight * portraitRatio
                )
                val cardW = contentW + viewfinderFramePadding
                val cardH = contentW / portraitRatio + viewfinderFramePadding
                val placeBelowTopBar = (availableHeight - cardH) >= topBarBottom
                val extraSpacing = ((availableHeight - topBarBottom - cardH - 110.dp) / 2).coerceAtLeast(0.dp)
                val top = if (placeBelowTopBar) {
                    topBarBottom + extraSpacing
                } else {
                    ((availableHeight - cardH) / 2).coerceAtLeast(0.dp)
                }
                Triple(cardW, cardH, top)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = if (isLandscapeContainer) Alignment.Center else Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .then(
                        if (!isLandscapeContainer && viewfinderTopOffset > 0.dp) {
                            Modifier.offset(y = viewfinderTopOffset)
                        } else {
                            Modifier
                        }
                    )
                    .size(width = targetWidth, height = targetHeight)
                    .then(
                        if (viewfinderCornerRadiusDp > 0) {
                            Modifier.clip(RoundedCornerShape(viewfinderCornerRadiusDp.dp))
                        } else {
                            Modifier.clipToBounds()
                        }
                    )
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
                                currentScale = updated
                                onZoomChange(updated)
                            }
                        }
                    }
                    .pointerInput(doubleTapAction) {
                        detectTapGestures(
                            onDoubleTap = if (doubleTapAction != "NONE") { _ ->
                                when (doubleTapAction) {
                                    "FLIP", "FLIP_CAMERA" -> onDoubleTapFlip()
                                    "LOCK" -> onDoubleTapLock()
                                }
                            } else null,
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
                        var lastMotionSampleTime = 0L
                        var motionSampleBitmap: Bitmap? = null
                        TextureView(context).apply {
                            textureViewInstance = this
                            val isUw = currentIsUsingUltraWideSurface
                            alpha = if (!isUw) 1f else 0f
                            visibility = if (!isUw) android.view.View.VISIBLE else android.view.View.INVISIBLE
                            translationZ = if (!isUw) 2f else 1f
                            (this.parent as? android.view.View)?.translationZ = if (!isUw) 2f else 1f
                            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                                val newW = right - left
                                val newH = bottom - top
                                if (newW > 0 && newH > 0 && (newW != (oldRight - oldLeft) || newH != (oldBottom - oldTop))) {
                                    applyTransformToView.value(this, false, newW, newH)
                                }
                            }
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    val effectiveW = if (this@apply.width > 0) this@apply.width else w
                                    val effectiveH = if (this@apply.height > 0) this@apply.height else h
                                    onSurfaceTextureAvailable(st, effectiveW, effectiveH)
                                    onSurfaceTextureSizeChanged?.invoke(st, effectiveW, effectiveH)
                                    if (effectiveW > 0 && effectiveH > 0) {
                                        applyTransformToView.value(this@apply, false, effectiveW, effectiveH)
                                    }
                                    com.example.camera.ui.components.BackdropBlurManager.registerViewfinder(this@apply)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    val effectiveW = if (this@apply.width > 0) this@apply.width else w
                                    val effectiveH = if (this@apply.height > 0) this@apply.height else h
                                    onSurfaceTextureSizeChanged?.invoke(st, effectiveW, effectiveH)
                                    if (effectiveW > 0 && effectiveH > 0) {
                                        applyTransformToView.value(this@apply, false, effectiveW, effectiveH)
                                    }
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    onSurfaceTextureAvailable(null, 0, 0)
                                    try {
                                        lumaSampleBitmap?.recycle()
                                        lumaSampleBitmap = null
                                    } catch (ignored: Exception) {}
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                    onPreviewFrameAvailable?.invoke(com.example.camera.engine.PreviewStreamSource.MAIN)
                                    if (currentIsUsingUltraWideSurface) return

                                    val modeNow = currentCameraMode
                                    val lumaCallback = currentOnFrameLuminanceStats
                                    val needsLumaAnalysis = lumaCallback != null && (
                                        modeNow == CameraMode.PHOTO ||
                                        (modeNow == CameraMode.CINEMA && currentCinemaConfig?.colorProfile == CinemaColorProfile.HLG10)
                                    )
                                    if (needsLumaAnalysis && lumaCallback != null) {
                                        val now = android.os.SystemClock.uptimeMillis()
                                        val intervalMs = if (modeNow == CameraMode.CINEMA) 150L else 350L
                                        if (now - lastLumaSampleTime >= intervalMs) {
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
                                                        lumaCallback.invoke(stats)
                                                    }
                                                }
                                            } catch (ignored: Exception) {}
                                        }
                                    }

                                    // Real-time floating window backdrop blur sampling
                                    if (com.example.camera.ui.components.BackdropBlurManager.isWindowActive) {
                                        com.example.camera.ui.components.BackdropBlurManager.onViewfinderFrame(
                                            textureView = this@apply,
                                            blurStrength = currentFloatingWindowBlurStrength
                                        )
                                    }
                                }
                            }
                        }
                    },
                    update = { textureView ->
                        val isUw = currentIsUsingUltraWideSurface
                        val isOverlapping = currentPreviewOverlapState.isOverlapping
                        textureView.alpha = if (isOverlapping) currentPreviewOverlapState.mainAlpha else (if (!isUw) 1f else 0f)
                        textureView.visibility = if (isOverlapping || !isUw) android.view.View.VISIBLE else android.view.View.INVISIBLE
                        val mainOnTop = currentPreviewOverlapState.topSource == com.example.camera.engine.PreviewStreamSource.MAIN
                        textureView.translationZ = if (isOverlapping) (if (mainOnTop) 2f else 1f) else (if (!isUw) 2f else 1f)
                        (textureView.parent as? android.view.View)?.translationZ = if (isOverlapping) (if (mainOnTop) 2f else 1f) else (if (!isUw) 2f else 1f)
                        // Sample background blur frame when windows are active
                        if (!isUw && com.example.camera.ui.components.BackdropBlurManager.isWindowActive && textureView.isAvailable) {
                            com.example.camera.ui.components.BackdropBlurManager.onViewfinderFrame(
                                textureView = textureView,
                                blurStrength = floatingWindowBlurStrength
                            )
                        }

                        applyTextureViewOutlineAndEffects(
                            textureView = textureView,
                            viewfinderCornerRadiusDp = viewfinderCornerRadiusDp,
                            cameraMode = cameraMode,
                            activePhotoFilter = activePhotoFilter,
                            activeLut = activeLut,
                            cinemaConfig = cinemaConfig,
                            videoAdjustments = videoAdjustments,
                            isGpuRelayPreviewActive = isGpuRelayPreviewActive,
                            proSaturation = proSaturation,
                            proContrast = proContrast,
                            proHighlights = proHighlights,
                            proShadows = proShadows
                        )
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(
                            if (currentPreviewOverlapState.isOverlapping) {
                                if (currentPreviewOverlapState.topSource == com.example.camera.engine.PreviewStreamSource.MAIN) 2f else 1f
                            } else if (!currentIsUsingUltraWideSurface) 2f else 1f
                        )
                        .onGloballyPositioned { coordinates ->
                            val pos = coordinates.positionInRoot()
                            val sz = coordinates.size
                            com.example.camera.ui.components.BackdropBlurManager.updateViewfinderGeometry(
                                boundsInRoot = Rect(pos.x, pos.y, pos.x + sz.width, pos.y + sz.height),
                                rootSize = coordinates.findRootCoordinates().size
                            )
                        }
                )

                // Dedicated Ultra-Wide TextureView continuously configured at the exact same resolution
                // and aspect ratio as the active viewfinder for 0ms instant lens switching.
                if (onUltraWideSurfaceTextureAvailable != null) {
                    AndroidView(
                        factory = { context ->
                            var lastUwLumaSampleTime = 0L
                            var uwLumaSampleBitmap: Bitmap? = null
                            var lastUwMotionSampleTime = 0L
                            var uwMotionSampleBitmap: Bitmap? = null
                            TextureView(context).apply {
                                ultraWideTextureViewInstance = this
                                val isUw = currentIsUsingUltraWideSurface
                                alpha = if (isUw) 1f else 0f
                                visibility = android.view.View.VISIBLE
                                translationZ = if (isUw) 2f else 0f
                                (this.parent as? android.view.View)?.translationZ = if (isUw) 2f else 0f
                                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                                    val newW = right - left
                                    val newH = bottom - top
                                    if (newW > 0 && newH > 0 && (newW != (oldRight - oldLeft) || newH != (oldBottom - oldTop))) {
                                        applyTransformToView.value(this, true, newW, newH)
                                    }
                                }
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        val effectiveW = if (this@apply.width > 0) this@apply.width else w
                                        val effectiveH = if (this@apply.height > 0) this@apply.height else h
                                        onUltraWideSurfaceTextureAvailable.invoke(st, effectiveW, effectiveH)
                                        onUltraWideSurfaceTextureSizeChanged?.invoke(st, effectiveW, effectiveH)
                                        if (effectiveW > 0 && effectiveH > 0) {
                                            applyTransformToView.value(this@apply, true, effectiveW, effectiveH)
                                        }
                                        if (currentIsUsingUltraWideSurface) {
                                            com.example.camera.ui.components.BackdropBlurManager.registerViewfinder(this@apply)
                                        }
                                    }

                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                        val effectiveW = if (this@apply.width > 0) this@apply.width else w
                                        val effectiveH = if (this@apply.height > 0) this@apply.height else h
                                        onUltraWideSurfaceTextureSizeChanged?.invoke(st, effectiveW, effectiveH)
                                        if (effectiveW > 0 && effectiveH > 0) {
                                            applyTransformToView.value(this@apply, true, effectiveW, effectiveH)
                                        }
                                    }

                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        onUltraWideSurfaceTextureAvailable.invoke(null, 0, 0)
                                        try {
                                            uwLumaSampleBitmap?.recycle()
                                            uwLumaSampleBitmap = null
                                        } catch (ignored: Exception) {}
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                        onPreviewFrameAvailable?.invoke(com.example.camera.engine.PreviewStreamSource.ULTRAWIDE)
                                        if (!currentIsUsingUltraWideSurface) return

                                        val modeNow = currentCameraMode
                                        val lumaCallback = currentOnFrameLuminanceStats
                                        val needsLumaAnalysis = lumaCallback != null && (
                                            modeNow == CameraMode.PHOTO ||
                                            (modeNow == CameraMode.CINEMA && currentCinemaConfig?.colorProfile == CinemaColorProfile.HLG10)
                                        )
                                        if (needsLumaAnalysis && lumaCallback != null) {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            val intervalMs = if (modeNow == CameraMode.CINEMA) 150L else 350L
                                            if (now - lastUwLumaSampleTime >= intervalMs) {
                                                lastUwLumaSampleTime = now
                                                try {
                                                    if (uwLumaSampleBitmap == null || uwLumaSampleBitmap?.isRecycled == true) {
                                                        uwLumaSampleBitmap = Bitmap.createBitmap(
                                                            com.example.camera.engine.FrameLuminanceAnalyzer.SAMPLE_WIDTH,
                                                            com.example.camera.engine.FrameLuminanceAnalyzer.SAMPLE_HEIGHT,
                                                            Bitmap.Config.ARGB_8888
                                                        )
                                                    }
                                                    uwLumaSampleBitmap?.let { bmp ->
                                                        getBitmap(bmp)
                                                        val stats = com.example.camera.engine.FrameLuminanceAnalyzer.analyzeBitmap(bmp)
                                                        if (stats != null) {
                                                            lumaCallback.invoke(stats)
                                                        }
                                                    }
                                                } catch (ignored: Exception) {}
                                            }
                                        }

                                        if (com.example.camera.ui.components.BackdropBlurManager.isWindowActive) {
                                            com.example.camera.ui.components.BackdropBlurManager.onViewfinderFrame(
                                                textureView = this@apply,
                                                blurStrength = currentFloatingWindowBlurStrength
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        update = { textureView ->
                            val isUw = currentIsUsingUltraWideSurface
                            val isOverlapping = currentPreviewOverlapState.isOverlapping
                            textureView.alpha = if (isOverlapping) currentPreviewOverlapState.ultraWideAlpha else (if (isUw) 1f else 0f)
                            textureView.visibility = android.view.View.VISIBLE
                            val ultraWideOnTop = currentPreviewOverlapState.topSource == com.example.camera.engine.PreviewStreamSource.ULTRAWIDE
                            textureView.translationZ = if (isOverlapping) (if (ultraWideOnTop) 2f else 1f) else (if (isUw) 2f else 0f)
                            (textureView.parent as? android.view.View)?.translationZ = if (isOverlapping) (if (ultraWideOnTop) 2f else 1f) else (if (isUw) 2f else 0f)
                            if (isUw && com.example.camera.ui.components.BackdropBlurManager.isWindowActive && textureView.isAvailable) {
                                com.example.camera.ui.components.BackdropBlurManager.onViewfinderFrame(
                                    textureView = textureView,
                                    blurStrength = floatingWindowBlurStrength
                                )
                            }
                            applyTextureViewOutlineAndEffects(
                                textureView = textureView,
                                viewfinderCornerRadiusDp = viewfinderCornerRadiusDp,
                                cameraMode = cameraMode,
                                activePhotoFilter = activePhotoFilter,
                                activeLut = activeLut,
                                cinemaConfig = cinemaConfig,
                                videoAdjustments = videoAdjustments,
                                isGpuRelayPreviewActive = isGpuRelayPreviewActive,
                                proSaturation = proSaturation,
                                proContrast = proContrast,
                                proHighlights = proHighlights,
                                proShadows = proShadows
                            )
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(
                                if (currentPreviewOverlapState.isOverlapping) {
                                    if (currentPreviewOverlapState.topSource == com.example.camera.engine.PreviewStreamSource.ULTRAWIDE) 2f else 1f
                                } else if (currentIsUsingUltraWideSurface) 2f else 1f
                            )
                    )
                }

                // Clean Cinematic LUT Active Badge
                val badgeLut = activeLut ?: cinemaConfig?.selectedLut
                if (cameraMode == CameraMode.CINEMA && badgeLut != null && !badgeLut.isOff && cinemaConfig?.isLutPreviewEnabled != false) {
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
 * Open Camera architecture viewfinder transformation engine.
 *
 * Fully replaces the previous OppoCam viewfinder aspect-ratio and mode-transition implementation
 * with Open Camera's canonical configureTransform architecture:
 * - Preview buffer vs view dimension mapping
 * - RectF buffer/view mapping
 * - Matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
 * - Uniform center scaling (Math.max(scaleX, scaleY)) around (centerX, centerY)
 * - Sensor orientation handling (90°/270°) and display rotation
 * - Seamless mode switching between 4:3 (Photo/Night) and 16:9 (Video/Cinema)
 *   without temporary stretching, distortion, squashing, wrong crop, or flickering
 * - Compatible with Stable Action Horizon Lock & Dolly Zoom pipelines
 */
fun configureTransform(
    textureView: TextureView,
    previewBufferSize: CameraSize?,
    targetRatio: Float,
    displayRotation: Int = Surface.ROTATION_0,
    sensorOrientation: Int = 90,
    isHorizonLockEnabled: Boolean = false,
    horizonRollDegrees: Float = 0f,
    horizonNormX: Float = 0f,
    horizonNormY: Float = 0f,
    isDollyZoomActive: Boolean = false,
    dollyScale: Float = 1.0f,
    dollyFocusX: Float = 0.5f,
    dollyFocusY: Float = 0.5f,
    viewWidth: Int = 0,
    viewHeight: Int = 0,
    opticalScale: Float = 1.0f,
    opticalOffsetX: Float = 0.0f,
    opticalOffsetY: Float = 0.0f
) {
    val viewW = if (viewWidth > 0) viewWidth.toFloat() else textureView.width.toFloat()
    val viewH = if (viewHeight > 0) viewHeight.toFloat() else textureView.height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return

    val matrix = Matrix()
    val centerX = viewW / 2f
    val centerY = viewH / 2f

    // Native Camera2 API Viewfinder Matrix Transformation
    // Preview buffer dimensions and orientation are mapped to the TextureView
    // ensuring zero distortion, zero stretching, zero unwanted crop, and proper framing.
    if (previewBufferSize != null) {
        val bufW = previewBufferSize.width.toFloat()
        val bufH = previewBufferSize.height.toFloat()
        val isLandscapeDisplay = (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270)

        // Upright camera buffer dimensions corresponding to view orientation:
        // In portrait (ROTATION_0, ROTATION_180), Camera2 sensor buffer is landscape (e.g. 3264x2448),
        // so upright width is the short dimension (2448) and upright height is the long dimension (3264).
        val camW = if (isLandscapeDisplay) maxOf(bufW, bufH) else minOf(bufW, bufH)
        val camH = if (isLandscapeDisplay) minOf(bufW, bufH) else maxOf(bufW, bufH)

        // Determine the target aspect ratio for framing. If targetRatio > 0, use it to ensure
        // layout transitions do not apply false scaling on stale layout dimensions.
        val effectiveTargetRatio = if (targetRatio > 0f) {
            targetRatio
        } else {
            if (isLandscapeDisplay) (viewW / viewH) else (viewH / viewW)
        }
        val camRatio = if (isLandscapeDisplay) (camW / camH) else (camH / camW)

        // When the camera buffer matches the target aspect ratio within tolerance,
        // no aspect-ratio distortion/crop is required (Identity transform in steady-state portrait).
        val isMatchingTargetAspect = kotlin.math.abs(camRatio - effectiveTargetRatio) < 0.05f

        val (cropScaleX, cropScaleY) = if (isMatchingTargetAspect) {
            Pair(1.0f, 1.0f)
        } else {
            val defaultScaleX = viewW / camW
            val defaultScaleY = viewH / camH
            val fillScale = maxOf(defaultScaleX, defaultScaleY)
            Pair(fillScale / defaultScaleX, fillScale / defaultScaleY)
        }

        if (isLandscapeDisplay) {
            val bufferRect = RectF(0f, 0f, bufH, bufW)
            val viewRect = RectF(0f, 0f, viewW, viewH)
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            if (cropScaleX != 1.0f || cropScaleY != 1.0f) {
                matrix.postScale(cropScaleX, cropScaleY, centerX, centerY)
            }
            matrix.postRotate((90 * (displayRotation - 2)).toFloat(), centerX, centerY)
        } else if (displayRotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
            if (cropScaleX != 1.0f || cropScaleY != 1.0f) {
                matrix.postScale(cropScaleX, cropScaleY, centerX, centerY)
            }
        } else {
            // ROTATION_0 (standard portrait):
            // Center-crop without distortion or stretching (1:1 pixel aspect ratio).
            // When a 4:3 stream is displayed in a 9:16 frame, cropScaleX cleanly crops the sides
            // to produce an undistorted 9:16 center crop, matching the recorded video output.
            if (cropScaleX != 1.0f || cropScaleY != 1.0f) {
                matrix.postScale(cropScaleX, cropScaleY, centerX, centerY)
            }
        }
    }

    // Viewfinder-level camera feature integration: Stable Action Horizon Lock & Dolly Zoom
    if (isHorizonLockEnabled) {
        val angleDeg = -horizonRollDegrees
        matrix.postRotate(angleDeg, centerX, centerY)

        val aspect = maxOf(viewW, viewH) / minOf(viewW, viewH)
        val safeScale = maxOf(kotlin.math.sqrt(1f + aspect * aspect) / 0.90f, 1.8518f)
        matrix.postScale(safeScale, safeScale, centerX, centerY)

        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = kotlin.math.cos(rad)
        val sinA = kotlin.math.sin(rad)
        val rotNormX = horizonNormX * cosA - horizonNormY * sinA
        val rotNormY = horizonNormX * sinA + horizonNormY * cosA
        val marginX = (viewW * (safeScale - 1f) / 2f).coerceAtLeast(0f)
        val marginY = (viewH * (safeScale - 1f) / 2f).coerceAtLeast(0f)
        val shiftX = (rotNormX * marginX * 0.9).toFloat()
        val shiftY = (rotNormY * marginY * 0.9).toFloat()
        matrix.postTranslate(shiftX, shiftY)
    } else if (isDollyZoomActive && dollyScale > 1.005f) {
        val focalX = (dollyFocusX * viewW).coerceIn(viewW * 0.05f, viewW * 0.95f)
        val focalY = (dollyFocusY * viewH).coerceIn(viewH * 0.05f, viewH * 0.95f)
        matrix.postScale(dollyScale, dollyScale, focalX, focalY)
        val shiftX = (viewW / 2f - focalX) * ((dollyScale - 1f) / dollyScale).coerceIn(0f, 1f)
        val shiftY = (viewH / 2f - focalY) * ((dollyScale - 1f) / dollyScale).coerceIn(0f, 1f)
        matrix.postTranslate(shiftX, shiftY)
    }

    if (opticalScale != 1.0f) {
        matrix.postScale(opticalScale, opticalScale, centerX, centerY)
    }
    if (opticalOffsetX != 0.0f || opticalOffsetY != 0.0f) {
        matrix.postTranslate(opticalOffsetX * viewW, opticalOffsetY * viewH)
    }

    textureView.setTransform(matrix)
}

/**
 * Backward-compatible bridge to Open Camera configureTransform architecture.
 */
internal fun updateTextureViewTransform(
    textureView: TextureView,
    previewBufferSize: CameraSize?,
    targetRatio: Float,
    sensorOrientation: Int = 90,
    isHorizonLockEnabled: Boolean = false,
    horizonRollDegrees: Float = 0f,
    horizonNormX: Float = 0f,
    horizonNormY: Float = 0f,
    isDollyZoomActive: Boolean = false,
    dollyScale: Float = 1.0f,
    dollyFocusX: Float = 0.5f,
    dollyFocusY: Float = 0.5f,
    viewWidth: Int = 0,
    viewHeight: Int = 0,
    opticalScale: Float = 1.0f,
    opticalOffsetX: Float = 0.0f,
    opticalOffsetY: Float = 0.0f
) {
    val displayRotation = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            textureView.display?.rotation ?: Surface.ROTATION_0
        } else {
            val wm = textureView.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    } catch (e: Exception) {
        Surface.ROTATION_0
    }

    configureTransform(
        textureView = textureView,
        previewBufferSize = previewBufferSize,
        targetRatio = targetRatio,
        displayRotation = displayRotation,
        sensorOrientation = sensorOrientation,
        isHorizonLockEnabled = isHorizonLockEnabled,
        horizonRollDegrees = horizonRollDegrees,
        horizonNormX = horizonNormX,
        horizonNormY = horizonNormY,
        isDollyZoomActive = isDollyZoomActive,
        dollyScale = dollyScale,
        dollyFocusX = dollyFocusX,
        dollyFocusY = dollyFocusY,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        opticalScale = opticalScale,
        opticalOffsetX = opticalOffsetX,
        opticalOffsetY = opticalOffsetY
    )
}


private data class TextureViewEffectCacheKey(
    val cameraMode: CameraMode,
    val activePhotoFilter: PhotoFilter?,
    val activeLut: CinematicLut?,
    val cinemaConfig: CinemaConfig?,
    val videoAdjustments: com.example.camera.model.VideoAdjustments?,
    val isGpuRelayPreviewActive: Boolean,
    val proSaturation: Float,
    val proContrast: Float,
    val proHighlights: Float,
    val proShadows: Float
)

private val lastAppliedCornerRadius = java.util.WeakHashMap<TextureView, Int>()
private val lastAppliedEffectKey = java.util.WeakHashMap<TextureView, TextureViewEffectCacheKey>()

private fun applyTextureViewOutlineAndEffects(
    textureView: TextureView,
    viewfinderCornerRadiusDp: Int,
    cameraMode: CameraMode,
    activePhotoFilter: PhotoFilter?,
    activeLut: CinematicLut?,
    cinemaConfig: CinemaConfig?,
    videoAdjustments: com.example.camera.model.VideoAdjustments?,
    isGpuRelayPreviewActive: Boolean,
    proSaturation: Float,
    proContrast: Float,
    proHighlights: Float,
    proShadows: Float
) {
    val prevRadius = synchronized(lastAppliedCornerRadius) {
        lastAppliedCornerRadius[textureView]
    }
    if (prevRadius != viewfinderCornerRadiusDp) {
        synchronized(lastAppliedCornerRadius) {
            lastAppliedCornerRadius[textureView] = viewfinderCornerRadiusDp
        }
        if (viewfinderCornerRadiusDp > 0) {
            val radiusPx = viewfinderCornerRadiusDp * textureView.resources.displayMetrics.density
            textureView.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            textureView.clipToOutline = true
        } else {
            textureView.clipToOutline = false
            textureView.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
    }

    val currentKey = TextureViewEffectCacheKey(
        cameraMode = cameraMode,
        activePhotoFilter = activePhotoFilter,
        activeLut = activeLut,
        cinemaConfig = cinemaConfig,
        videoAdjustments = videoAdjustments,
        isGpuRelayPreviewActive = isGpuRelayPreviewActive,
        proSaturation = proSaturation,
        proContrast = proContrast,
        proHighlights = proHighlights,
        proShadows = proShadows
    )
    val prevKey = synchronized(lastAppliedEffectKey) {
        lastAppliedEffectKey[textureView]
    }
    if (prevKey == currentKey) {
        return
    }
    synchronized(lastAppliedEffectKey) {
        lastAppliedEffectKey[textureView] = currentKey
    }

    if (cameraMode == CameraMode.CINEMA && cinemaConfig != null) {
        val effectiveLut = if (cinemaConfig.isLutPreviewEnabled) {
            activeLut ?: cinemaConfig.selectedLut
        } else {
            CinematicLut.OFF
        }
        val effectiveConfig = if (effectiveLut != cinemaConfig.selectedLut) {
            cinemaConfig.copy(selectedLut = effectiveLut)
        } else {
            cinemaConfig
        }
        val shouldIncludeLut = cinemaConfig.isLutPreviewEnabled && !effectiveLut.isOff
        com.example.camera.engine.CinemaColorPipeline.applyToView(
            view = textureView,
            config = effectiveConfig,
            includeCreativeLut = shouldIncludeLut
        )
    } else if (cameraMode == CameraMode.VIDEO && videoAdjustments != null) {
        if (isGpuRelayPreviewActive) {
            com.example.camera.engine.VideoAdjustmentsPipeline.applyToView(textureView, null)
        } else {
            com.example.camera.engine.VideoAdjustmentsPipeline.applyToView(textureView, videoAdjustments)
        }
    } else {
        val hasProAdj = (proSaturation != 0f || proContrast != 1.0f || proHighlights != 0f || proShadows != 0f)

        if (hasProAdj) {
            val combinedMatrix = android.graphics.ColorMatrix()
            if (hasProAdj) {
                val sat = (1.0f + proSaturation).coerceIn(0f, 2.5f)
                if (sat != 1.0f) {
                    val satMat = android.graphics.ColorMatrix().apply { setSaturation(sat) }
                    combinedMatrix.postConcat(satMat)
                }
                val c = proContrast.coerceIn(0.4f, 2.0f)
                val lumDelta = (proShadows * 28f) + (proHighlights * 22f)
                val dynContrast = (c + (proHighlights - proShadows) * 0.18f).coerceIn(0.4f, 2.2f)
                val translate = (1f - dynContrast) * 128f + lumDelta
                val toneMat = android.graphics.ColorMatrix(floatArrayOf(
                    dynContrast, 0f, 0f, 0f, translate,
                    0f, dynContrast, 0f, 0f, translate,
                    0f, 0f, dynContrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
                combinedMatrix.postConcat(toneMat)
            }
            val filter = android.graphics.ColorMatrixColorFilter(combinedMatrix)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                textureView.setRenderEffect(android.graphics.RenderEffect.createColorFilterEffect(filter))
            } else {
                val paint = android.graphics.Paint().apply { colorFilter = filter }
                textureView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                textureView.setRenderEffect(null)
            }
            if (textureView.layerType != android.view.View.LAYER_TYPE_NONE) {
                textureView.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
            }
        }
    }
}



