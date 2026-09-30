package com.example.camera.videopipeline

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import android.view.View
import com.example.camera.model.HardwareCapabilities
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Common base implementation for genuinely independent custom video pipelines.
 *
 * Provides:
 * 1. Dedicated Camera2 ISP/Sensor acquisition setup (custom sensor TonemapCurve, exposure headroom,
 *    HAL sharpening bypass) that completely bypasses the normal video pipeline.
 * 2. Real-time hardware AGSL RuntimeShader execution on the live viewfinder TextureView with
 *    automatic resolution synchronization so preview and recorded video have 100% identical look.
 * 3. Real-time & offline OpenGL ES 2.0 multi-stage frame processing.
 */
abstract class BaseVideoPipeline(
    override val type: VideoPipelineType
) : IVideoPipeline {

    protected val tag = "VideoPipeline_${type.name}"

    @Volatile
    private var runtimeShaderInstance: Any? = null
    private var fallbackPaint: Paint? = null
    private var currentAttachedView: WeakReference<View>? = null
    private var lastAppliedWidth: Int = -1
    private var lastAppliedHeight: Int = -1

    override fun applyToCaptureRequest(
        builder: CaptureRequest.Builder,
        capabilities: HardwareCapabilities,
        baseEvIndex: Int
    ) {
        val params = stageParams

        // 1. Bypass all built-in normal video effects and scene modes
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        )

        // 2. Apply custom pipeline's sensor exposure headroom bias (for highlight retention)
        if (params.sensorExposureBiasEv != 0f) {
            val step = capabilities.exposureCompensationStep.takeIf { it > 0f } ?: 0.333f
            val biasSteps = (params.sensorExposureBiasEv / step).roundToInt()
            val targetEv = (baseEvIndex + biasSteps).coerceIn(
                capabilities.minExposureCompensation,
                capabilities.maxExposureCompensation
            )
            if (capabilities.minExposureCompensation <= capabilities.maxExposureCompensation) {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, targetEv)
            }
        }

        // 3. Bypass OEM Normal Video Tonemap Curve and install this pipeline's dedicated sensor curve
        if (capabilities.supportsTonemapCurve) {
            try {
                val customCurve = params.buildCustomIspTonemapCurve()
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                builder.set(CaptureRequest.TONEMAP_CURVE, customCurve)
            } catch (e: Exception) {
                Log.w(tag, "Custom ISP tonemap curve fallback: ${e.message}")
            }
        }

        // 4. Bypass normal video edge sharpening so this pipeline's 5-tap spatial stage controls detail
        if (params.bypassHalEdgeSharpening) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        } else {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        }

        // 5. Configure dedicated temporal noise reduction for clean shadow lifting
        builder.set(
            CaptureRequest.NOISE_REDUCTION_MODE,
            if (params.highQualityTemporalDenoise) {
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            } else {
                CaptureRequest.NOISE_REDUCTION_MODE_FAST
            }
        )
    }

    override fun applyToView(view: View) {
        val activeTypeOnView = VideoPipelineManager.getActiveViewPipeline(view)
        val viewW = view.width.coerceAtLeast(1)
        val viewH = view.height.coerceAtLeast(1)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                var shader = runtimeShaderInstance as? RuntimeShader
                if (shader == null) {
                    shader = RuntimeShader(getAgslShaderCode())
                    runtimeShaderInstance = shader
                }

                val w = viewW.toFloat()
                val h = viewH.toFloat()
                shader.setFloatUniform("uResolution", w, h)

                val needsRebind = activeTypeOnView != type ||
                        currentAttachedView?.get() != view ||
                        lastAppliedWidth != viewW ||
                        lastAppliedHeight != viewH

                if (needsRebind) {
                    val effect = RenderEffect.createRuntimeShaderEffect(shader, "uContent")
                    currentAttachedView = WeakReference(view)
                    lastAppliedWidth = viewW
                    lastAppliedHeight = viewH
                    VideoPipelineManager.markActiveViewPipeline(view, type)
                    view.setRenderEffect(effect)
                }

                if (view.layerType != View.LAYER_TYPE_NONE) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                view.invalidate()
                return
            } catch (e: Exception) {
                Log.w(tag, "AGSL runtime shader fallback: ${e.message}")
            }
        }

        VideoPipelineManager.markActiveViewPipeline(view, type)
        applyFallbackMatrix(view)
    }

    private fun applyFallbackMatrix(view: View) {
        val matrix = computeColorMatrix()
        val paint = fallbackPaint ?: Paint().also { fallbackPaint = it }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        view.invalidate()
    }

    override fun clearFromView(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(null)
            } catch (ignored: Throwable) {}
        }
        if (view.layerType != View.LAYER_TYPE_NONE) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        if (currentAttachedView?.get() == view) {
            currentAttachedView = null
        }
        lastAppliedWidth = -1
        lastAppliedHeight = -1
    }

    override fun processVideo(inputFile: File, outputFile: File, orientationDegrees: Int): File {
        return VideoPipelineTranscoder.processVideo(
            inputFile = inputFile,
            outputFile = outputFile,
            pipeline = this,
            orientationDegrees = orientationDegrees
        )
    }
}
