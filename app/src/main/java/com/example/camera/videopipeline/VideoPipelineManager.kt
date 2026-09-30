package com.example.camera.videopipeline

import android.hardware.camera2.CaptureRequest
import android.view.View
import com.example.camera.model.HardwareCapabilities
import java.io.File
import java.util.WeakHashMap

/**
 * Central Coordinator for Independent Video Processing Pipelines.
 * Manages pipeline instances, sensor/ISP CaptureRequest isolation, live preview shader switching,
 * real-time custom pipeline OpenGL recording, and recorded video processing.
 */
object VideoPipelineManager {

    private val normalPipeline = NormalVideoPipeline()
    private val iPhonePipeline = IPhoneVideoPipeline()
    private val samsungPipeline = SamsungVideoPipeline()
    private val vivoPipeline = VivoVideoPipeline()

    private val activeViewPipelines = WeakHashMap<View, VideoPipelineType>()

    @Synchronized
    fun getActiveViewPipeline(view: View): VideoPipelineType? {
        return activeViewPipelines[view]
    }

    @Synchronized
    fun markActiveViewPipeline(view: View, type: VideoPipelineType?) {
        if (type == null) {
            activeViewPipelines.remove(view)
        } else {
            activeViewPipelines[view] = type
        }
    }

    /**
     * Resolves the pipeline implementation for the given [type].
     */
    fun getPipeline(type: VideoPipelineType): IVideoPipeline {
        return when (type) {
            VideoPipelineType.NORMAL -> normalPipeline
            VideoPipelineType.IPHONE -> iPhonePipeline
            VideoPipelineType.SAMSUNG -> samsungPipeline
            VideoPipelineType.VIVO -> vivoPipeline
        }
    }

    /**
     * Returns true when [type] is a dedicated custom video pipeline that completely bypasses
     * the normal video pipeline.
     */
    fun isCustomPipeline(type: VideoPipelineType): Boolean {
        return getPipeline(type).isCustomPipeline
    }

    /**
     * Applies the selected pipeline's Stage 0 hardware ISP configuration to the Camera2 [builder].
     * When a custom pipeline is active, this overrides default normal video tone mapping, edge
     * enhancement, noise reduction, and exposure bias with the custom pipeline's own curve.
     */
    fun applyPipelineToCaptureRequest(
        builder: CaptureRequest.Builder,
        type: VideoPipelineType,
        capabilities: HardwareCapabilities
    ) {
        getPipeline(type).applyToCaptureRequest(builder, capabilities)
    }

    /**
     * Applies the designated video pipeline to the viewfinder [view].
     * If [type] is [VideoPipelineType.NORMAL], any active dedicated pipeline effect is cleared.
     */
    fun applyPipelineToView(view: View, type: VideoPipelineType) {
        if (type == VideoPipelineType.NORMAL) {
            clearPipelineFromView(view)
        } else {
            getPipeline(type).applyToView(view)
        }
    }

    /**
     * Clears all pipeline shader effects from the viewfinder [view].
     */
    fun clearPipelineFromView(view: View) {
        iPhonePipeline.clearFromView(view)
        samsungPipeline.clearFromView(view)
        vivoPipeline.clearFromView(view)
        normalPipeline.clearFromView(view)
    }

    /**
     * Processes a recorded video using the specified [type].
     * For [VideoPipelineType.NORMAL], bypasses transcoding and returns [inputFile] directly.
     */
    fun processRecordedVideo(
        inputFile: File,
        outputFile: File,
        type: VideoPipelineType,
        orientationDegrees: Int
    ): File {
        val pipeline = getPipeline(type)
        return pipeline.processVideo(
            inputFile = inputFile,
            outputFile = outputFile,
            orientationDegrees = orientationDegrees
        )
    }
}

