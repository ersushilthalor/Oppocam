package com.example.camera.videopipeline

import android.graphics.ColorMatrix
import android.hardware.camera2.CaptureRequest
import android.view.View
import com.example.camera.model.HardwareCapabilities
import java.io.File

/**
 * Normal Video Pipeline.
 * Uses the default standard camera sensor and normal video processing path.
 */
class NormalVideoPipeline : IVideoPipeline {
    override val type: VideoPipelineType = VideoPipelineType.NORMAL

    override val stageParams: VideoPipelineStageParams = VideoPipelineStageParams(
        sensorExposureBiasEv = 0.0f,
        ispTonemapGamma = 1.0f,
        ispHighlightRollOff = 0.0f,
        bypassHalEdgeSharpening = false,
        highQualityTemporalDenoise = false,
        spatialSharpnessStrength = 0.0f,
        localMicroContrastStrength = 0.0f
    )

    override fun applyToCaptureRequest(
        builder: CaptureRequest.Builder,
        capabilities: HardwareCapabilities,
        baseEvIndex: Int
    ) {
        // Restore standard hardware tone mapping when switching back to Normal Video
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
    }

    override fun applyToView(view: View) {
        clearFromView(view)
        VideoPipelineManager.markActiveViewPipeline(view, VideoPipelineType.NORMAL)
    }

    override fun clearFromView(view: View) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try { view.setRenderEffect(null) } catch (ignored: Throwable) {}
        }
        if (view.layerType != View.LAYER_TYPE_NONE) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    override fun getAgslShaderCode(): String = ""

    override fun getGlFragmentShaderCode(): String = ""

    override fun computeColorMatrix(): ColorMatrix = ColorMatrix()

    override fun processVideo(inputFile: File, outputFile: File, orientationDegrees: Int): File {
        // Passthrough: Normal pipeline uses the standard recording path
        return inputFile
    }
}
