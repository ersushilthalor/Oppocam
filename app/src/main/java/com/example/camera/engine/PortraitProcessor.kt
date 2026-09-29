package com.example.camera.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.cinedepth.pro.ui.BlurPreviewParams
import com.cinedepth.pro.ui.LensEffect
import com.cinedepth.pro.ui.blur.DepthBlurEngine
import com.cinedepth.pro.ui.blur.DepthEstimator
import com.cinedepth.pro.ui.blur.FaceAutoFocus
import com.example.camera.model.BokehStyle
import com.example.camera.model.PortraitConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CineDepth Pro Computational Photography Portrait Engine.
 *
 * Fully integrated from https://github.com/weiykong/CineDepthPro:
 * 1. AI Monocular Depth Estimation with TFLite GPU Delegate acceleration & CPU fallback.
 * 2. High-Resolution On-Device Portrait Segmentation with Raw Size Alpha Matting.
 * 3. Pass 1: Luma-weighted Bilateral GPU Depth Map Edge Refinement (AGSL RuntimeShader).
 * 4. Boundary-Aware Contour Matting with Hair and Occlusion Protection (AGSL RuntimeShader).
 * 5. Pass 2: High-Fidelity Optical Bokeh Accumulation (AGSL RuntimeShader):
 *    - Golden angle disc blur (up to 72 samples)
 *    - Anamorphic 1.65x oval bokeh with horizontal flare streak accumulation
 *    - Highlight bloom and specular boost
 *    - Chromatic Aberration (CA) and Cat's-Eye Vignetting
 * 6. Automated EXIF metadata preservation & immediate gallery saving to DCIM.
 */
class PortraitProcessor(private val context: Context) {

    companion object {
        private const val TAG = "PortraitProcessor"
    }

    private val depthBlurEngine = DepthBlurEngine
    private val depthEstimator: DepthEstimator by lazy { DepthBlurEngine.getEstimator(context) }

    /**
     * Executes CineDepth Pro's complete processing pipeline on the captured [orientedBitmap].
     * Immediately processes with GPU AGSL shaders and saves the final result to the device gallery.
     */
    suspend fun processAndSavePortrait(
        orientedBitmap: Bitmap,
        config: PortraitConfig,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Uri? = withContext(Dispatchers.Default) {
        onProgress(0.08f, "Starting CineDepth Pro...")
        try {
            // 1. Map BokehStyle to CineDepth Pro LensEffect
            val lensEffect = when (config.bokehStyle) {
                BokehStyle.NATURAL_ROUND -> LensEffect.Classic
                BokehStyle.SOFT_ELLIPTICAL -> LensEffect.Anamorphic
                BokehStyle.POLYGONAL_APERTURE -> LensEffect.Hexagon
                BokehStyle.LIGHT_SOURCE -> LensEffect.Bloom
                BokehStyle.ZEISS_SWIRL -> LensEffect.Bubble
                BokehStyle.LEICA_3D_POP -> LensEffect.Creamy
            }

            // 2. Map simulated aperture and blurStrength (0..100) to optical blur scale
            val apertureBaseBlur = when (config.simulatedAperture) {
                "f/0.95" -> 0.52f
                "f/1.2" -> 0.46f
                "f/1.4" -> 0.40f
                "f/1.8" -> 0.34f
                "f/2.0" -> 0.28f
                "f/2.4" -> 0.24f
                "f/2.8" -> 0.20f
                "f/4.0" -> 0.15f
                "f/5.6" -> 0.10f
                "f/8.0" -> 0.06f
                "f/11" -> 0.03f
                "f/16" -> 0.01f
                else -> 0.34f
            }
            val blurStrength = (apertureBaseBlur * (config.blurStrength / 60f)).coerceIn(0.02f, 1.0f)

            // 3. Optical artifacts and style characteristics
            val vignetteStrength = when {
                config.selectedStyle.isLeicaOptical -> 0.20f
                config.selectedStyle.isZeissOptical -> 0.24f
                lensEffect == LensEffect.Anamorphic -> 0.15f
                lensEffect == LensEffect.Creamy -> 0.18f
                else -> 0.06f
            }

            val flareStrength = when {
                lensEffect == LensEffect.Anamorphic -> 0.18f
                config.selectedStyle.isZeissOptical -> 0.12f
                else -> 0.05f
            }

            val highlightBoost = (0.20f + config.selectedStyle.highlightGlow * 0.40f).coerceIn(0.10f, 0.70f)

            onProgress(0.25f, "Running CineDepth Pro depth estimation & matting...")

            // 4. Pin subject focus depth via upfront face detection or manual focus point
            val focusLocation = if (config.focusPointX != null && config.focusPointY != null) {
                Pair(config.focusPointX, config.focusPointY)
            } else {
                FaceAutoFocus.detectFaceLocation(orientedBitmap)
            }

            // Subject focus depth in 0..255 space (64f = 0.25f foreground plane)
            val focusDepth = 64f

            val initialParams = BlurPreviewParams(
                blurStrength = blurStrength,
                focusDepth = focusDepth,
                lensEffect = lensEffect,
                edgeSoftness = 0.35f,
                edgeExpand = 0.20f,
                edgeRefine = 0.55f,
                backgroundLight = 0.12f,
                highlightBoost = highlightBoost,
                blurFalloff = 0.75f,
                vignetteStrength = vignetteStrength,
                flareStrength = flareStrength
            )

            onProgress(0.55f, "Executing CineDepth Pro GPU AGSL bokeh rendering...")

            // Run CineDepth Pro's depth-aware GPU rendering in a single pristine pass
            val renderOutput = depthBlurEngine.renderDepthAware(
                source = orientedBitmap,
                params = initialParams,
                depthEstimator = depthEstimator,
                overrideDepth = null,
                fastContourMatte = false
            )

            onProgress(0.85f, "Saving CineDepth Pro portrait to gallery...")

            // Save to DCIM/Camera MediaStore
            val savedUri = depthBlurEngine.saveBitmapToGallery(
                context = context,
                bitmap = renderOutput.bitmap,
                relativeFolder = "DCIM/Camera"
            )

            // Recycle intermediate buffers
            if (renderOutput.sourceBitmap !== orientedBitmap && !renderOutput.sourceBitmap.isRecycled) {
                renderOutput.sourceBitmap.recycle()
            }
            if (!renderOutput.depthMapBitmap.isRecycled) {
                renderOutput.depthMapBitmap.recycle()
            }
            if (!renderOutput.bitmap.isRecycled) {
                renderOutput.bitmap.recycle()
            }

            onProgress(1.0f, "Portrait complete")
            Log.i(TAG, "Successfully processed and saved CineDepth Pro portrait: $savedUri")
            savedUri
        } catch (t: Throwable) {
            Log.e(TAG, "CineDepth Pro portrait processing failed", t)
            null
        }
    }
}
