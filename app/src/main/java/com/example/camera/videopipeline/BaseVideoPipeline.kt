package com.example.camera.videopipeline

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import android.view.View
import java.io.File
import java.lang.ref.WeakReference

/**
 * Common base implementation providing real-time hardware AGSL RuntimeShader
 * application for the live viewfinder and delegating post-recording transcoding.
 */
abstract class BaseVideoPipeline(
    override val type: VideoPipelineType
) : IVideoPipeline {

    protected val tag = "VideoPipeline_${type.name}"

    @Volatile
    private var runtimeShaderInstance: Any? = null
    @Volatile
    private var cachedRenderEffect: RenderEffect? = null
    private var fallbackPaint: Paint? = null
    private var currentAttachedView: WeakReference<View>? = null

    override fun applyToView(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                var shader = runtimeShaderInstance as? RuntimeShader
                if (shader == null) {
                    shader = RuntimeShader(getAgslShaderCode())
                    runtimeShaderInstance = shader
                }

                val w = view.width.toFloat().coerceAtLeast(1.0f)
                val h = view.height.toFloat().coerceAtLeast(1.0f)
                shader.setFloatUniform("uResolution", w, h)

                if (cachedRenderEffect == null || currentAttachedView?.get() != view) {
                    val effect = RenderEffect.createRuntimeShaderEffect(shader, "uContent")
                    cachedRenderEffect = effect
                    currentAttachedView = WeakReference(view)
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
        cachedRenderEffect = null
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
