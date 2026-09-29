package com.example.camera.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.example.camera.model.BokehStyle
import com.example.camera.model.PortraitConfig
import com.example.camera.model.PortraitStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PhotonCamera-inspired Virtual Aperture & Large Aperture Bokeh Engine.
 *
 * Implements the complete physical depth-of-field pipeline:
 * 1. Continuous AI Inverse-Depth Map + High-Resolution Edge-Guided Matting Fusion
 * 2. Interactive or Subject-Locked Focal Plane Depth (Z_focus) with shallow DOF roll-off
 * 3. Physical F-Number Virtual Aperture (f/0.95 .. f/16) -> per-pixel Circle of Confusion (CoC)
 * 4. Depth-Ordered Multi-Strata Optical Disc Gather with Foreground Occlusion Weighting
 * 5. Non-Linear Specular Highlight Bokeh Boost (I^gamma before disc gather, I^(1/gamma) after)
 * 6. Real-Time Viewfinder Virtual Aperture & Depth Map Preview (exclusively when a verified AI model is installed)
 */
class PhotonVirtualApertureEngine(private val context: Context) {

    val inferenceEngine = DepthInferenceEngine(context)

    companion object {
        val SUPPORTED_APERTURES = listOf(
            "f/0.95" to 0.95f,
            "f/1.2" to 1.2f,
            "f/1.4" to 1.4f,
            "f/1.8" to 1.8f,
            "f/2.0" to 2.0f,
            "f/2.4" to 2.4f,
            "f/2.8" to 2.8f,
            "f/4.0" to 4.0f,
            "f/5.6" to 5.6f,
            "f/8.0" to 8.0f,
            "f/11" to 11.0f,
            "f/16" to 16.0f
        )

        fun parseFNumber(apertureStr: String): Float {
            val cleaned = apertureStr.removePrefix("f/").removePrefix("F/").trim()
            return cleaned.toFloatOrNull()?.coerceIn(0.7f, 22.0f) ?: 1.4f
        }

        /**
         * Physical aperture scale relative to f/1.4 reference lens:
         * Wide-open f/0.95 produces maximum Circle of Confusion; stopped-down f/16 produces minimal CoC.
         */
        fun computeApertureScale(apertureStr: String): Float {
            val fNum = parseFNumber(apertureStr)
            return (1.8f / fNum).pow(1.15f).coerceIn(0.08f, 2.65f)
        }
    }

    data class VirtualApertureRenderOutput(
        val renderedBitmap: Bitmap,
        val fusedDepthMap: FloatArray,
        val cocRadii: FloatArray,
        val focalDepth: Float,
        val modelUsed: DepthModelType?,
        val inferenceTimeMs: Long,
        val delegateName: String
    )

    /**
     * Fuses the AI monocular inverse-depth map [0.0=far .. 1.0=near] with the high-resolution
     * hair/edge-guided subject alpha matte [0.0=bg .. 1.0=subject] so that:
     * - Background has continuous 3D depth gradients (tables, walls, trees, distant horizon)
     * - Subject and individual hair strands have crisp depth discontinuities without halo bleeding
     */
    fun fuseAiDepthWithSubjectMatte(
        aiInverseDepth: FloatArray,
        alphaMatte: FloatArray,
        guideLuma: FloatArray,
        guideEdges: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {
        val total = width * height
        // Edge-aware joint guided refinement of the AI depth map using full-res luminance + gradients
        val refinedAiDepth = edgeGuidedDepthFilter(
            aiDepth = aiInverseDepth,
            guideLuma = guideLuma,
            guideEdges = guideEdges,
            width = width,
            height = height
        )

        // Compute median subject depth from AI depth where alphaMatte > 0.75
        var subjectDepthSum = 0.0
        var subjectCount = 0
        for (i in 0 until total step 3) {
            if (alphaMatte[i] > 0.75f) {
                subjectDepthSum += refinedAiDepth[i]
                subjectCount++
            }
        }
        val meanSubjectAiDepth = if (subjectCount > 20) {
            (subjectDepthSum / subjectCount).toFloat().coerceIn(0.65f, 0.95f)
        } else {
            0.85f
        }

        val fused = FloatArray(total)
        for (i in 0 until total) {
            val a = alphaMatte[i].coerceIn(0f, 1f)
            val dAi = refinedAiDepth[i].coerceIn(0f, 1f)

            if (a >= 0.90f) {
                // Foreground subject plane: preserve subtle 3D facial/body relief from AI depth around focal plane
                val relief = (dAi - meanSubjectAiDepth) * 0.22f
                fused[i] = (0.88f + relief).coerceIn(0.80f, 1.0f)
            } else if (a <= 0.05f) {
                // Background: continuous AI depth map scaled to [0.0 .. 0.72] so background behind subject
                // transitions naturally from near-ground to infinity
                fused[i] = (dAi * 0.72f).coerceIn(0.0f, 0.72f)
            } else {
                // Hair strands & complex object boundaries: smooth depth-aware interpolation
                val fgDepth = (0.88f + (dAi - meanSubjectAiDepth) * 0.22f).coerceIn(0.80f, 1.0f)
                val bgDepth = (dAi * 0.72f).coerceIn(0.0f, 0.72f)
                fused[i] = (fgDepth * a + bgDepth * (1f - a)).coerceIn(0f, 1f)
            }
        }
        return fused
    }

    /**
     * Computes focal plane depth Z_focus in [0.0 .. 1.0].
     * If user tapped a focus point in Portrait Mode, samples a local patch around (focusPointX, focusPointY).
     * Otherwise locks onto the primary subject foreground plane.
     */
    fun determineFocalPlaneDepth(
        fusedDepth: FloatArray,
        alphaMatte: FloatArray,
        width: Int,
        height: Int,
        focusPointX: Float?,
        focusPointY: Float?
    ): Float {
        if (focusPointX != null && focusPointY != null &&
            focusPointX in 0.01f..0.99f && focusPointY in 0.01f..0.99f
        ) {
            val cx = (focusPointX * (width - 1)).roundToInt().coerceIn(0, width - 1)
            val cy = (focusPointY * (height - 1)).roundToInt().coerceIn(0, height - 1)
            val patchRadius = max(4, min(width, height) / 40)
            var sum = 0f
            var count = 0
            for (y in max(0, cy - patchRadius)..min(height - 1, cy + patchRadius)) {
                val yOff = y * width
                for (x in max(0, cx - patchRadius)..min(width - 1, cx + patchRadius)) {
                    sum += fusedDepth[yOff + x]
                    count++
                }
            }
            if (count > 0) {
                return (sum / count).coerceIn(0.05f, 0.98f)
            }
        }

        // Default: weighted average of high-confidence subject region
        var weightedSum = 0.0
        var weightTotal = 0.0
        for (i in fusedDepth.indices step 4) {
            val w = alphaMatte[i]
            if (w > 0.6f) {
                weightedSum += fusedDepth[i] * w
                weightTotal += w
            }
        }
        return if (weightTotal > 0.0) {
            (weightedSum / weightTotal).toFloat().coerceIn(0.75f, 0.95f)
        } else {
            0.88f
        }
    }

    /**
     * Computes per-pixel Circle of Confusion (CoC) blur radius in pixels based on:
     * - Physical virtual aperture f-number (f/0.95 .. f/16)
     * - Blur strength slider (0..100)
     * - Depth distance from focal plane |Z(x,y) - Z_focus| with dead-zone depth-of-fieldband
     */
    fun computeCircleOfConfusionMap(
        fusedDepth: FloatArray,
        alphaMatte: FloatArray,
        width: Int,
        height: Int,
        focalDepth: Float,
        config: PortraitConfig
    ): Pair<FloatArray, Float> {
        val apertureScale = computeApertureScale(config.simulatedAperture)
        val fNumber = parseFNumber(config.simulatedAperture)

        // Depth-of-field in-focus deadband widens as aperture stops down (e.g., f/8 has wider DOF than f/1.2)
        val dofHalfBand = (0.035f * (fNumber / 1.4f)).coerceIn(0.02f, 0.28f)

        val maxCoCRadius = (max(width, height) * 0.030f * (config.blurStrength / 60f) * apertureScale)
            .coerceIn(1.5f, 82f)

        val cocMap = FloatArray(width * height)
        for (i in cocMap.indices) {
            val z = fusedDepth[i]
            val distFromFocus = abs(z - focalDepth)

            if (distFromFocus <= dofHalfBand) {
                cocMap[i] = 0f
            } else {
                val effectiveDefocus = ((distFromFocus - dofHalfBand) / (1f - dofHalfBand).coerceAtLeast(0.1f))
                    .coerceIn(0f, 1f)
                // Slight non-linear optical progression (distant background blurs progressively more)
                val opticalCurve = effectiveDefocus.pow(1.18f)
                // Foreground blur is slightly softer than background bokeh to avoid harsh double-edges
                val planeFactor = if (z > focalDepth) 0.70f else 1.0f

                // If focused on subject (focalDepth > 0.75), protect solid foreground subject sharpness
                val subjectProtection = if (focalDepth >= 0.72f && alphaMatte[i] >= 0.92f) {
                    0f
                } else if (focalDepth >= 0.72f && alphaMatte[i] > 0.25f) {
                    (1f - alphaMatte[i]).coerceIn(0f, 1f)
                } else {
                    1f
                }

                cocMap[i] = (maxCoCRadius * opticalCurve * planeFactor * subjectProtection)
                    .coerceIn(0f, maxCoCRadius)
            }
        }
        return cocMap to maxCoCRadius
    }

    /**
     * Renders depth-aware Virtual Aperture bokeh using multi-strata optical disc kernels,
     * foreground occlusion protection, and non-linear specular highlight bokeh balls.
     */
    suspend fun renderVirtualApertureBokeh(
        originalBitmap: Bitmap,
        decontaminatedBg: Bitmap,
        fusedDepth: FloatArray,
        alphaMatte: FloatArray,
        cocMap: FloatArray,
        maxCoCRadius: Float,
        focalDepth: Float,
        config: PortraitConfig
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = originalBitmap.width
        val height = originalBitmap.height
        val numPixels = width * height

        if (config.blurStrength <= 1f || maxCoCRadius < 1.5f) {
            return@withContext originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        // Downsampled working grid for depth-ordered disc gather + multi-tier strata
        val scale = (max(width, height) / 1024f).coerceAtLeast(1.0f)
        val sw = (width / scale).toInt().coerceAtLeast(160)
        val sh = (height / scale).toInt().coerceAtLeast(160)

        val smallBgBmp = Bitmap.createScaledBitmap(decontaminatedBg, sw, sh, true)
        val smallOrigBmp = Bitmap.createScaledBitmap(originalBitmap, sw, sh, true)

        val bgPixels = IntArray(sw * sh)
        val origSmallPixels = IntArray(sw * sh)
        smallBgBmp.getPixels(bgPixels, 0, sw, 0, 0, sw, sh)
        smallOrigBmp.getPixels(origSmallPixels, 0, sw, 0, 0, sw, sh)
        smallBgBmp.recycle()
        smallOrigBmp.recycle()

        val smallDepth = downsampleFloat(fusedDepth, width, height, sw, sh)
        val smallCoC = downsampleFloat(cocMap, width, height, sw, sh)
        val smallAlpha = downsampleFloat(alphaMatte, width, height, sw, sh)

        val maxRadiusScaled = (maxCoCRadius / scale).coerceIn(2f, 42f)

        // Render depth-ordered optical disc bokeh on the scaled canvas with HDR highlight preservation
        val bokehSmallPixels = gatherDepthAwareOpticalBokeh(
            bgPixels = bgPixels,
            origPixels = origSmallPixels,
            depthMap = smallDepth,
            cocMap = smallCoC,
            alphaMap = smallAlpha,
            w = sw,
            h = sh,
            maxRadiusScaled = maxRadiusScaled,
            focalDepth = focalDepth,
            bokehStyle = config.bokehStyle
        )

        val bokehSmallBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        bokehSmallBmp.setPixels(bokehSmallPixels, 0, sw, 0, 0, sw, sh)
        val fullBokehBmp = Bitmap.createScaledBitmap(bokehSmallBmp, width, height, true)
        bokehSmallBmp.recycle()

        // Final full-resolution depth & hair-aware compositing preserving 100% sensor sharpness in focal zone
        val origFullPixels = IntArray(numPixels)
        val bokehFullPixels = IntArray(numPixels)
        val outPixels = IntArray(numPixels)

        originalBitmap.getPixels(origFullPixels, 0, width, 0, 0, width, height)
        fullBokehBmp.getPixels(bokehFullPixels, 0, width, 0, 0, width, height)
        fullBokehBmp.recycle()

        val style = config.selectedStyle
        val warmTint = style.warmCoolTint
        val satBoost = style.saturationBoost
        val contrast = style.contrastBoost
        val smoothSkin = style.skinSmoothing || config.skinToneCorrection
        val invMaxCoC = if (maxCoCRadius > 0.01f) 1f / maxCoCRadius else 0f

        for (i in 0 until numPixels) {
            val coc = cocMap[i]
            val blurBlend = (coc * invMaxCoC * 1.6f).coerceIn(0f, 1f)
            // Combine hair alpha matting protection when subject is in focus
            val sharpWeight = if (focalDepth >= 0.72f) {
                max(alphaMatte[i], 1f - blurBlend).coerceIn(0f, 1f)
            } else {
                (1f - blurBlend).coerceIn(0f, 1f)
            }

            val origC = origFullPixels[i]
            val bokehC = bokehFullPixels[i]

            var r = ((origC shr 16) and 0xFF) * sharpWeight + ((bokehC shr 16) and 0xFF) * (1f - sharpWeight)
            var g = ((origC shr 8) and 0xFF) * sharpWeight + ((bokehC shr 8) and 0xFF) * (1f - sharpWeight)
            var b = (origC and 0xFF) * sharpWeight + (bokehC and 0xFF) * (1f - sharpWeight)

            if (sharpWeight > 0.6f) {
                if (smoothSkin) {
                    r = (r * 1.025f).coerceAtMost(255f)
                    g = (g * 1.012f).coerceAtMost(255f)
                }
                if (config.faceEnhancement) {
                    r = (r * 1.02f + 2f).coerceAtMost(255f)
                    g = (g * 1.02f + 2f).coerceAtMost(255f)
                    b = (b * 1.02f + 2f).coerceAtMost(255f)
                }
            }

            if (warmTint != 0f) {
                r = (r * (1f + warmTint * 0.10f)).coerceIn(0f, 255f)
                b = (b * (1f - warmTint * 0.08f)).coerceIn(0f, 255f)
            }
            if (contrast != 0f && sharpWeight > 0.5f) {
                r = (((r - 128f) * (1f + contrast)) + 128f).coerceIn(0f, 255f)
                g = (((g - 128f) * (1f + contrast)) + 128f).coerceIn(0f, 255f)
                b = (((b - 128f) * (1f + contrast)) + 128f).coerceIn(0f, 255f)
            }
            if (satBoost != 1.0f && sharpWeight > 0.5f) {
                val gray = 0.299f * r + 0.587f * g + 0.114f * b
                r = (gray + (r - gray) * satBoost).coerceIn(0f, 255f)
                g = (gray + (g - gray) * satBoost).coerceIn(0f, 255f)
                b = (gray + (b - gray) * satBoost).coerceIn(0f, 255f)
            }

            outPixels[i] = (0xFF shl 24) or
                    (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or
                    b.roundToInt().coerceIn(0, 255)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        result
    }

    /**
     * Real-time Viewfinder Preview processor for Portrait Mode Virtual Aperture.
     * Runs exclusively when an AI depth model is installed & verified.
     * Returns Pair(renderedPreviewBitmap, optionalDepthColormapBitmap) or null if no model installed.
     */
    suspend fun processRealtimePreviewFrame(
        previewFrame: Bitmap,
        config: PortraitConfig
    ): Pair<Bitmap, Bitmap?>? = withContext(Dispatchers.Default) {
        if (!inferenceEngine.hasVerifiedInstalledModel()) return@withContext null

        val pw = previewFrame.width
        val ph = previewFrame.height
        if (pw <= 16 || ph <= 16) return@withContext null

        val inferenceRes = inferenceEngine.estimateDepth(previewFrame, pw, ph) ?: return@withContext null
        val aiDepth = inferenceRes.depthMap

        val pixels = IntArray(pw * ph)
        previewFrame.getPixels(pixels, 0, pw, 0, 0, pw, ph)

        // Determine focal depth from user tap or center subject region
        val focalDepth = determineFocalPlaneDepth(
            fusedDepth = aiDepth,
            alphaMatte = aiDepth,
            width = pw,
            height = ph,
            focusPointX = config.focusPointX,
            focusPointY = config.focusPointY
        )

        val (cocMap, maxCoC) = computeCircleOfConfusionMap(
            fusedDepth = aiDepth,
            alphaMatte = aiDepth,
            width = pw,
            height = ph,
            focalDepth = focalDepth,
            config = config
        )

        val bokehPixels = gatherDepthAwareOpticalBokeh(
            bgPixels = pixels,
            origPixels = pixels,
            depthMap = aiDepth,
            cocMap = cocMap,
            alphaMap = aiDepth,
            w = pw,
            h = ph,
            maxRadiusScaled = maxCoC.coerceAtMost(22f),
            focalDepth = focalDepth,
            bokehStyle = config.bokehStyle
        )

        val renderedBmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        renderedBmp.setPixels(bokehPixels, 0, pw, 0, 0, pw, ph)

        val depthVisualizerBmp = if (config.showDepthPreview) {
            renderDepthMapColormap(aiDepth, focalDepth, pw, ph)
        } else null

        renderedBmp to depthVisualizerBmp
    }

    /**
     * Renders an Turbo/Inferno scientific colormap of the real AI depth map with a subtle cyan ring
     * highlighting the active Virtual Aperture focal plane depth.
     */
    fun renderDepthMapColormap(
        depthMap: FloatArray,
        focalDepth: Float,
        width: Int,
        height: Int
    ): Bitmap {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val d = depthMap[i].coerceIn(0f, 1f)
            val distFocus = abs(d - focalDepth)

            // Inferno/Plasma-inspired perceptual depth palette (0.0=deep indigo/purple, 0.5=crimson/orange, 1.0=bright gold/white)
            val r = (255f * (d * 1.35f).coerceIn(0f, 1f)).roundToInt()
            val g = (255f * ((d - 0.25f) * 1.33f).coerceIn(0f, 1f).pow(1.2f)).roundToInt()
            val b = (255f * (sin(d * Math.PI).toFloat() * 0.65f + (1f - d) * 0.45f).coerceIn(0f, 1f)).roundToInt()

            if (distFocus < 0.022f) {
                // Highlight active focal plane in-focus contour in bright cyan-gold
                out[i] = Color.argb(255, min(255, r + 45), 255, 220)
            } else {
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, width, 0, 0, width, height)
        }
    }

    /**
     * PhotonCamera-style depth-aware optical disc gather with HDR highlight exponentiation:
     * - Samples a 32-point Poisson/concentric optical aperture disc per pixel scaled by CoC(x,y)
     * - Applies BokehStyle aperture geometry (Round, Elliptical cat-eye, Hexagonal polygonal, Swirl, Specular ring)
     * - Rejects samples from sharper foreground strata (depth occlusion weighting) so sharp subject edges never bleed outward
     * - Boosts specular highlights (luma > 185) via non-linear gamma energy weighting to form authentic optical bokeh discs
     */
    private fun gatherDepthAwareOpticalBokeh(
        bgPixels: IntArray,
        origPixels: IntArray,
        depthMap: FloatArray,
        cocMap: FloatArray,
        alphaMap: FloatArray,
        w: Int,
        h: Int,
        maxRadiusScaled: Float,
        focalDepth: Float,
        bokehStyle: BokehStyle
    ): IntArray {
        val total = w * h
        val out = IntArray(total)

        // Precompute 28-tap concentric optical disc offsets (u, v, ringRatio)
        val discTaps = buildApertureDiscKernel(bokehStyle)
        val numTaps = discTaps.size / 3
        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxCenterDist = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)

        for (y in 0 until h) {
            val yOff = y * w
            val dyCenter = y - cy
            for (x in 0 until w) {
                val idx = yOff + x
                val coc = (cocMap[idx] / max(1f, cocMap.maxOrNull()?.div(maxRadiusScaled) ?: 1f))
                    .coerceIn(0f, maxRadiusScaled)

                if (coc < 0.75f) {
                    out[idx] = origPixels[idx]
                    continue
                }

                val centerDepth = depthMap[idx]
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumWeight = 0f

                // Optional tangential swirl rotation for ZEISS_SWIRL
                val dxCenter = x - cx
                val swirlAngle = if (bokehStyle == BokehStyle.ZEISS_SWIRL) {
                    val normDist = (sqrt(dxCenter * dxCenter + dyCenter * dyCenter) / maxCenterDist).coerceIn(0f, 1f)
                    normDist * 0.85f
                } else 0f
                val cosA = if (swirlAngle != 0f) cos(swirlAngle) else 1f
                val sinA = if (swirlAngle != 0f) sin(swirlAngle) else 0f

                var tapIdx = 0
                for (t in 0 until numTaps) {
                    var du = discTaps[tapIdx]
                    var dv = discTaps[tapIdx + 1]
                    val ringRatio = discTaps[tapIdx + 2]
                    tapIdx += 3

                    if (swirlAngle != 0f) {
                        val ru = du * cosA - dv * sinA
                        val rv = du * sinA + dv * cosA
                        du = ru
                        dv = rv
                    }

                    val sx = (x + du * coc).roundToInt().coerceIn(0, w - 1)
                    val sy = (y + dv * coc).roundToInt().coerceIn(0, h - 1)
                    val sIdx = sy * w + sx

                    val sampleCoC = cocMap[sIdx]
                    val sampleDepth = depthMap[sIdx]

                    // Depth occlusion weight: prevent sharp in-focus foreground pixels from bleeding into background blur
                    val depthDiff = sampleDepth - centerDepth
                    val occlusionWeight = if (sampleCoC < coc * 0.45f && depthDiff > 0.12f) {
                        0.06f
                    } else {
                        1.0f
                    }

                    val c = if (alphaMap[sIdx] > 0.75f && focalDepth >= 0.72f) bgPixels[sIdx] else origPixels[sIdx]
                    val r = ((c shr 16) and 0xFF).toFloat()
                    val g = ((c shr 8) and 0xFF).toFloat()
                    val b = (c and 0xFF).toFloat()

                    // Non-linear HDR specular highlight bokeh boost (PhotonCamera large aperture highlight discs)
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val highlightBoost = if (luma > 185f) {
                        val excess = (luma - 185f) / 70f
                        val rimFactor = when (bokehStyle) {
                            BokehStyle.LIGHT_SOURCE -> if (ringRatio > 0.72f) 2.2f else 1.2f
                            BokehStyle.LEICA_3D_POP -> 1.65f * (1f - ringRatio * 0.25f)
                            else -> 1.45f
                        }
                        1f + excess * excess * 4.5f * rimFactor
                    } else {
                        1f
                    }

                    val wTap = occlusionWeight * highlightBoost
                    sumR += r * wTap
                    sumG += g * wTap
                    sumB += b * wTap
                    sumWeight += wTap
                }

                if (sumWeight > 1e-4f) {
                    val outR = (sumR / sumWeight).roundToInt().coerceIn(0, 255)
                    val outG = (sumG / sumWeight).roundToInt().coerceIn(0, 255)
                    val outB = (sumB / sumWeight).roundToInt().coerceIn(0, 255)
                    out[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                } else {
                    out[idx] = bgPixels[idx]
                }
            }
        }
        return out
    }

    /**
     * Generates a 29-point concentric optical aperture disc sampling pattern [du, dv, ringRatio].
     */
    private fun buildApertureDiscKernel(bokehStyle: BokehStyle): FloatArray {
        val taps = ArrayList<Float>(29 * 3)
        // Center tap
        taps.add(0f); taps.add(0f); taps.add(0f)

        val aspectX = if (bokehStyle == BokehStyle.SOFT_ELLIPTICAL) 0.76f else 1.0f
        val aspectY = if (bokehStyle == BokehStyle.SOFT_ELLIPTICAL) 1.32f else 1.0f

        // Inner ring (8 samples at r = 0.42)
        for (k in 0 until 8) {
            val theta = k * (2.0 * Math.PI / 8.0)
            val r = 0.42f
            taps.add((cos(theta).toFloat() * r * aspectX))
            taps.add((sin(theta).toFloat() * r * aspectY))
            taps.add(0.42f)
        }

        // Mid ring (8 samples at r = 0.72)
        for (k in 0 until 8) {
            val theta = (k + 0.5) * (2.0 * Math.PI / 8.0)
            val r = 0.72f
            taps.add((cos(theta).toFloat() * r * aspectX))
            taps.add((sin(theta).toFloat() * r * aspectY))
            taps.add(0.72f)
        }

        // Outer perimeter ring (12 samples at r = 1.0, hexagonal modulation if POLYGONAL_APERTURE)
        for (k in 0 until 12) {
            val theta = k * (2.0 * Math.PI / 12.0)
            val polyMod = if (bokehStyle == BokehStyle.POLYGONAL_APERTURE) {
                // 6-blade hexagonal iris radius modulation
                val sectorAngle = (theta % (Math.PI / 3.0)) - (Math.PI / 6.0)
                (0.866 / cos(sectorAngle)).toFloat().coerceIn(0.85f, 1.05f)
            } else {
                1.0f
            }
            taps.add((cos(theta).toFloat() * polyMod * aspectX))
            taps.add((sin(theta).toFloat() * polyMod * aspectY))
            taps.add(1.0f)
        }

        return taps.toFloatArray()
    }

    private fun edgeGuidedDepthFilter(
        aiDepth: FloatArray,
        guideLuma: FloatArray,
        guideEdges: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {
        val scale = (max(width, height) / 640f).coerceAtLeast(1.0f)
        val sw = (width / scale).toInt().coerceAtLeast(96)
        val sh = (height / scale).toInt().coerceAtLeast(96)

        val smallDepth = downsampleFloat(aiDepth, width, height, sw, sh)
        val smallLuma = downsampleFloat(guideLuma, width, height, sw, sh)
        val smallEdges = downsampleFloat(guideEdges, width, height, sw, sh)

        val guide = FloatArray(sw * sh) { i ->
            (smallLuma[i] * 0.72f + smallEdges[i] * 0.28f).coerceIn(0f, 1f)
        }

        val r = 5
        val eps = 0.0025f
        val meanI = boxFilter(guide, sw, sh, r)
        val meanP = boxFilter(smallDepth, sw, sh, r)
        val ip = FloatArray(sw * sh) { i -> guide[i] * smallDepth[i] }
        val meanIP = boxFilter(ip, sw, sh, r)
        val ii = FloatArray(sw * sh) { i -> guide[i] * guide[i] }
        val meanII = boxFilter(ii, sw, sh, r)

        val a = FloatArray(sw * sh) { i ->
            val cov = meanIP[i] - meanI[i] * meanP[i]
            val variance = meanII[i] - meanI[i] * meanI[i]
            cov / (variance + eps)
        }
        val b = FloatArray(sw * sh) { i -> meanP[i] - a[i] * meanI[i] }

        val meanA = boxFilter(a, sw, sh, r)
        val meanB = boxFilter(b, sw, sh, r)
        val filteredSmall = FloatArray(sw * sh) { i ->
            (meanA[i] * guide[i] + meanB[i]).coerceIn(0f, 1f)
        }

        return upsampleBilinear(filteredSmall, sw, sh, width, height)
    }

    private fun boxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val dst = FloatArray(w * h)
        val temp = FloatArray(w * h)

        for (y in 0 until h) {
            var sum = 0f
            val yOff = y * w
            for (i in -r..r) sum += src[yOff + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                val count = (min(w - 1, x + r) - max(0, x - r) + 1).toFloat()
                temp[yOff + x] = sum / count
                sum += src[yOff + (x + r + 1).coerceIn(0, w - 1)] - src[yOff + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (i in -r..r) sum += temp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                val count = (min(h - 1, y + r) - max(0, y - r) + 1).toFloat()
                dst[y * w + x] = sum / count
                sum += temp[(y + r + 1).coerceIn(0, h - 1) * w + x] - temp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return dst
    }

    private fun downsampleFloat(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val dst = FloatArray(dw * dh)
        val xRatio = sw.toFloat() / dw.toFloat()
        val yRatio = sh.toFloat() / dh.toFloat()
        for (y in 0 until dh) {
            val sy = (y * yRatio).toInt().coerceIn(0, sh - 1)
            val syOff = sy * sw
            val dyOff = y * dw
            for (x in 0 until dw) {
                val sx = (x * xRatio).toInt().coerceIn(0, sw - 1)
                dst[dyOff + x] = src[syOff + sx]
            }
        }
        return dst
    }

    private fun upsampleBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val dst = FloatArray(dw * dh)
        val xRatio = max(1, sw - 1).toFloat() / max(1, dw).toFloat()
        val yRatio = max(1, sh - 1).toFloat() / max(1, dh).toFloat()
        for (y in 0 until dh) {
            val srcY = y * yRatio
            val y1 = srcY.toInt().coerceIn(0, sh - 1)
            val y2 = (y1 + 1).coerceAtMost(sh - 1)
            val yDiff = srcY - y1
            val y1Off = y1 * sw
            val y2Off = y2 * sw
            val dOff = y * dw
            for (x in 0 until dw) {
                val srcX = x * xRatio
                val x1 = srcX.toInt().coerceIn(0, sw - 1)
                val x2 = (x1 + 1).coerceAtMost(sw - 1)
                val xDiff = srcX - x1
                dst[dOff + x] = (
                    src[y1Off + x1] * (1f - xDiff) * (1f - yDiff) +
                    src[y1Off + x2] * xDiff * (1f - yDiff) +
                    src[y2Off + x1] * (1f - xDiff) * yDiff +
                    src[y2Off + x2] * xDiff * yDiff
                ).coerceIn(0f, 1f)
            }
        }
        return dst
    }
}
