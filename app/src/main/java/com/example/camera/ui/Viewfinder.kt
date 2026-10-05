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
import com.cinedepth.pro.ui.BlurPreviewParams
import com.cinedepth.pro.ui.LensEffect
import com.cinedepth.pro.ui.blur.DepthBlurEngine
import com.example.camera.model.BokehStyle
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
    selectedVideoPipeline: com.example.camera.videopipeline.VideoPipelineType = com.example.camera.videopipeline.VideoPipelineType.NORMAL,
    customVideoPipelineConfig: com.example.camera.videopipeline.CustomVideoPipelineConfig? = null,
    rec2020AutoToneParams: com.example.camera.engine.Rec2020AutoToneParams? = null,
    proSaturation: Float = 0f,
    proContrast: Float = 1.0f,
    proHighlights: Float = 0f,
    proShadows: Float = 0f,
    isProModeActive: Boolean = false,
    floatingWindowBlurStrength: Float = 24.0f,
    isSettingsOpen: Boolean = false,
    onSurfaceTextureAvailable: (SurfaceTexture?) -> Unit,
    onSurfaceTextureSizeChanged: ((SurfaceTexture, Int, Int) -> Unit)? = null,
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
    isMotionPhotoEnabled: Boolean = false,
    onMotionPhotoPreviewFrame: ((Bitmap) -> Unit)? = null,
    isHorizonLockEnabled: Boolean = false,
    horizonRollDegrees: Float = 0f,
    horizonNormX: Float = 0f,
    horizonNormY: Float = 0f,
    viewfinderCornerRadiusDp: Int = 0,
    isDollyZoomActive: Boolean = false,
    dollyCropState: com.example.camera.dollyzoom.DollyCropState? = null,
    onTapToLockDollySubject: ((Float, Float) -> Unit)? = null,
    onOpenCustomPipelineSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var currentScale by remember { mutableFloatStateOf(currentZoom) }

    val context = LocalContext.current
    val depthEstimator = remember { DepthBlurEngine.getEstimator(context) }

    var liveVirtualAperturePreviewBmp by remember { mutableStateOf<Bitmap?>(null) }
    var liveDepthColormapBmp by remember { mutableStateOf<Bitmap?>(null) }
    var textureViewInstance by remember { mutableStateOf<TextureView?>(null) }

    val isLivePortraitDepthActive = cameraMode == CameraMode.PORTRAIT &&
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
        portraitConfig?.focusPointY
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
                    try {
                        val lensEffect = when (portraitConfig.bokehStyle) {
                            BokehStyle.NATURAL_ROUND -> LensEffect.Classic
                            BokehStyle.SOFT_ELLIPTICAL -> LensEffect.Anamorphic
                            BokehStyle.POLYGONAL_APERTURE -> LensEffect.Hexagon
                            BokehStyle.LIGHT_SOURCE -> LensEffect.Bloom
                            BokehStyle.ZEISS_SWIRL -> LensEffect.Bubble
                            BokehStyle.LEICA_3D_POP -> LensEffect.Creamy
                        }
                        val blurScale = (portraitConfig.blurStrength / 100f).coerceIn(0.05f, 1.0f)
                        val previewParams = BlurPreviewParams(
                            blurStrength = blurScale,
                            focusDepth = 64f,
                            lensEffect = lensEffect,
                            edgeSoftness = 0.35f,
                            edgeExpand = 0.22f,
                            edgeRefine = 0.50f,
                            blurFalloff = 0.70f
                        )
                        val result = DepthBlurEngine.renderDepthAware(
                            source = frame,
                            params = previewParams,
                            depthEstimator = depthEstimator,
                            overrideDepth = null,
                            fastContourMatte = true
                        )
                        liveVirtualAperturePreviewBmp = result.bitmap
                        liveDepthColormapBmp = result.depthMapBitmap
                    } catch (_: Throwable) {
                        liveVirtualAperturePreviewBmp = null
                        liveDepthColormapBmp = null
                    } finally {
                        frame.recycle()
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

    val isFourThreeMode = when (cameraMode) {
        CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.NIGHT -> true
        CameraMode.VIDEO, CameraMode.CINEMA -> false
        else -> if (aspectRatio > 0f) aspectRatio < 1.5f else true
    }
    val targetRatioCalc = if (isFourThreeMode) 4f / 3f else 16f / 9f

    var framesSyncedSinceTransition by remember { mutableIntStateOf(0) }

    LaunchedEffect(cameraMode, isSettingsOpen, previewBufferSize, targetRatioCalc) {
        framesSyncedSinceTransition = 0
    }

    LaunchedEffect(
        textureViewInstance,
        isHorizonLockEnabled,
        horizonRollDegrees,
        horizonNormX,
        horizonNormY,
        isDollyZoomActive,
        dollyCropState,
        cameraMode,
        previewBufferSize,
        isSettingsOpen,
        aspectRatio
    ) {
        textureViewInstance?.let { tv ->
            if (tv.width > 0 && tv.height > 0) {
                updateTextureViewTransform(
                    textureView = tv,
                    previewBufferSize = previewBufferSize,
                    targetRatio = targetRatioCalc,
                    sensorOrientation = sensorOrientation,
                    isHorizonLockEnabled = isHorizonLockEnabled && (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.CINEMA),
                    horizonRollDegrees = horizonRollDegrees,
                    horizonNormX = horizonNormX,
                    horizonNormY = horizonNormY,
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
        val currentIsHorizonLock by rememberUpdatedState(isHorizonLockEnabled && (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.CINEMA))
        val currentHorizonRoll by rememberUpdatedState(horizonRollDegrees)
        val currentHorizonNormX by rememberUpdatedState(horizonNormX)
        val currentHorizonNormY by rememberUpdatedState(horizonNormY)
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
                            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                                val newW = right - left
                                val newH = bottom - top
                                if (newW > 0 && newH > 0 && (newW != (oldRight - oldLeft) || newH != (oldBottom - oldTop) || framesSyncedSinceTransition < 5)) {
                                    updateTextureViewTransform(
                                        textureView = this,
                                        previewBufferSize = currentPreviewBufferSize,
                                        targetRatio = currentTargetRatio,
                                        sensorOrientation = currentSensorOrientation,
                                        isHorizonLockEnabled = currentIsHorizonLock,
                                        horizonRollDegrees = currentHorizonRoll,
                                        horizonNormX = currentHorizonNormX,
                                        horizonNormY = currentHorizonNormY,
                                        isDollyZoomActive = currentIsDollyZoom,
                                        dollyScale = currentDollyScale,
                                        dollyFocusX = currentDollyFocusX,
                                        dollyFocusY = currentDollyFocusY,
                                        viewWidth = newW,
                                        viewHeight = newH
                                    )
                                }
                            }
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    val effectiveW = if (this@apply.width > 0) this@apply.width else w
                                    val effectiveH = if (this@apply.height > 0) this@apply.height else h
                                    updateTextureViewTransform(
                                        textureView = this@apply,
                                        previewBufferSize = currentPreviewBufferSize,
                                        targetRatio = currentTargetRatio,
                                        sensorOrientation = currentSensorOrientation,
                                        isHorizonLockEnabled = currentIsHorizonLock,
                                        horizonRollDegrees = currentHorizonRoll,
                                        horizonNormX = currentHorizonNormX,
                                        horizonNormY = currentHorizonNormY,
                                        isDollyZoomActive = currentIsDollyZoom,
                                        dollyScale = currentDollyScale,
                                        dollyFocusX = currentDollyFocusX,
                                        dollyFocusY = currentDollyFocusY,
                                        viewWidth = effectiveW,
                                        viewHeight = effectiveH
                                    )
                                    onSurfaceTextureAvailable(st)
                                    onSurfaceTextureSizeChanged?.invoke(st, w, h)
                                    com.example.camera.ui.components.BackdropBlurManager.registerViewfinder(this@apply)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    val effectiveW = if (this@apply.width > 0) this@apply.width else w
                                    val effectiveH = if (this@apply.height > 0) this@apply.height else h
                                    updateTextureViewTransform(
                                        textureView = this@apply,
                                        previewBufferSize = currentPreviewBufferSize,
                                        targetRatio = currentTargetRatio,
                                        sensorOrientation = currentSensorOrientation,
                                        isHorizonLockEnabled = currentIsHorizonLock,
                                        horizonRollDegrees = currentHorizonRoll,
                                        horizonNormX = currentHorizonNormX,
                                        horizonNormY = currentHorizonNormY,
                                        isDollyZoomActive = currentIsDollyZoom,
                                        dollyScale = currentDollyScale,
                                        dollyFocusX = currentDollyFocusX,
                                        dollyFocusY = currentDollyFocusY,
                                        viewWidth = effectiveW,
                                        viewHeight = effectiveH
                                    )
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
                                    // Synchronize transformation matrix continuously on initial frames
                                    // following camera reinitialization, mode transitions, and returning from Settings
                                    if (framesSyncedSinceTransition < 5) {
                                        framesSyncedSinceTransition++
                                        updateTextureViewTransform(
                                            textureView = this@apply,
                                            previewBufferSize = currentPreviewBufferSize,
                                            targetRatio = currentTargetRatio,
                                            sensorOrientation = currentSensorOrientation,
                                            isHorizonLockEnabled = currentIsHorizonLock,
                                            horizonRollDegrees = currentHorizonRoll,
                                            horizonNormX = currentHorizonNormX,
                                            horizonNormY = currentHorizonNormY,
                                            isDollyZoomActive = currentIsDollyZoom,
                                            dollyScale = currentDollyScale,
                                            dollyFocusX = currentDollyFocusX,
                                            dollyFocusY = currentDollyFocusY
                                        )
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

                                    // Real-time floating window backdrop blur sampling
                                    if (com.example.camera.ui.components.BackdropBlurManager.isWindowActive) {
                                        com.example.camera.ui.components.BackdropBlurManager.onViewfinderFrame(
                                            textureView = this@apply,
                                            blurStrength = floatingWindowBlurStrength
                                        )
                                    }
                                }
                            }
                        }
                    },
                    update = { textureView ->
                        // Sample background blur frame when windows are active
                        if (com.example.camera.ui.components.BackdropBlurManager.isWindowActive && textureView.isAvailable) {
                            com.example.camera.ui.components.BackdropBlurManager.onViewfinderFrame(
                                textureView = textureView,
                                blurStrength = floatingWindowBlurStrength
                            )
                        }
                        // Dynamically synchronize TextureView transformation with current dimensions and buffer size
                        val viewW = textureView.width.toFloat()
                        val viewH = textureView.height.toFloat()
                        if (viewW > 0f && viewH > 0f) {
                            updateTextureViewTransform(textureView, previewBufferSize, targetRatio, sensorOrientation, isHorizonLockEnabled && (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.CINEMA), horizonRollDegrees, horizonNormX, horizonNormY, isDollyZoomActive && cameraMode == CameraMode.VIDEO, dollyCropState?.scaleFactor ?: 1.0f, dollyCropState?.focusNormX ?: 0.5f, dollyCropState?.focusNormY ?: 0.5f)
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
                            com.example.camera.videopipeline.VideoPipelineManager.clearPipelineFromView(textureView)
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
                            com.example.camera.videopipeline.VideoPipelineManager.clearPipelineFromView(textureView)
                            com.example.camera.engine.VideoAdjustmentsPipeline.clearAdjustments(textureView)
                            val effectiveConfig = if (activeLut != null && activeLut != cinemaConfig?.selectedLut) {
                                cinemaConfig?.copy(selectedLut = activeLut)
                            } else {
                                cinemaConfig
                            }
                            com.example.camera.engine.CinemaColorPipeline.applyToView(
                                view = textureView,
                                config = effectiveConfig,
                                rec2020Params = rec2020AutoToneParams,
                                includeCreativeLut = effectiveConfig?.isLutPreviewEnabled ?: true
                            )
                            textureView.invalidate()
                            return@AndroidView
                        } else if (cameraMode == CameraMode.PORTRAIT && portraitConfig != null) {
                            com.example.camera.videopipeline.VideoPipelineManager.clearPipelineFromView(textureView)
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
                            if (selectedVideoPipeline == com.example.camera.videopipeline.VideoPipelineType.CUSTOM) {
                                com.example.camera.engine.VideoAdjustmentsPipeline.clearAdjustments(textureView)
                                com.example.camera.videopipeline.VideoPipelineManager.applyPipelineToView(textureView, selectedVideoPipeline)
                                return@AndroidView
                            } else {
                                com.example.camera.videopipeline.VideoPipelineManager.clearPipelineFromView(textureView)
                                com.example.camera.engine.VideoAdjustmentsPipeline.applyToView(textureView, videoAdjustments)
                                return@AndroidView
                            }
                        } else {
                            com.example.camera.videopipeline.VideoPipelineManager.clearPipelineFromView(textureView)
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
                if (cameraMode == CameraMode.CINEMA && badgeLut != null && !badgeLut.isOff && !isLogProfile) {
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
                } else if (cameraMode == CameraMode.VIDEO && selectedVideoPipeline == com.example.camera.videopipeline.VideoPipelineType.CUSTOM) {
                    val pipelineAccent = Color(0xFFFFD54F)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xDD0D0F18))
                            .border(1.dp, pipelineAccent.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .clickable(enabled = onOpenCustomPipelineSettings != null) {
                                onOpenCustomPipelineSettings?.invoke()
                            }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                            .testTag("active_custom_pipeline_badge")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(pipelineAccent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CUSTOM PIPELINE • REC.2020 NATURAL LOG",
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
 * Open Camera architecture viewfinder transformation engine.
 *
 * Fully replaces the previous OppoCam viewfinder aspect-ratio and mode-transition implementation
 * with Open Camera's canonical configureTransform architecture:
 * - Preview buffer vs view dimension mapping
 * - RectF buffer/view mapping
 * - Matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
 * - Uniform center scaling (Math.max(scaleX, scaleY)) around (centerX, centerY)
 * - Sensor orientation handling (90°/270°) and display rotation
 * - Seamless mode switching between 4:3 (Photo/Portrait/Night) and 16:9 (Video/Cinema)
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
    viewHeight: Int = 0
) {
    val viewW = if (viewWidth > 0) viewWidth.toFloat() else textureView.width.toFloat()
    val viewH = if (viewHeight > 0) viewHeight.toFloat() else textureView.height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return

    val matrix = Matrix()
    val centerX = viewW / 2f
    val centerY = viewH / 2f

    // 1. Calculate buffer dimensions in display orientation (Open Camera approach)
    val isLandscapeDisplay = (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270)
    val (bufW, bufH) = if (previewBufferSize != null && previewBufferSize.width > 0 && previewBufferSize.height > 0) {
        val maxDim = maxOf(previewBufferSize.width, previewBufferSize.height).toFloat()
        val minDim = minOf(previewBufferSize.width, previewBufferSize.height).toFloat()
        if (isLandscapeDisplay) {
            maxDim to minDim
        } else {
            minDim to maxDim
        }
    } else {
        if (isLandscapeDisplay) {
            (viewH * targetRatio) to viewH
        } else {
            viewW to (viewW * targetRatio)
        }
    }

    val bufAspect = bufH / bufW
    val actualViewAspect = viewH / viewW

    // 2. Open Camera mode-transition & stale layout synchronization:
    // When switching between modes (e.g. Photo 4:3 <-> Video 16:9), if the buffer already matches
    // the target aspect ratio, ensure we synchronize with targetRatio rather than applying false
    // distortion from layout dimensions that are transitioning in the background.
    val isBufMatchingTarget = kotlin.math.abs(bufAspect - targetRatio) < 0.08f
    val isViewMatchingTarget = kotlin.math.abs(actualViewAspect - targetRatio) < 0.08f

    val effectiveViewW: Float
    val effectiveViewH: Float
    if (isBufMatchingTarget && !isViewMatchingTarget) {
        if (viewH >= viewW) {
            effectiveViewW = viewW
            effectiveViewH = viewW * targetRatio
        } else {
            effectiveViewW = viewH * targetRatio
            effectiveViewH = viewH
        }
    } else {
        effectiveViewW = viewW
        effectiveViewH = viewH
    }

    val effectiveViewAspect = effectiveViewH / effectiveViewW

    // 3. Open Camera RectF buffer/view mapping and Matrix.setRectToRect()
    // When buffer and view aspect ratios differ, perform Open Camera's uniform center scaling:
    if (kotlin.math.abs(bufAspect - effectiveViewAspect) > 0.005f) {
        val viewRect = RectF(0f, 0f, viewW, viewH)
        val (mappedBufW, mappedBufH) = if (effectiveViewAspect > bufAspect) {
            viewW to (viewW * bufAspect)
        } else {
            (viewH / bufAspect) to viewH
        }
        val bufferRect = RectF(0f, 0f, mappedBufW, mappedBufH)
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

        // Map viewRect to bufferRect using Matrix.ScaleToFit.FILL
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

        // Open Camera uniform center scaling to fill view without non-uniform stretching
        val scale = maxOf(viewW / mappedBufW, viewH / mappedBufH)
        matrix.postScale(scale, scale, centerX, centerY)
    }

    // 4. Open Camera rotation handling
    if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
        val degrees = if (displayRotation == Surface.ROTATION_90) -90f else 90f
        matrix.postRotate(degrees, centerX, centerY)
    } else if (displayRotation == Surface.ROTATION_180) {
        matrix.postRotate(180f, centerX, centerY)
    }

    // 5. Existing camera feature integration: Stable Action Horizon Lock & Dolly Zoom
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
    viewHeight: Int = 0
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
        viewHeight = viewHeight
    )
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

        val bounds = cropState.postFilterBoxNorm
        val leftPx = with(density) { (bounds.left * w.toPx()) }
        val topPx = with(density) { (bounds.top * h.toPx()) }
        val widthPx = with(density) { (bounds.width() * w.toPx()) }
        val heightPx = with(density) { (bounds.height() * h.toPx()) }

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


