package com.example.camera.videopipeline

import android.graphics.ColorMatrix
import android.view.View
import java.io.File

/**
 * Base contract for an independent video processing pipeline.
 * Completely isolates its color science, tone curves, and rendering
 * for both real-time viewfinder display and final recorded video files.
 */
interface IVideoPipeline {
    val type: VideoPipelineType
    val displayName: String get() = type.title
    val subtitle: String get() = type.subtitle
    val description: String get() = type.description

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
     * Returns the OpenGL ES fragment shader source code for video transcoding.
     */
    fun getGlFragmentShaderCode(): String

    /**
     * Computes the mathematical 4x5 ColorMatrix representation of this pipeline's
     * chromatic and tonal transformations for API < 33 fallback.
     */
    fun computeColorMatrix(): ColorMatrix

    /**
     * Hardware-accelerated post-processor that transforms the recorded video file
     * using this pipeline's dedicated shader and parameters.
     */
    fun processVideo(inputFile: File, outputFile: File, orientationDegrees: Int): File
}
