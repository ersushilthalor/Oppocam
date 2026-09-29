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
import com.example.camera.depth.DepthModelInstallState
import com.example.camera.depth.DepthModelManager
import com.example.camera.depth.PhotonVirtualApertureEngine
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
    isMotionPhotoEnabled: Boolean = false,
    onMotionPhotoPreviewFrame: ((Bitmap) -> Unit)? = null,
    isHorizonLockEnabled: Boolean = false,
    horizonRollDegrees: Float = 0f,
    viewfinderCornerRadiusDp: Int = 0,
    isDollyZoomActive: Boolean = false,
    dollyCropState: com.example.camera.dollyzoom.DollyCropState? = null,
    onTapToLockDollySubject: ((Float, Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var currentScale by remember { mutableFloatStateOf(currentZoom) }

    val context = LocalContext.current
    val depthModelManager = remember { DepthModelManager.getInstance(context) }
    val virtualApertureEngine = remember { PhotonVirtualApertureEngine(context) }
    val modelStatuses by depthModelManager.modelStatuses.collectAsStateWithLifecycle()
    val hasVerifiedAiModel = remember(modelStatuses) {
        modelStatuses.values.any { it.state is DepthModelInstallState.Installed }
    }
    val activeAiModel = remember(modelStatuses) {
        depthModelManager.getActiveInstalledModelFile()?.first
    }

    var liveVirtualAperturePreviewBmp by remember { mutableStateOf<Bitmap?>(null) }
    var liveDepthColormapBmp by remember { mutableStateOf<Bitmap?>(null) }
    var textureViewInstance by remember { mutableStateOf<TextureView?>(null) }

    val isLivePortraitDepthActive = cameraMode == CameraMode.PORTRAIT &&
            hasVerifiedAiModel &&
            portraitConfig != null &&
            portraitConfig.virtualApertureEnabled &&
            (portraitConfig.liveAperturePreviewEnabled || portraitConfig.showDepthPreview)

    LaunchedEffect(
        isLivePortraitDepthActive,
        portraitConfig?.simulatedAperture,
        portraitConfig?.blurStrength,
        portraitConfig?.bokehStyle,
        portraitConfig?.showDepthPreview,
        portraitConfig?.focusPointX,
        portraitConfig?.focusPointY,
        activeAiModel
    ) {
        if (!isLivePortraitDepthActive || portraitConfig == null) {
            liveVirtualAperturePreviewBmp = null
            liveDepthColormapBmp = null
            return@LaunchedEffect
        }
        while (true) {
            val tv = textureViewInstance
            if (tv != null && tv.isAvailable && tv.width > 32 && tv.height > 32) {
                val sampleH = 256
                val sampleW = ((tv.width.toFloat() / tv.height.toFloat()) * sampleH).toInt().coerceIn(144, 384)
                val frame = try {
                    tv.getBitmap(sampleW, sampleH)
                } catch (_: Throwable) {
                    null
                }
                if (frame != null) {
                    val result = virtualApertureEngine.processRealtimePreviewFrame(frame, portraitConfig)
                    frame.recycle()
                    if (result != null) {
                        liveVirtualAperturePreviewBmp = result.first
                        liveDepthColormapBmp = result.second
                    } else {
                        liveVirtualAperturePreviewBmp = null
                        liveDepthColormapBmp = null
                    }
                }
            }
            delay(140L)
        }
    }

    LaunchedEffect(currentZoom) {
        if (abs(currentZoom - currentScale) > 0.05f) {
            currentScale = currentZoom
        }
    }

    LaunchedEffect(textureViewInstance, isHorizonLockEnabled, horizonRollDegrees, isDollyZoomActive, dollyCropState, cameraMode) {
        textureViewInstance?.let { tv ->
            if (tv.width > 0 && tv.height > 0) {
                val isFourThree = when (cameraMode) {
                    CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.NIGHT -> true
                    CameraMode.VIDEO, CameraMode.CINEMA -> false
                    else -> if (aspectRatio > 0f) aspectRatio < 1.5f else true
                }
                val targetRatio = if (isFourThree) 4f / 3f else 16f / 9f
                updateTextureViewTransform(
                    textureView = tv,
                    previewBufferSize = previewBufferSize,
                    targetRatio = targetRatio,
                    sensorOrientation = sensorOrientation,
                    isHorizonLockEnabled = isHorizonLockEnabled && cameraMode == CameraMode.VIDEO,
                    horizonRollDegrees = horizonRollDegrees,
                    isDollyZoomActive = isDollyZoomActive && cameraMode == CameraMode.VIDEO,
                    dollyScale = dollyCropState?.scaleFactor ?: 1.0f,
                    dollyFocusX = dollyCropState?.focusNormX ?: 0.5f,
                    dollyFocusY = dollyCropState?.focusNormY ?: 0.5f
                )
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

        // Native viewfinder uses 3:4 portrait frame for Photo, Portrait, Pro, and Night modes,
        // and 9:16 portrait frame for Video and Cinema modes.
        val isFourThree = when (cameraMode) {
            CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.NIGHT -> true
            CameraMode.VIDEO, CameraMode.CINEMA -> false
            else -> if (aspectRatio > 0f) aspectRatio < 1.5f else true
        }
        val targetRatio = if (isFourThree) 4f / 3f else 16f / 9f
        val currentTargetRatio by rememberUpdatedState(targetRatio)
        val currentPreviewBufferSize by rememberUpdatedState(previewBufferSize)
        val currentSensorOrientation by rememberUpdatedState(sensorOrientation)
        val currentIsHorizonLock by rememberUpdatedState(isHorizonLockEnabled && cameraMode == CameraMode.VIDEO)
        val currentHorizonRoll by rememberUpdatedState(horizonRollDegrees)
        val currentIsDollyZoom by rememberUpdatedState(isDollyZoomActive && cameraMode == CameraMode.VIDEO)
        val currentDollyScale by rememberUpdatedState(dollyCropState?.scaleFactor ?: 1.0f)
        val currentDollyFocusX by rememberUpdatedState(dollyCropState?.focusNormX ?: 0.5f)
        val currentDollyFocusY by rememberUpdatedState(dollyCropState?.focusNormY ?: 0.5f)

        // Viewfinder spans dimensions dictated by the mode-specific aspect ratio frame
        val (targetWidth, targetHeight) = if (containerWidth * targetRatio <= containerHeight) {
            containerWidth to (containerWidth * targetRatio)
        } else {
            (containerHeight / targetRatio) to containerHeight
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = if (isFourThree) Alignment.TopCenter else Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .then(
                        if (isFourThree) {
                            Modifier
                                .statusBarsPadding()
                                .padding(top = 52.dp)
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
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                if (isDollyZoomActive && cameraMode == CameraMode.VIDEO) {
                                    onTapToLockDollySubject?.invoke(normX, normY)
                                }
                                onTapToFocus(offset, normX, normY)
                            },
                            onLongPress = { offset ->
                                val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                if (isDollyZoomActive && cameraMode == CameraMode.VIDEO) {
                                    onTapToLockDollySubject?.invoke(normX, normY)
                                }
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
                            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                                val newW = right - left
                                val newH = bottom - top
                                if (newW > 0 && newH > 0) {
                                    updateTextureViewTransform(this, currentPreviewBufferSize, currentTargetRatio, currentSensorOrientation, currentIsHorizonLock, currentHorizonRoll, currentIsDollyZoom, currentDollyScale, currentDollyFocusX, currentDollyFocusY)
                                }
                            }
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    updateTextureViewTransform(this@apply, currentPreviewBufferSize, currentTargetRatio, currentSensorOrientation, currentIsHorizonLock, currentHorizonRoll, currentIsDollyZoom, currentDollyScale, currentDollyFocusX, currentDollyFocusY)
                                    onSurfaceTextureAvailable(st)
                                    onSurfaceTextureSizeChanged?.invoke(st, w, h)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    updateTextureViewTransform(this@apply, currentPreviewBufferSize, currentTargetRatio, currentSensorOrientation, currentIsHorizonLock, currentHorizonRoll, currentIsDollyZoom, currentDollyScale, currentDollyFocusX, currentDollyFocusY)
                                    onSurfaceTextureSizeChanged?.invoke(st, w, h)
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    onSurfaceTextureAvailable(null)
                                    try {
                                        lumaSampleBitmap?.recycle()
                                        lumaSampleBitmap = null
                                    } catch (ignored: Exception) {}
                                    try {
                                        motionSampleBitmap?.recycle()
                                        motionSampleBitmap = null
                                    } catch (ignored: Exception) {}
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                    // Real-time backdrop blur sampling for all floating windows & popups across the app
                                    if (com.example.camera.ui.components.BackdropBlurManager.isWindowActive) {
                                        com.example.camera.ui.components.BackdropBlurManager.onViewfinderFrame(this@apply, floatingWindowBlurStrength)
                                    }

                                    // Real-time Motion Photo frame buffering (30 FPS)
                                    if (isMotionPhotoEnabled && cameraMode == CameraMode.PHOTO && onMotionPhotoPreviewFrame != null) {
                                        val nowMs = android.os.SystemClock.uptimeMillis()
                                        if (nowMs - lastMotionSampleTime >= 33L) {
                                            lastMotionSampleTime = nowMs
                                            try {
                                                val aspect = currentTargetRatio.takeIf { it > 0 } ?: (4f / 3f)
                                                val sampleW = if (aspect > 1.3f) 1280 else if (aspect in 0.95f..1.05f) 720 else 960
                                                val sampleH = 720
                                                if (motionSampleBitmap == null || motionSampleBitmap?.width != sampleW || motionSampleBitmap?.height != sampleH || motionSampleBitmap?.isRecycled == true) {
                                                    motionSampleBitmap = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
                                                }
                                                motionSampleBitmap?.let { bmp ->
                                                    getBitmap(bmp)
                                                    onMotionPhotoPreviewFrame.invoke(bmp)
                                                }
                                            } catch (ignored: Exception) {}
                                        }
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
                            updateTextureViewTransform(textureView, previewBufferSize, targetRatio, sensorOrientation, isHorizonLockEnabled && cameraMode == CameraMode.VIDEO, horizonRollDegrees, isDollyZoomActive && cameraMode == CameraMode.VIDEO, dollyCropState?.scaleFactor ?: 1.0f, dollyCropState?.focusNormX ?: 0.5f, dollyCropState?.focusNormY ?: 0.5f)
                        }

                        // Outline provider for corner radius clipping on hardware accelerated TextureView
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            if (viewfinderCornerRadiusDp > 0) {
                                textureView.outlineProvider = object : android.view.ViewOutlineProvider() {
                                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                                        val radiusPx = viewfinderCornerRadiusDp * view.resources.displayMetrics.density
                                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                                    }
                                }
                                textureView.clipToOutline = true
                            } else {
                                textureView.outlineProvider = null
                                textureView.clipToOutline = false
                            }
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
                            com.example.camera.engine.VideoAdjustmentsPipeline.clearAdjustments(textureView)
                            val effectiveConfig = if (activeLut != null && activeLut != cinemaConfig?.selectedLut) {
                                cinemaConfig?.copy(selectedLut = activeLut)
                            } else {
                                cinemaConfig
                            }
                            val cinemaMatrix = com.example.camera.engine.CinemaColorPipeline.computeCinemaColorMatrix(
                                config = effectiveConfig,
                                rec2020Params = rec2020AutoToneParams,
                                includeCreativeLut = effectiveConfig?.isLutPreviewEnabled ?: true
                            )
                            if (cinemaMatrix != null) {
                                colorMatrix.postConcat(cinemaMatrix)
                                hasFilter = true
                            }
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
                            val filter = android.graphics.ColorMatrixColorFilter(colorMatrix)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                try {
                                    textureView.setRenderEffect(android.graphics.RenderEffect.createColorFilterEffect(filter))
                                    if (textureView.layerType != android.view.View.LAYER_TYPE_NONE) {
                                        textureView.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                                    }
                                } catch (e: Exception) {
                                    val paint = android.graphics.Paint().apply { colorFilter = filter }
                                    textureView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
                                }
                            } else {
                                val paint = android.graphics.Paint().apply { colorFilter = filter }
                                textureView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
                            }
                        } else {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                try {
                                    textureView.setRenderEffect(null)
                                } catch (ignored: Exception) {}
                            }
                            textureView.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                        }
                        textureView.invalidate()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            val pos = coordinates.positionInRoot()
                            val sz = coordinates.size
                            com.example.camera.ui.components.BackdropBlurManager.updateViewfinderGeometry(
                                boundsInRoot = Rect(pos.x, pos.y, pos.x + sz.width, pos.y + sz.height),
                                rootSize = coordinates.findRootCoordinates().size
                            )
                        }
                )

                // Real-Time Photon Virtual Aperture / AI Depth Map Preview Overlay (Exclusively in Portrait Mode when verified AI model is active)
                if (cameraMode == CameraMode.PORTRAIT && isLivePortraitDepthActive && portraitConfig != null) {
                    val displayBmp = if (portraitConfig.showDepthPreview && liveDepthColormapBmp != null) {
                        liveDepthColormapBmp
                    } else if (portraitConfig.liveAperturePreviewEnabled) {
                        liveVirtualAperturePreviewBmp
                    } else null

                    if (displayBmp != null && !displayBmp.isRecycled) {
                        Image(
                            bitmap = displayBmp.asImageBitmap(),
                            contentDescription = "Real-time Virtual Aperture Preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("portrait_virtual_aperture_live_preview")
                        )
                    }
                }

                // Clean Cinematic LUT Active Badge (Omitted when LOG profile is selected)
                val isLogProfile = cinemaConfig?.let {
                    it.colorProfile in listOf(
                        com.example.camera.model.CinemaColorProfile.FLAT_LOG,
                        com.example.camera.model.CinemaColorProfile.APPLE_LOG_2,
                        com.example.camera.model.CinemaColorProfile.SAMSUNG_APV_LOG
                    ) || it.isLogMode
                } ?: false
                val badgeLut = activeLut ?: cinemaConfig?.selectedLut
                if (cameraMode == CameraMode.CINEMA && badgeLut != null && badgeLut != CinematicLut.NONE && !isLogProfile) {
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

                // Dolly Zoom Subject Tracking Reticle & Framing Overlay
                if (cameraMode == CameraMode.VIDEO && isDollyZoomActive && dollyCropState != null) {
                    DollyZoomReticleOverlay(
                        cropState = dollyCropState,
                        modifier = Modifier.fillMaxSize()
                    )
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
    targetRatio: Float,
    sensorOrientation: Int = 90,
    isHorizonLockEnabled: Boolean = false,
    horizonRollDegrees: Float = 0f,
    isDollyZoomActive: Boolean = false,
    dollyScale: Float = 1.0f,
    dollyFocusX: Float = 0.5f,
    dollyFocusY: Float = 0.5f
) {
    val viewW = textureView.width.toFloat()
    val viewH = textureView.height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return

    val matrix = Matrix()
    val isSensorLandscape = (sensorOrientation == 90 || sensorOrientation == 270)
    val bufAspect = if (previewBufferSize != null && previewBufferSize.width > 0 && previewBufferSize.height > 0) {
        val bufPortraitW = if (isSensorLandscape) minOf(previewBufferSize.width, previewBufferSize.height).toFloat() else maxOf(previewBufferSize.width, previewBufferSize.height).toFloat()
        val bufPortraitH = if (isSensorLandscape) maxOf(previewBufferSize.width, previewBufferSize.height).toFloat() else minOf(previewBufferSize.width, previewBufferSize.height).toFloat()
        bufPortraitH / bufPortraitW
    } else {
        targetRatio
    }

    val viewAspect = viewH / viewW
    val centerX = viewW / 2f
    val centerY = viewH / 2f

    // When view and buffer aspect ratios differ, apply mathematically correct uniform scaling
    // to strictly preserve original aspect ratio and prevent vertical stretching or distortion.
    if (kotlin.math.abs(bufAspect - viewAspect) > 0.005f) {
        if (viewAspect > bufAspect) {
            val scaleX = viewAspect / bufAspect
            val scaleY = 1.0f
            matrix.setScale(scaleX, scaleY, centerX, centerY)
        } else {
            val scaleX = 1.0f
            val scaleY = bufAspect / viewAspect
            matrix.setScale(scaleX, scaleY, centerX, centerY)
        }
    }

    if (isHorizonLockEnabled) {
        // Stable Action Counter-Rotation & Safe Inscribed Crop
        val angleDeg = -horizonRollDegrees
        matrix.postRotate(angleDeg, centerX, centerY)

        // Safe crop scale factor so NO black borders appear at any rotation angle (0° to 360°)
        val aspect = max(viewW, viewH) / min(viewW, viewH)
        val safeScale = max(kotlin.math.sqrt(1f + aspect * aspect) / 0.90f, 1.8518f)
        matrix.postScale(safeScale, safeScale, centerX, centerY)
    } else if (isDollyZoomActive && dollyScale > 1.005f) {
        // Dolly Zoom real-time geometric scaling: zooms in/out centered on the tracked subject
        // to maintain the subject's constant apparent size.
        val focalX = (dollyFocusX * viewW).coerceIn(viewW * 0.15f, viewW * 0.85f)
        val focalY = (dollyFocusY * viewH).coerceIn(viewH * 0.15f, viewH * 0.85f)
        matrix.postScale(dollyScale, dollyScale, focalX, focalY)
    }

    textureView.setTransform(matrix)
}

@Composable
fun DollyZoomReticleOverlay(
    cropState: com.example.camera.dollyzoom.DollyCropState,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val w = maxWidth
        val h = maxHeight
        val density = androidx.compose.ui.platform.LocalDensity.current

        val leftPx = with(density) { (cropState.subjectBoundsNorm.left * w.toPx()) }
        val topPx = with(density) { (cropState.subjectBoundsNorm.top * h.toPx()) }
        val widthPx = with(density) { (cropState.subjectBoundsNorm.width() * w.toPx()) }
        val heightPx = with(density) { (cropState.subjectBoundsNorm.height() * h.toPx()) }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cyanColor = Color(0xFF00E5FF)
            val strokeW = 2.dp.toPx()
            val bracketLen = minOf(widthPx, heightPx) * 0.25f

            // Top-Left corner
            drawLine(cyanColor, Offset(leftPx, topPx), Offset(leftPx + bracketLen, topPx), strokeW)
            drawLine(cyanColor, Offset(leftPx, topPx), Offset(leftPx, topPx + bracketLen), strokeW)

            // Top-Right corner
            drawLine(cyanColor, Offset(leftPx + widthPx, topPx), Offset(leftPx + widthPx - bracketLen, topPx), strokeW)
            drawLine(cyanColor, Offset(leftPx + widthPx, topPx), Offset(leftPx + widthPx, topPx + bracketLen), strokeW)

            // Bottom-Left corner
            drawLine(cyanColor, Offset(leftPx, topPx + heightPx), Offset(leftPx + bracketLen, topPx + heightPx), strokeW)
            drawLine(cyanColor, Offset(leftPx, topPx + heightPx), Offset(leftPx, topPx + heightPx - bracketLen), strokeW)

            // Bottom-Right corner
            drawLine(cyanColor, Offset(leftPx + widthPx, topPx + heightPx), Offset(leftPx + widthPx - bracketLen, topPx + heightPx), strokeW)
            drawLine(cyanColor, Offset(leftPx + widthPx, topPx + heightPx), Offset(leftPx + widthPx, topPx + heightPx - bracketLen), strokeW)

            // Small center crosshair
            val cx = leftPx + widthPx / 2f
            val cy = topPx + heightPx / 2f
            drawLine(cyanColor.copy(alpha = 0.7f), Offset(cx - 8f, cy), Offset(cx + 8f, cy), 1.5.dp.toPx())
            drawLine(cyanColor.copy(alpha = 0.7f), Offset(cx, cy - 8f), Offset(cx, cy + 8f), 1.5.dp.toPx())
        }
    }
}


