package com.example.camera.videopipeline

import android.graphics.ColorMatrix
import android.hardware.camera2.CaptureRequest
import android.view.View
import com.example.camera.model.HardwareCapabilities
import java.io.File

/**
 * Base contract for an independent video processing pipeline.
 * Completely isolates its sensor/ISP acquisition, multi-stage color science,
 * tone curves, HDR compression, spatial detail/micro-contrast sharpening, and rendering
 * for both real-time viewfinder display and final recorded video files.
 */
interface IVideoPipeline {
    val type: VideoPipelineType
    val displayName: String get() = type.title
    val subtitle: String get() = type.subtitle
    val description: String get() = type.description
    val isCustomPipeline: Boolean get() = type != VideoPipelineType.NORMAL

    /**
     * Multi-stage parameters defining this pipeline's independent processing path.
     */
    val stageParams: VideoPipelineStageParams

    /**
     * Configures the Camera2 CaptureRequest for this pipeline's dedicated ISP acquisition path,
     * completely bypassing the normal video pipeline's tone mapping, sharpening, and color profile.
     */
    fun applyToCaptureRequest(
        builder: CaptureRequest.Builder,
        capabilities: HardwareCapabilities,
        baseEvIndex: Int = 0
    )

    /**
     * Applies this pipeline directly to the viewfinder TextureView.
     * Uses hardware AGSL shader on Android 13+ (API 33+) or hardware ColorMatrix on earlier versions.
     */
    fun applyToView(view: View)

    /**
     * Clears all shader/render effects associated with this pipeline from the given view.
     */
    fun clearFromView(view: View)

    /**
     * Returns the AGSL RuntimeShader source code for real-time live preview.
     */
    fun getAgslShaderCode(): String

    /**
     * Returns the OpenGL ES fragment shader source code for real-time custom video encoding and transcoding.
     */
    fun getGlFragmentShaderCode(): String

    /**
     * Computes the mathematical 4x5 ColorMatrix representation of this pipeline's
     * chromatic and tonal transformations for API < 33 fallback.
     */
    fun computeColorMatrix(): ColorMatrix

    /**
     * Hardware-accelerated processor that transforms the recorded video file
     * using this pipeline's dedicated multi-stage shader and parameters.
     */
    fun processVideo(inputFile: File, outputFile: File, orientationDegrees: Int): File
}
