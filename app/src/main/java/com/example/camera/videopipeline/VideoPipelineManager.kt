package com.example.camera.videopipeline

import android.view.View
import java.io.File

/**
 * Central Coordinator for Video Processing Pipelines.
 * Manages pipeline instances, live preview shader switching, and recorded video processing.
 */
object VideoPipelineManager {

    private val normalPipeline = NormalVideoPipeline()
    private val iPhonePipeline = IPhoneVideoPipeline()
    private val samsungPipeline = SamsungVideoPipeline()
    private val vivoPipeline = VivoVideoPipeline()

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
     * Processes a newly recorded video using the specified [type].
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
