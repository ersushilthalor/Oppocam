package com.example.camera.videopipeline

import android.graphics.ColorMatrix
import android.view.View
import java.io.File

/**
 * Normal Video Pipeline.
 * Leaves the camera sensor and default processing pipeline unmodified.
 */
class NormalVideoPipeline : IVideoPipeline {
    override val type: VideoPipelineType = VideoPipelineType.NORMAL

    override fun applyToView(view: View) {
        clearFromView(view)
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
        // Passthrough: No transcoding needed for Normal pipeline
        return inputFile
    }
}
