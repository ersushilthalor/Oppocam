package com.camerapro.camera.engine

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import com.camerapro.camera.data.CubeLutParser
import com.camerapro.camera.model.CinemaColorProfile
import com.camerapro.camera.model.CinemaConfig
import com.camerapro.camera.model.CinematicLut
import com.camerapro.camera.model.LogBitDepth
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Professional 5-Stage Cinema Color Grading Pipeline:
 *
 * 1. Log Input / Technical Transform (CST):
 *    Maps Log sensor characteristic curves (Flat Log, Samsung APV Log, Apple Log 2, Rec.2020)
 *    into a linear/grading working space with proper dynamic range expansion, midtone pivot,
 *    and black pedestal anchoring.
 *
 * 2. Primary Grade:
 *    Applies exposure compensation, S-curve contrast pivoting at 18% middle-grey,
 *    independent shadows & highlights sculpting, washed-out black recovery,
 *    saturation, and skin-tone protected vibrance.
 *
 * 3. Creative LUT Transform:
 *    Applies authentic Hollywood film stock (Kodak 2383, Fuji Eterna, Teal & Orange,
 *    Vibrant Green / Punchy Green with selective skin-tone protection, Bleach Bypass, etc.)
 *    or user-imported 3D .cube LUT with true chromatic separation and controlled intensity blending.
 *
 * 4. Final Output Transform & Tone Mapping:
 *    ACES/Film-print inspired soft-knee highlight shoulder compression and inky black toe
 *    anchoring to strictly prevent washed-out milky blacks or harsh 255 digital clipping.
 *
 * Both the live Viewfinder preview and recorded video export consume this identical pipeline.
 */
object CinemaColorPipeline {

    /**
     * Cached identity 2D strip bitmap (size 17) used when no 3D LUT is active.
     */
    val identityStripBitmap: android.graphics.Bitmap by lazy {
        val n = 17
        val width = n * n
        val height = n
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (b in 0 until n) {
            val blue = (b.toFloat() / (n - 1) * 255f).toInt()
            for (g in 0 until n) {
                val green = (g.toFloat() / (n - 1) * 255f).toInt()
                for (r in 0 until n) {
                    val red = (r.toFloat() / (n - 1) * 255f).toInt()
                    pixels[g * width + (b * n + r)] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap
    }

    /**
     * Retrieves the 2D strip Bitmap and grid size for the active LUT (custom or preset), or null if none.
     */
    fun getLutStripBitmap(config: CinemaConfig?): Pair<android.graphics.Bitmap, Int>? {
        if (config == null) return null
        val lut = config.selectedLut
        val intensity = config.lutIntensity.coerceIn(0.0f, 1.0f)
        if (lut.isOff || intensity <= 0.001f) return null

        if (lut == CinematicLut.CUSTOM && !config.customLutPath.isNullOrBlank()) {
            val parsed = CubeLutParser.getOrLoad(config.customLutPath)
            if (parsed != null) {
                return Pair(parsed.to2DStripBitmap(), parsed.size)
            }
        } else if (lut != CinematicLut.CUSTOM && !lut.isOff) {
            val bmp = CubeLutParser.generate3DStripBitmapForPreset(lut)
            return Pair(bmp, 33)
        }
        return null
    }

    /**
     * Evaluates a preset cinematic LUT for an RGB pixel with exact tonal curves and color separation.
     */
    fun samplePresetLut(lut: CinematicLut, inR: Float, inG: Float, inB: Float): FloatArray {
        return CubeLutParser.samplePreset(lut, inR, inG, inB)
    }

    /**
     * Returns true if any Cinema color grading stage (log profile, primary grade controls,
     * selective Shadows/Highlights/Vibrance, Creative LUT, or Color Fine-Tuning) is active.
     */
    fun hasActiveTransform(
        config: CinemaConfig?,
        rec2020Params: Rec2020AutoToneParams? = null,
        includeCreativeLut: Boolean = true
    ): Boolean {
        if (config == null) return false
        if (config.hasColorFineTuning) return true
        if (computeCinemaColorMatrix(config, rec2020Params, includeCreativeLut, forGpuShader = false) != null) {
            return true
        }
        if (config.shadows != 0.0f || config.highlights != 0.0f || config.vibrance != 0.0f) {
            return true
        }
        if (config.exposure != 0.0f || config.contrast != 0.0f || config.saturation != 1.0f || config.washedOut > 0.0f) {
            return true
        }
        if (config.colorProfile != CinemaColorProfile.NATIVE) return true
        val lut = config.selectedLut
        val intensity = config.lutIntensity.coerceIn(0.0f, 1.0f)
        if (includeCreativeLut && !lut.isOff && intensity > 0.001f) {
            return true
        }
        return false
    }

    /**
     * Returns the effective Vibrant Green / Punchy Green LUT intensity (0.0 .. 1.0)
     * when VIBRANT_GREEN or PUNCHY_GREEN is active, or 0.0f otherwise.
     */
    fun getVibrantGreenLutIntensity(
        config: CinemaConfig?,
        includeCreativeLut: Boolean = true
    ): Float {
        if (config == null || !includeCreativeLut) return 0f
        return if (config.selectedLut.isVibrantGreenLut) {
            config.lutIntensity.coerceIn(0.0f, 1.0f)
        } else {
            0f
        }
    }

    /**
     * Returns true if selective per-pixel shader processing (Vibrant Green LUT,
     * 3D LUT sampling, independent Shadows / Highlights / Vibrance, or Color Fine-Tuning) is active.
     */
    fun requiresSelectiveShader(
        config: CinemaConfig?,
        includeCreativeLut: Boolean = true
    ): Boolean {
        if (config == null) return false
        if (config.hasColorFineTuning) return true
        val vibrantGreenActive = getVibrantGreenLutIntensity(config, includeCreativeLut) > 0.001f
        val selectiveControlsActive = config.shadows != 0.0f ||
                config.highlights != 0.0f ||
                config.vibrance != 0.0f ||
                config.exposure != 0.0f ||
                config.contrast != 0.0f ||
                config.saturation != 1.0f ||
                config.washedOut > 0.0f
        val custom3DLutActive = includeCreativeLut &&
                config.selectedLut == CinematicLut.CUSTOM &&
                !config.customLutPath.isNullOrBlank() &&
                config.lutIntensity > 0.001f
        val creative3DLutActive = includeCreativeLut &&
                !config.selectedLut.isOff &&
                config.lutIntensity > 0.001f
        return vibrantGreenActive || selectiveControlsActive || custom3DLutActive || creative3DLutActive
    }

    /**
     * Computes the unified 4x5 ColorMatrix for Cinema mode preview and final video export.
     * Returns null if no transform is active (e.g. Native with default parameters and no LUT).
     *
     * @param includeCreativeLut If true, incorporates Stage 3 (Creative LUT) into the matrix.
     * @param forGpuShader If true, omits non-linear selective operations and 3D LUT sampling
     * from the 4x5 matrix because they are executed with real 3D LUT sampling and per-pixel
     * selective masks in the GPU shader (AGSL / OpenGL ES).
     */
    fun computeCinemaColorMatrix(
        config: CinemaConfig?,
        rec2020Params: Rec2020AutoToneParams? = null,
        includeCreativeLut: Boolean = true,
        forGpuShader: Boolean = false
    ): ColorMatrix? {
        if (config == null) return null

        val masterMatrix = ColorMatrix()
        var hasTransform = false

        // =========================================================================
        // STAGE 1: LOG INPUT / TECHNICAL TRANSFORM (CST)
        // =========================================================================
        if (config.logBitDepth != LogBitDepth.OFF || config.colorProfile == CinemaColorProfile.HLG10 || config.colorProfile == CinemaColorProfile.HDR_LOG || config.colorProfile != CinemaColorProfile.NATIVE) {
            val technicalTransform = computeTechnicalInputTransform(config.colorProfile, rec2020Params)
            if (technicalTransform != null) {
                masterMatrix.postConcat(technicalTransform)
                hasTransform = true
            }
        }

        // When building for GPU shader, Stages 2 (3D LUT), 3 (Tonal Grading), and 4 (Output Transform)
        // are executed directly in the shader, so return the CST matrix here.
        if (forGpuShader) {
            return if (hasTransform) masterMatrix else null
        }

        // =========================================================================
        // FALLBACK COLORMATRIX PIPELINE (When no GPU shader is available):
        // Preserves processing order: CST -> 3D LUT (fallback matrix) -> Primary Grade -> Output Transform
        // =========================================================================

        // STAGE 2: CREATIVE LUT FALLBACK TRANSFORM
        if (includeCreativeLut) {
            val creativeLut = computeCreativeLutTransform(config, forGpuShader = false)
            if (creativeLut != null) {
                masterMatrix.postConcat(creativeLut)
                hasTransform = true
            }
        }

        // STAGE 3: PRIMARY GRADE (Tonal & Exposure Balance)
        val primaryGrade = computePrimaryGradeTransform(config, forGpuShader = false)
        if (primaryGrade != null) {
            masterMatrix.postConcat(primaryGrade)
            hasTransform = true
        }

        // STAGE 4: FINAL OUTPUT TRANSFORM & FILMIC TONE MAPPING
        val outputTransform = computeFinalOutputTransform(config)
        if (outputTransform != null) {
            masterMatrix.postConcat(outputTransform)
            hasTransform = true
        }

        return if (hasTransform) masterMatrix else null
    }

    /**
     * STAGE 1: Log Input / Technical Transform (Color Space & Gamma Transformation).
     * Linearizes log profiles, anchors lifted black pedestals, and normalizes middle-grey.
     */
    private fun computeTechnicalInputTransform(
        profile: CinemaColorProfile,
        rec2020Params: Rec2020AutoToneParams?
    ): ColorMatrix? {
        return when (profile) {
            CinemaColorProfile.FLAT_LOG -> {
                // Flat Log Technical Transform:
                // Normalizes lifted black pedestal (-14f offset), expands compressed log midtones
                // with 18% middle-grey pivot (contrast 1.16x), and restores sensor chroma latitude (1.14x sat)
                val c = 1.16f
                val pivot = 128f
                val pedestalOffset = -14f
                val t = (1.0f - c) * pivot + pedestalOffset
                val mat = ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                ))
                val chroma = ColorMatrix()
                chroma.setSaturation(1.14f)
                mat.postConcat(chroma)
                mat
            }

            CinemaColorProfile.SAMSUNG_APV_LOG -> {
                // Samsung APV (Advanced Professional Video) Log Technical Transform:
                // APV Log encodes wide dynamic range with code 0.025 black floor and middle-grey at 0.385 (code 98).
                // Linearization expands APV midtones (1.20x) with code 98 pivot and anchors code 6 black pedestal (-20f)
                val c = 1.20f
                val pivot = 98f
                val pedestalOffset = -20f
                val t = (1.0f - c) * pivot + pedestalOffset
                val mat = ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                ))
                val chroma = ColorMatrix()
                chroma.setSaturation(1.16f)
                mat.postConcat(chroma)
                mat
            }

            CinemaColorProfile.APPLE_LOG_2 -> {
                // Apple Log 2 Technical Transform:
                // Pulls down the code 38 baseline pedestal (-24f), expands dynamic latitude by 1.22x around middle-grey 124
                val c = 1.22f
                val pivot = 124f
                val pedestalOffset = -24f
                val t = (1.0f - c) * pivot + pedestalOffset
                val mat = ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                ))
                val chroma = ColorMatrix()
                chroma.setSaturation(1.12f)
                mat.postConcat(chroma)
                mat
            }

            CinemaColorProfile.REC_2020 -> {
                // REC.2020 Real-Time Auto Tone Control
                val p = rec2020Params ?: Rec2020AutoToneParams()
                Rec2020AutoToneEngine.computePreviewColorMatrix(p)
            }

            CinemaColorProfile.HLG10 -> {
                // ARIB STD-B67 / ITU-R BT.2100 Hybrid Log-Gamma 10-bit HDR Technical Transform:
                // - Faithful Rec.2020 wide color gamut primaries with calibrated HLG tone response
                // - Zero pedestal offset (strictly 0.0f): preserves inky blacks without crushing shadow details
                // - 0.38 middle-gray reference alignment (code 97 in 8-bit, 387 in 10-bit)
                // - Preserves full HLG dynamic range, natural skin tones, vibrant foliage, and realistic highlights
                val c = 1.04f // Clean, natural contrast calibration conforming to ARIB STD-B67
                val mat = ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, 0f,
                    0f, c, 0f, 0f, 0f,
                    0f, 0f, c, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                val hlg10Sat = ColorMatrix()
                hlg10Sat.setSaturation(1.05f) // Natural, accurate Rec.2020 wide-gamut chroma balance
                mat.postConcat(hlg10Sat)
                mat
            }

            CinemaColorProfile.PROCESSED_JPEG -> {
                // Smartphone JPEG Photo Technical Transform:
                // - Stronger but natural contrast (1.18x around 18% middle-grey pivot 128)
                // - Deeper controlled inky blacks (pedestal offset -10f)
                // - Rich natural saturation (1.16x) with faithful skin tones
                val c = 1.18f
                val pivot = 128f
                val pedestalOffset = -10f
                val t = (1.0f - c) * pivot + pedestalOffset
                val mat = ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                ))
                val chroma = ColorMatrix()
                chroma.setSaturation(1.16f)
                mat.postConcat(chroma)
                mat
            }

            CinemaColorProfile.HDR_LOG -> {
                // HDR Log Technical Transform:
                // - Zero pedestal offset (strictly 0.0f): deep inky blacks without washed-out haze
                // - Natural contrast calibration (1.08x)
                // - Natural chroma saturation balance (1.08x) retaining rich highlights and shadows
                val c = 1.08f
                val mat = ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, 0f,
                    0f, c, 0f, 0f, 0f,
                    0f, 0f, c, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                val chroma = ColorMatrix()
                chroma.setSaturation(1.08f)
                mat.postConcat(chroma)
                mat
            }

            CinemaColorProfile.NATIVE -> null
        }
    }

    /**
     * STAGE 2: Primary Grade (Tonal & Exposure Balance).
     * Adjusts exposure, contrast, washed-out black reduction, shadows, highlights,
     * saturation, and skin-tone protected vibrance.
     */
    private fun computePrimaryGradeTransform(
        config: CinemaConfig,
        forGpuShader: Boolean = false
    ): ColorMatrix? {
        if (config.colorProfile == CinemaColorProfile.REC_2020) return null

        val gradeMatrix = ColorMatrix()
        var hasPrimary = false

        // 1. Washed-Out Reduction & Inky Black Toe Sculpting
        // Eliminates the milky haze from flat log footage without crushing shadow detail
        if (config.washedOut > 0.0f) {
            val w = config.washedOut
            val pedestalReduction = -26f * w
            val contrastBoost = 1.0f + (w * 0.28f)
            val t = (1.0f - contrastBoost) * 128f + pedestalReduction
            val washedOutMatrix = ColorMatrix(floatArrayOf(
                contrastBoost, 0f, 0f, 0f, t,
                0f, contrastBoost, 0f, 0f, t,
                0f, 0f, contrastBoost, 0f, t,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(washedOutMatrix)
            hasPrimary = true
        }

        // 2. Exposure Control (+/-)
        // Note: For FLAT_LOG, sensor AE manages physical exposure; software exposure applies to others
        if (config.exposure != 0.0f && config.colorProfile != CinemaColorProfile.FLAT_LOG) {
            val expMultiplier = 2.0f.pow(config.exposure * 0.75f)
            val expMatrix = ColorMatrix(floatArrayOf(
                expMultiplier, 0f, 0f, 0f, 0f,
                0f, expMultiplier, 0f, 0f, 0f,
                0f, 0f, expMultiplier, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(expMatrix)
            hasPrimary = true
        }

        // 3. User Contrast Control (+/-)
        // S-curve contrast pivoting around 18% middle-grey (128f)
        if (config.contrast != 0.0f) {
            val c = 1.0f + (config.contrast * 0.38f)
            val t = (1.0f - c) * 128f
            val contrastMatrix = ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(contrastMatrix)
            hasPrimary = true
        }

        // 4. User Shadows & Highlights Tone Sculpting (fallback matrix when not using per-pixel shader)
        if (!forGpuShader && (config.shadows != 0.0f || config.highlights != 0.0f)) {
            val sOffset = config.shadows * 18f
            val hGain = 1.0f + (config.highlights * 0.15f)
            val shadowHighlightMatrix = ColorMatrix(floatArrayOf(
                hGain, 0f, 0f, 0f, sOffset,
                0f, hGain, 0f, 0f, sOffset,
                0f, 0f, hGain, 0f, sOffset,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(shadowHighlightMatrix)
            hasPrimary = true
        }

        // 5. Primary Saturation
        if (config.saturation != 1.0f) {
            val satMatrix = ColorMatrix()
            satMatrix.setSaturation(config.saturation)
            gradeMatrix.postConcat(satMatrix)
            hasPrimary = true
        }

        // 6. Skin-Tone Protected Vibrance (fallback matrix when not using per-pixel shader)
        // Boosts green/blue/cyan chroma while keeping R-G skin-tone axis controlled
        if (!forGpuShader && config.vibrance != 0.0f) {
            val v = config.vibrance.coerceIn(-1.0f, 1.0f)
            val rBoost = if (v > 0f) v * 0.14f else v * 0.45f
            val gBoost = if (v > 0f) v * 0.48f else v * 0.45f
            val bBoost = if (v > 0f) v * 0.46f else v * 0.45f
            val lr = 0.2126f
            val lg = 0.7152f
            val lb = 0.0722f
            val sr = 1.0f + rBoost
            val sg = 1.0f + gBoost
            val sb = 1.0f + bBoost
            val vibranceMatrix = ColorMatrix(floatArrayOf(
                lr * (1f - sr) + sr, lg * (1f - sr), lb * (1f - sr), 0f, 0f,
                lr * (1f - sg), lg * (1f - sg) + sg, lb * (1f - sg), 0f, 0f,
                lr * (1f - sb), lg * (1f - sb), lb * (1f - sb) + sb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(vibranceMatrix)
            hasPrimary = true
        }

        // 7. White Balance (Temperature & Tint) fallback matrix
        if (!forGpuShader && (config.temperature != 0.0f || config.tint != 0.0f)) {
            val tempShift = config.temperature.coerceIn(-1.0f, 1.0f) * 0.28f
            val tintShift = config.tint.coerceIn(-1.0f, 1.0f) * 0.22f
            val rMul = (1.0f + tempShift) * (1.0f - tintShift * 0.5f)
            val gMul = 1.0f + tintShift
            val bMul = (1.0f - tempShift) * (1.0f - tintShift * 0.5f)
            val wbMatrix = ColorMatrix(floatArrayOf(
                rMul, 0f, 0f, 0f, 0f,
                0f, gMul, 0f, 0f, 0f,
                0f, 0f, bMul, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(wbMatrix)
            hasPrimary = true
        }

        // 8. Black Level Pedestal Offset fallback matrix
        if (!forGpuShader && config.blackLevel != 0.0f) {
            val blOffset = config.blackLevel.coerceIn(-1.0f, 1.0f) * 38.25f
            val blMatrix = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, blOffset,
                0f, 1f, 0f, 0f, blOffset,
                0f, 0f, 1f, 0f, blOffset,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(blMatrix)
            hasPrimary = true
        }

        // 9. Whites, Blacks, Midtones fallback matrix
        if (!forGpuShader && (config.whites != 0.0f || config.blacks != 0.0f || config.midtones != 0.0f)) {
            val wGain = 1.0f + config.whites.coerceIn(-1.0f, 1.0f) * 0.18f
            val bOffset = config.blacks.coerceIn(-1.0f, 1.0f) * 16.0f
            val midC = 1.0f + config.midtones.coerceIn(-1.0f, 1.0f) * 0.20f
            val midOffset = (1.0f - midC) * 128f + bOffset
            val scale = wGain * midC
            val tonalMatrix = ColorMatrix(floatArrayOf(
                scale, 0f, 0f, 0f, midOffset,
                0f, scale, 0f, 0f, midOffset,
                0f, 0f, scale, 0f, midOffset,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(tonalMatrix)
            hasPrimary = true
        }

        // 10. Chroma Strength fallback matrix
        if (!forGpuShader && config.chromaStrength != 1.0f) {
            val chrMatrix = ColorMatrix()
            chrMatrix.setSaturation(config.chromaStrength.coerceIn(0.0f, 2.0f))
            gradeMatrix.postConcat(chrMatrix)
            hasPrimary = true
        }

        // 11. Color Matrix / Transform fallback matrix
        if (!forGpuShader && config.colorTransform != 0.0f) {
            val ct = config.colorTransform.coerceIn(-1.0f, 1.0f)
            val ctWeight = kotlin.math.abs(ct)
            val r0 = if (ct > 0) 1.08f else 1.05f
            val r1 = if (ct > 0) -0.05f else -0.02f
            val r2 = if (ct > 0) -0.03f else -0.03f
            val g0 = if (ct > 0) -0.02f else -0.05f
            val g1 = if (ct > 0) 1.06f else 1.08f
            val g2 = if (ct > 0) -0.04f else -0.03f
            val b0 = if (ct > 0) -0.04f else 0.01f
            val b1 = if (ct > 0) -0.03f else -0.03f
            val b2 = if (ct > 0) 1.07f else 1.06f
            val ctMat = ColorMatrix(floatArrayOf(
                1f * (1f - ctWeight) + r0 * ctWeight, r1 * ctWeight, r2 * ctWeight, 0f, 0f,
                g0 * ctWeight, 1f * (1f - ctWeight) + g1 * ctWeight, g2 * ctWeight, 0f, 0f,
                b0 * ctWeight, b1 * ctWeight, 1f * (1f - ctWeight) + b2 * ctWeight, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(ctMat)
            hasPrimary = true
        }

        // 12. Output Gamma fallback matrix approximation
        if (!forGpuShader && config.outputGamma != 1.0f) {
            val gammaGain = (1.0f / config.outputGamma.coerceIn(0.5f, 1.5f)).coerceIn(0.6f, 1.8f)
            val gammaOffset = (1.0f - gammaGain) * 64f
            val gammaMat = ColorMatrix(floatArrayOf(
                gammaGain, 0f, 0f, 0f, gammaOffset,
                0f, gammaGain, 0f, 0f, gammaOffset,
                0f, 0f, gammaGain, 0f, gammaOffset,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(gammaMat)
            hasPrimary = true
        }

        return if (hasPrimary) gradeMatrix else null
    }

    /**
     * STAGE 3: Creative LUT Transform (Film Stock Presets or Imported 3D .cube).
     * Implements genuine color-grading tonal adjustments (contrast, shadows, highlights,
     * color temperature tint, and RGB color separation) blended by user intensity.
     */
    private fun computeCreativeLutTransform(
        config: CinemaConfig,
        forGpuShader: Boolean = false
    ): ColorMatrix? {
        val lut = config.selectedLut
        val intensity = config.lutIntensity.coerceIn(0.0f, 1.0f)
        if (lut.isOff || intensity <= 0.001f) return null

        // Obtain the base creative transform matrix
        val rawLutMat = if (lut == CinematicLut.CUSTOM && !config.customLutPath.isNullOrBlank()) {
            CubeLutParser.getOrLoad(config.customLutPath)?.toAndroidColorMatrix()
        } else {
            buildPresetCreativeMatrix(lut)
        } ?: return null

        return if (intensity >= 0.999f) {
            rawLutMat
        } else {
            // High-precision affine matrix interpolation between Identity and Creative LUT
            val rawArr = rawLutMat.array
            val blendedArr = FloatArray(20)
            for (i in 0 until 20) {
                val identityVal = if (i == 0 || i == 6 || i == 12 || i == 18) 1.0f else 0.0f
                blendedArr[i] = identityVal * (1.0f - intensity) + rawArr[i] * intensity
            }
            ColorMatrix(blendedArr)
        }
    }

    /**
     * Builds the complete creative grading matrix for a film preset:
     * Combines contrast, color channel separation, warm/cool color temperature,
     * blacks toe, shadow toe, midtones, highlights, whites, and saturation/vibrance.
     */
    private fun buildPresetCreativeMatrix(lut: CinematicLut): ColorMatrix {
        val master = ColorMatrix()

        // 1. Channel cross-talk & color separation matrix
        val baseArr = lut.matrixValues ?: floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        master.postConcat(ColorMatrix(baseArr))

        // 2. Preset Film Contrast with 18% middle-grey pivot
        if (lut.contrast != 1.0f) {
            val c = lut.contrast
            val t = (1.0f - c) * 128f
            val contrastMat = ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            ))
            master.postConcat(contrastMat)
        }

        // 3. Warm / Cool Color Temperature Offset
        if (lut.warmCoolOffset != 0.0f) {
            val offset = lut.warmCoolOffset
            val rShift = offset * 10f
            val bShift = -offset * 10f
            val tempMat = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, rShift,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, bShift,
                0f, 0f, 0f, 1f, 0f
            ))
            master.postConcat(tempMat)
        }

        // 4. Blacks Toe & Shadow Toe Tuning
        val totalBlackShadow = (lut.blacksToe * 12f) + (lut.shadowToe * 10f)
        if (totalBlackShadow != 0.0f) {
            val toeMat = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, totalBlackShadow,
                0f, 1f, 0f, 0f, totalBlackShadow,
                0f, 0f, 1f, 0f, totalBlackShadow,
                0f, 0f, 0f, 1f, 0f
            ))
            master.postConcat(toeMat)
        }

        // 5. Midtones & Highlights Gain
        val midGain = lut.midtonesGain
        val hlGain = lut.highlightsGain
        val netGain = midGain * hlGain
        if (netGain != 1.0f) {
            val gainMat = ColorMatrix(floatArrayOf(
                netGain, 0f, 0f, 0f, 0f,
                0f, netGain, 0f, 0f, 0f,
                0f, 0f, netGain, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            master.postConcat(gainMat)
        }

        // 6. Preset Film Saturation & Vibrance
        val effectiveSat = lut.saturation * (1.0f + lut.vibrance * 0.15f)
        if (effectiveSat != 1.0f) {
            val satMat = ColorMatrix()
            satMat.setSaturation(effectiveSat)
            master.postConcat(satMat)
        }

        return master
    }

    /**
     * STAGE 4: Final Output Transform & Filmic Tone Mapping.
     * Film-print inspired tone mapping:
     * - Soft-knee highlight compression (prevents digital 255 clipping of speculars and skies)
     * - Inky black toe anchoring (removes any residual milky fog, anchoring deep rich blacks)
     * - Produces the deep, rich, dimensional cinema-camera aesthetic instead of a flat filter look.
     */
    private fun computeFinalOutputTransform(config: CinemaConfig): ColorMatrix? {
        val lut = config.selectedLut
        val profile = config.colorProfile
        val isGraded = lut != CinematicLut.NONE || profile != CinemaColorProfile.NATIVE

        if (!isGraded || profile == CinemaColorProfile.HLG10 || profile == CinemaColorProfile.HDR_LOG) return null

        // Filmic Output S-curve:
        // Anchors deep inky blacks (-3f) while softly compressing highlights (0.975x)
        // to produce clean, dimensional cinema output
        val highlightCompression = 0.975f
        val inkyBlackAnchor = -3.5f
        val filmicTransform = ColorMatrix(floatArrayOf(
            highlightCompression, 0f, 0f, 0f, inkyBlackAnchor,
            0f, highlightCompression, 0f, 0f, inkyBlackAnchor,
            0f, 0f, highlightCompression, 0f, inkyBlackAnchor,
            0f, 0f, 0f, 1f, 0f
        ))

        return filmicTransform
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Evaluates the complete selective Cinema color pipeline for an RGB pixel in [0.0 .. 1.0].
     * Matches the live Viewfinder AGSL shader and the recorded video OpenGL ES 2.0 fragment shader 1:1.
     *
     * Implements:
     * 1. Base 4x5 Cinema ColorMatrix (Log CST, Exposure, Contrast, Saturation, standard LUTs, Filmic output)
     * 2. Independent Shadows & Highlights tonal zone sculpting
     * 3. Skin-tone protected Vibrance (boosts muted colors while preventing skin oversaturation)
     * 4. Selective Vibrant Green / Punchy Green LUT:
     *    - Makes greens/foliage noticeably more saturated, vibrant, and punchy
     *    - Strictly protects skin tones (no skin saturation increase, no orange/red cast)
     *    - Keeps skin tones natural, clean, and slightly bright/fair-looking
     */
    fun evaluatePixel(
        rIn: Float,
        gIn: Float,
        bIn: Float,
        config: CinemaConfig,
        rec2020Params: Rec2020AutoToneParams? = null,
        includeCreativeLut: Boolean = true
    ): FloatArray {
        // =========================================================================
        // STAGE 1: LOG INPUT / TECHNICAL TRANSFORM (CST)
        // =========================================================================
        val baseMatrix = computeCinemaColorMatrix(
            config = config,
            rec2020Params = rec2020Params,
            includeCreativeLut = includeCreativeLut,
            forGpuShader = true
        )

        var r = rIn.coerceIn(0f, 1f)
        var g = gIn.coerceIn(0f, 1f)
        var b = bIn.coerceIn(0f, 1f)

        if (baseMatrix != null) {
            val a = baseMatrix.array
            val nr = a[0] * r + a[1] * g + a[2] * b + (a[3] + a[4]) / 255f
            val ng = a[5] * r + a[6] * g + a[7] * b + (a[8] + a[9]) / 255f
            val nb = a[10] * r + a[11] * g + a[12] * b + (a[13] + a[14]) / 255f
            r = nr.coerceIn(0f, 1f)
            g = ng.coerceIn(0f, 1f)
            b = nb.coerceIn(0f, 1f)
        }

        // =========================================================================
        // STAGE 2: 3D LUT SAMPLING & INTENSITY BLENDING
        // =========================================================================
        val lut = config.selectedLut
        val intensity = config.lutIntensity.coerceIn(0.0f, 1.0f)
        if (includeCreativeLut && !lut.isOff && intensity > 0.001f) {
            val sampled = if (lut == CinematicLut.CUSTOM && !config.customLutPath.isNullOrBlank()) {
                CubeLutParser.getOrLoad(config.customLutPath)?.sample3D(r, g, b)
            } else if (!lut.isOff && lut != CinematicLut.CUSTOM) {
                samplePresetLut(lut, r, g, b)
            } else {
                null
            }
            if (sampled != null) {
                r = (r * (1.0f - intensity) + sampled[0] * intensity).coerceIn(0f, 1f)
                g = (g * (1.0f - intensity) + sampled[1] * intensity).coerceIn(0f, 1f)
                b = (b * (1.0f - intensity) + sampled[2] * intensity).coerceIn(0f, 1f)
            }
        }

        // =========================================================================
        // STAGE 3: TONAL & COLOR GRADING CONTROLS
        // =========================================================================

        // 1. Exposure
        if (config.exposure != 0.0f && config.colorProfile != CinemaColorProfile.FLAT_LOG) {
            val expMultiplier = 2.0f.pow(config.exposure * 0.75f)
            r = (r * expMultiplier).coerceIn(0f, 1f)
            g = (g * expMultiplier).coerceIn(0f, 1f)
            b = (b * expMultiplier).coerceIn(0f, 1f)
        }

        // 2. White Balance (Temperature, Tint)
        if (config.temperature != 0.0f || config.tint != 0.0f) {
            val tempShift = config.temperature.coerceIn(-1.0f, 1.0f) * 0.28f
            val tintShift = config.tint.coerceIn(-1.0f, 1.0f) * 0.22f
            r = (r * (1.0f + tempShift) * (1.0f - tintShift * 0.5f)).coerceIn(0f, 1f)
            g = (g * (1.0f + tintShift)).coerceIn(0f, 1f)
            b = (b * (1.0f - tempShift) * (1.0f - tintShift * 0.5f)).coerceIn(0f, 1f)
        }

        // 3. Black Level Pedestal
        if (config.blackLevel != 0.0f) {
            val bl = config.blackLevel.coerceIn(-1.0f, 1.0f) * 0.15f
            r = (r + bl).coerceIn(0f, 1f)
            g = (g + bl).coerceIn(0f, 1f)
            b = (b + bl).coerceIn(0f, 1f)
        }

        // 4. Washed-Out Black Reduction
        if (config.washedOut > 0.0f) {
            val w = config.washedOut
            val pedestalReduction = -0.10f * w
            val contrastBoost = 1.0f + (w * 0.28f)
            r = (0.5f + (r - 0.5f) * contrastBoost + pedestalReduction).coerceIn(0f, 1f)
            g = (0.5f + (g - 0.5f) * contrastBoost + pedestalReduction).coerceIn(0f, 1f)
            b = (0.5f + (b - 0.5f) * contrastBoost + pedestalReduction).coerceIn(0f, 1f)
        }

        // 5. User Contrast
        if (config.contrast != 0.0f) {
            val cFactor = 1.0f + (config.contrast * 0.38f)
            r = (0.5f + (r - 0.5f) * cFactor).coerceIn(0f, 1f)
            g = (0.5f + (g - 0.5f) * cFactor).coerceIn(0f, 1f)
            b = (0.5f + (b - 0.5f) * cFactor).coerceIn(0f, 1f)
        }

        // 6. Tonal Zone Sculpting
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b

        val blacks = config.blacks.coerceIn(-1.0f, 1.0f)
        val deltaBlacks = if (blacks != 0f) {
            val wBlacks = 1.0f - smoothstep(0.0f, 0.25f, luma)
            blacks * 0.22f * wBlacks * (1.0f - luma)
        } else 0f

        val shadows = config.shadows.coerceIn(-1f, 1f)
        val deltaShadows = if (shadows != 0f) {
            val shadowMask = 1.0f - smoothstep(0.0f, 0.65f, luma)
            shadows * 0.22f * shadowMask * (1.0f - luma)
        } else 0f

        val midtones = config.midtones.coerceIn(-1.0f, 1.0f)
        val deltaMidtones = if (midtones != 0f) {
            val wMidtones = 4.0f * luma * (1.0f - luma)
            midtones * 0.25f * wMidtones
        } else 0f

        val highlights = config.highlights.coerceIn(-1f, 1f)
        val deltaHighlights = if (highlights != 0f) {
            val highlightMask = smoothstep(0.35f, 1.0f, luma)
            highlights * 0.22f * highlightMask * luma
        } else 0f

        val whites = config.whites.coerceIn(-1.0f, 1.0f)
        val deltaWhites = if (whites != 0f) {
            val wWhites = smoothstep(0.70f, 1.0f, luma)
            whites * 0.25f * wWhites * luma
        } else 0f

        val shadowRolloff = config.shadowRolloff.coerceIn(-1.0f, 1.0f)
        val deltaShadowRolloff = if (shadowRolloff != 0f) {
            val toeWeight = (1.0f - smoothstep(0.0f, 0.38f, luma)) * smoothstep(0.0f, 0.18f, luma)
            shadowRolloff * 0.18f * toeWeight
        } else 0f

        val highlightRolloff = config.highlightRolloff.coerceIn(-1.0f, 1.0f)
        val deltaHighlightRolloff = if (highlightRolloff != 0f) {
            val kneeWeight = smoothstep(0.62f, 1.0f, luma)
            -highlightRolloff * 0.20f * kneeWeight * (luma - 0.62f)
        } else 0f

        val lumaCurve = config.lumaCurve.coerceIn(-1.0f, 1.0f)
        val deltaLumaCurve = if (lumaCurve != 0f) {
            val curveFactor = 1.0f + lumaCurve * 0.65f
            val shapedLuma = if (luma < 0.5f) {
                0.5f * (2.0f * luma).pow(curveFactor)
            } else {
                1.0f - 0.5f * (2.0f * (1.0f - luma)).pow(curveFactor)
            }
            shapedLuma - luma
        } else 0f

        val totalTonalDelta = deltaBlacks + deltaShadows + deltaMidtones + deltaHighlights + deltaWhites + deltaShadowRolloff + deltaHighlightRolloff + deltaLumaCurve
        if (totalTonalDelta != 0f) {
            r = (r + totalTonalDelta).coerceIn(0f, 1f)
            g = (g + totalTonalDelta).coerceIn(0f, 1f)
            b = (b + totalTonalDelta).coerceIn(0f, 1f)
        }

        // 7. Color Transform
        val ct = config.colorTransform.coerceIn(-1.0f, 1.0f)
        if (ct != 0f) {
            val ctWeight = kotlin.math.abs(ct)
            val filmR = if (ct > 0) (1.08f * r - 0.05f * g - 0.03f * b) else (1.05f * r - 0.02f * g - 0.03f * b)
            val filmG = if (ct > 0) (-0.02f * r + 1.06f * g - 0.04f * b) else (-0.05f * r + 1.08f * g - 0.03f * b)
            val filmB = if (ct > 0) (-0.04f * r - 0.03f * g + 1.07f * b) else (0.01f * r - 0.03f * g + 1.06f * b)
            r = (r * (1f - ctWeight) + filmR * ctWeight).coerceIn(0f, 1f)
            g = (g * (1f - ctWeight) + filmG * ctWeight).coerceIn(0f, 1f)
            b = (b * (1f - ctWeight) + filmB * ctWeight).coerceIn(0f, 1f)
        }

        // 8. Saturation
        if (config.saturation != 1.0f) {
            val curL = 0.2126f * r + 0.7152f * g + 0.0722f * b
            r = (curL + (r - curL) * config.saturation).coerceIn(0f, 1f)
            g = (curL + (g - curL) * config.saturation).coerceIn(0f, 1f)
            b = (curL + (b - curL) * config.saturation).coerceIn(0f, 1f)
        }

        // 9. Chroma Strength
        val chromaStr = config.chromaStrength.coerceIn(0.0f, 2.0f)
        if (chromaStr != 1.0f) {
            val curL = 0.2126f * r + 0.7152f * g + 0.0722f * b
            r = (curL + (r - curL) * chromaStr).coerceIn(0f, 1f)
            g = (curL + (g - curL) * chromaStr).coerceIn(0f, 1f)
            b = (curL + (b - curL) * chromaStr).coerceIn(0f, 1f)
        }

        // 10. Skin-Tone Protected Vibrance
        val lumaBeforeChroma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val rgDiff = r - g
        val gbDiff = g - b
        val rbDiff = r - b
        val skinHueMask = smoothstep(0.015f, 0.085f, rgDiff) *
                smoothstep(-0.01f, 0.045f, gbDiff) *
                smoothstep(0.035f, 0.13f, rbDiff) *
                (1.0f - smoothstep(0.40f, 0.65f, rgDiff))
        val skinLumaMask = smoothstep(0.06f, 0.18f, lumaBeforeChroma) *
                (1.0f - smoothstep(0.90f, 0.99f, lumaBeforeChroma))
        val skinWeight = (skinHueMask * skinLumaMask).coerceIn(0f, 1f)

        val vibrance = config.vibrance.coerceIn(-1f, 1f)
        if (vibrance != 0f) {
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val sat = maxC - minC
            val curLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val skinAttenuation = if (vibrance > 0f) (1.0f - 0.85f * skinWeight) else 1.0f
            val satWeight = if (vibrance > 0f) (1.0f - sat * 0.75f).coerceIn(0.15f, 1.0f) else 1.0f
            val vibScale = 1.0f + vibrance * 0.65f * satWeight * skinAttenuation
            r = (curLuma + (r - curLuma) * vibScale).coerceIn(0f, 1f)
            g = (curLuma + (g - curLuma) * vibScale).coerceIn(0f, 1f)
            b = (curLuma + (b - curLuma) * vibScale).coerceIn(0f, 1f)
        }

        // 11. Selective Vibrant Green / Punchy Green LUT
        val vGreen = getVibrantGreenLutIntensity(config, includeCreativeLut)
        if (vGreen > 0.001f) {
            val lumaGreen = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val greenDomR = smoothstep(-0.035f, 0.075f, g - r)
            val greenDomB = smoothstep(0.015f, 0.12f, g - b)
            val greenWeight = (greenDomR * greenDomB * (1.0f - skinWeight)).coerceIn(0f, 1f)

            if (greenWeight > 0.001f) {
                val gw = greenWeight * vGreen
                val chromaScale = 1.0f + 0.72f * gw
                var gr = lumaGreen + (r - lumaGreen) * chromaScale
                var gg = lumaGreen + (g - lumaGreen) * chromaScale
                var gb = lumaGreen + (b - lumaGreen) * chromaScale

                val greenExcess = (g - (r + b) * 0.5f).coerceAtLeast(0f)
                gg += greenExcess * 0.38f * gw + 0.025f * gw
                gr -= greenExcess * 0.18f * gw
                gb -= greenExcess * 0.12f * gw

                val foliageContrast = 1.0f + 0.10f * gw
                r = ((gr - 0.5f) * foliageContrast + 0.5f).coerceIn(0f, 1f)
                g = ((gg - 0.5f) * foliageContrast + 0.5f).coerceIn(0f, 1f)
                b = ((gb - 0.5f) * foliageContrast + 0.5f).coerceIn(0f, 1f)
            }

            if (skinWeight > 0.001f) {
                val sw = skinWeight * vGreen
                val skinLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val cleanChromaScale = 1.0f - 0.05f * sw
                var sr = skinLuma + (r - skinLuma) * cleanChromaScale
                var sg = skinLuma + (g - skinLuma) * cleanChromaScale
                var sb = skinLuma + (b - skinLuma) * cleanChromaScale

                val fairLift = 0.052f * sw * (1.0f - skinLuma * 0.25f)
                val excessOrange = (sr - sg - 0.12f).coerceAtLeast(0f)
                sr = sr - excessOrange * 0.12f * sw + fairLift * 0.92f
                sg = sg + fairLift * 1.04f
                sb = sb + fairLift * 1.08f

                r = sr.coerceIn(0f, 1f)
                g = sg.coerceIn(0f, 1f)
                b = sb.coerceIn(0f, 1f)
            }
        }

        // 12. Local Contrast
        if (config.localContrast != 0.0f) {
            val lc = config.localContrast.coerceIn(-1.0f, 1.0f)
            val curL = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val contrastDelta = (curL - 0.5f) * lc * 0.22f * (4.0f * curL * (1.0f - curL))
            r = (r + contrastDelta).coerceIn(0f, 1f)
            g = (g + contrastDelta).coerceIn(0f, 1f)
            b = (b + contrastDelta).coerceIn(0f, 1f)
        }

        // =========================================================================
        // STAGE 4: OUTPUT TRANSFORM / TONE MAPPING / OUTPUT GAMMA
        // =========================================================================
        val tmStr = config.toneMappingStrength.coerceIn(0.0f, 1.0f)
        if (tmStr > 0.001f) {
            fun aces(x: Float): Float = ((x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f)).coerceIn(0f, 1f)
            r = (r * (1f - tmStr) + aces(r) * tmStr).coerceIn(0f, 1f)
            g = (g * (1f - tmStr) + aces(g) * tmStr).coerceIn(0f, 1f)
            b = (b * (1f - tmStr) + aces(b) * tmStr).coerceIn(0f, 1f)
        }

        val isGraded = (!config.selectedLut.isOff || config.colorProfile != CinemaColorProfile.NATIVE)
        val isHdrProfile = (config.colorProfile == CinemaColorProfile.HLG10 || config.colorProfile == CinemaColorProfile.HDR_LOG)
        if (isGraded && !isHdrProfile) {
            val highlightCompression = 0.975f
            val inkyBlackAnchor = -3.5f / 255.0f
            r = (r * highlightCompression + inkyBlackAnchor).coerceIn(0f, 1f)
            g = (g * highlightCompression + inkyBlackAnchor).coerceIn(0f, 1f)
            b = (b * highlightCompression + inkyBlackAnchor).coerceIn(0f, 1f)
        }

        val gamma = config.outputGamma.coerceIn(0.5f, 1.5f)
        if (kotlin.math.abs(gamma - 1.0f) > 0.001f) {
            r = r.pow(gamma).coerceIn(0f, 1f)
            g = g.pow(gamma).coerceIn(0f, 1f)
            b = b.pow(gamma).coerceIn(0f, 1f)
        }

        return floatArrayOf(r, g, b)
    }

    /**
     * Applies the Cinema color pipeline to the live Viewfinder TextureView.
     * Uses hardware AGSL RuntimeShader on Android 13+ (API 33+) when selective per-pixel grading
     * (Vibrant Green / Punchy Green LUT, 3D LUT sampling, Shadows, Highlights, Vibrance, or Fine-Tuning) is active,
     * with seamless fallback to RenderEffect / Paint ColorMatrixColorFilter.
     */
    fun applyToView(
        view: View,
        config: CinemaConfig?,
        rec2020Params: Rec2020AutoToneParams? = null,
        includeCreativeLut: Boolean = true
    ) {
        if (!hasActiveTransform(config, rec2020Params, includeCreativeLut) || config == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    view.setRenderEffect(null)
                } catch (ignored: Exception) {}
            }
            if (view.layerType != View.LAYER_TYPE_NONE) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            requiresSelectiveShader(config, includeCreativeLut)
        ) {
            try {
                val cstMat = computeCinemaColorMatrix(
                    config = config,
                    rec2020Params = rec2020Params,
                    includeCreativeLut = includeCreativeLut,
                    forGpuShader = true
                )
                val a = cstMat?.array ?: floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
                val shader = RuntimeShader(AGSL_CINEMA_SHADER)
                shader.setFloatUniform("uMatRow0", a[0], a[1], a[2])
                shader.setFloatUniform("uMatRow1", a[5], a[6], a[7])
                shader.setFloatUniform("uMatRow2", a[10], a[11], a[12])
                shader.setFloatUniform(
                    "uMatOffset",
                    (a[3] + a[4]) / 255f,
                    (a[8] + a[9]) / 255f,
                    (a[13] + a[14]) / 255f
                )

                // 3D LUT uniforms & strip shader
                val lutPair = if (includeCreativeLut) getLutStripBitmap(config) else null
                val bmp = lutPair?.first ?: identityStripBitmap
                val lutSize = (lutPair?.second ?: 17).toFloat()
                val use3DLut = if (lutPair != null && config.lutIntensity > 0.001f) 1.0f else 0.0f
                val lutIntensity = if (lutPair != null) config.lutIntensity.coerceIn(0f, 1f) else 0.0f

                val lutShader = android.graphics.BitmapShader(
                    bmp,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP
                )
                shader.setInputShader("uLutStrip", lutShader)
                shader.setFloatUniform("uUse3DLut", use3DLut)
                shader.setFloatUniform("uLutSize", lutSize)
                shader.setFloatUniform("uLutIntensity", lutIntensity)

                // Exposure, Contrast, Saturation, WashedOut
                val exposure = if (config.colorProfile != CinemaColorProfile.FLAT_LOG) config.exposure else 0f
                shader.setFloatUniform("uExposure", exposure)
                shader.setFloatUniform("uContrast", config.contrast)
                shader.setFloatUniform("uSaturation", config.saturation)
                shader.setFloatUniform("uWashedOut", config.washedOut)

                val isGraded = (!config.selectedLut.isOff || config.colorProfile != CinemaColorProfile.NATIVE)
                val isHdrProfile = (config.colorProfile == CinemaColorProfile.HLG10 || config.colorProfile == CinemaColorProfile.HDR_LOG)
                val filmicOutput = if (isGraded && !isHdrProfile) 1.0f else 0.0f
                shader.setFloatUniform("uFilmicOutput", filmicOutput)

                shader.setFloatUniform("uShadows", config.shadows.coerceIn(-1f, 1f))
                shader.setFloatUniform("uHighlights", config.highlights.coerceIn(-1f, 1f))
                shader.setFloatUniform("uVibrance", config.vibrance.coerceIn(-1f, 1f))
                shader.setFloatUniform(
                    "uVibrantGreenIntensity",
                    getVibrantGreenLutIntensity(config, includeCreativeLut)
                )

                // 18 Cinema Color Fine-Tuning Uniforms
                shader.setFloatUniform("uTemperature", config.temperature.coerceIn(-1f, 1f))
                shader.setFloatUniform("uTint", config.tint.coerceIn(-1f, 1f))
                shader.setFloatUniform("uWhites", config.whites.coerceIn(-1f, 1f))
                shader.setFloatUniform("uBlacks", config.blacks.coerceIn(-1f, 1f))
                shader.setFloatUniform("uMidtones", config.midtones.coerceIn(-1f, 1f))
                shader.setFloatUniform("uBlackLevel", config.blackLevel.coerceIn(-1f, 1f))
                shader.setFloatUniform("uHighlightRolloff", config.highlightRolloff.coerceIn(-1f, 1f))
                shader.setFloatUniform("uShadowRolloff", config.shadowRolloff.coerceIn(-1f, 1f))
                shader.setFloatUniform("uLocalContrast", config.localContrast.coerceIn(-1f, 1f))
                shader.setFloatUniform("uLumaCurve", config.lumaCurve.coerceIn(-1f, 1f))
                shader.setFloatUniform("uColorTransform", config.colorTransform.coerceIn(-1f, 1f))
                shader.setFloatUniform("uChromaStrength", config.chromaStrength.coerceIn(0f, 2f))
                shader.setFloatUniform("uToneMappingStrength", config.toneMappingStrength.coerceIn(0f, 1f))
                shader.setFloatUniform("uLumaNoiseReduction", config.lumaNoiseReduction.coerceIn(0f, 1f))
                shader.setFloatUniform("uChromaNoiseReduction", config.chromaNoiseReduction.coerceIn(0f, 1f))
                shader.setFloatUniform("uSharpening", config.fineSharpening.coerceIn(0f, 1f))
                shader.setFloatUniform("uMicroContrast", config.microContrast.coerceIn(-1f, 1f))
                shader.setFloatUniform("uOutputGamma", config.outputGamma.coerceIn(0.5f, 1.5f))

                val effect = RenderEffect.createRuntimeShaderEffect(shader, "inputShader")
                view.setRenderEffect(effect)
                if (view.layerType != View.LAYER_TYPE_NONE) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                return
            } catch (ignored: Throwable) {
                // Fall back to standard ColorMatrixColorFilter below
            }
        }

        val fallbackMat = computeCinemaColorMatrix(
            config = config,
            rec2020Params = rec2020Params,
            includeCreativeLut = includeCreativeLut,
            forGpuShader = false
        )
        if (fallbackMat != null) {
            val filter = ColorMatrixColorFilter(fallbackMat)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    view.setRenderEffect(RenderEffect.createColorFilterEffect(filter))
                    if (view.layerType != View.LAYER_TYPE_NONE) {
                        view.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                    return
                } catch (ignored: Exception) {}
            }
            val paint = Paint().apply { colorFilter = filter }
            view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    view.setRenderEffect(null)
                } catch (ignored: Exception) {}
            }
            if (view.layerType != View.LAYER_TYPE_NONE) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        }
    }

    private val AGSL_CINEMA_SHADER = """
        uniform shader inputShader;
        uniform shader uLutStrip;
        uniform float3 uMatRow0;
        uniform float3 uMatRow1;
        uniform float3 uMatRow2;
        uniform float3 uMatOffset;
        uniform float uUse3DLut;
        uniform float uLutSize;
        uniform float uLutIntensity;
        uniform float uExposure;
        uniform float uContrast;
        uniform float uSaturation;
        uniform float uWashedOut;
        uniform float uFilmicOutput;
        uniform float uShadows;
        uniform float uHighlights;
        uniform float uVibrance;
        uniform float uVibrantGreenIntensity;
        uniform float uTemperature;
        uniform float uTint;
        uniform float uWhites;
        uniform float uBlacks;
        uniform float uMidtones;
        uniform float uBlackLevel;
        uniform float uHighlightRolloff;
        uniform float uShadowRolloff;
        uniform float uLocalContrast;
        uniform float uLumaCurve;
        uniform float uColorTransform;
        uniform float uChromaStrength;
        uniform float uToneMappingStrength;
        uniform float uLumaNoiseReduction;
        uniform float uChromaNoiseReduction;
        uniform float uSharpening;
        uniform float uMicroContrast;
        uniform float uOutputGamma;

        float3 sample3DLut(float3 color, float lutSize) {
            float n = lutSize;
            float b = clamp(color.b, 0.0, 1.0) * (n - 1.0);
            float slice0 = floor(b);
            float slice1 = min(slice0 + 1.0, n - 1.0);
            float bWeight = b - slice0;

            float rCoord = clamp(color.r, 0.0, 1.0) * (n - 1.0);
            float gCoord = 0.5 + clamp(color.g, 0.0, 1.0) * (n - 1.0);

            float2 coord0 = float2(slice0 * n + 0.5 + rCoord, gCoord);
            float2 coord1 = float2(slice1 * n + 0.5 + rCoord, gCoord);

            half4 s0 = uLutStrip.eval(coord0);
            half4 s1 = uLutStrip.eval(coord1);
            float3 c0 = float3(s0.r, s0.g, s0.b);
            float3 c1 = float3(s1.r, s1.g, s1.b);

            return mix(c0, c1, bWeight);
        }

        half4 main(float2 fragCoord) {
            half4 src = inputShader.eval(fragCoord);
            float3 inColor = float3(src.r, src.g, src.b);

            // Sample cross neighbors for noise reduction & detail enhancement
            float3 cUp = float3(inputShader.eval(fragCoord + float2(0.0, -1.0)).rgb);
            float3 cDown = float3(inputShader.eval(fragCoord + float2(0.0, 1.0)).rgb);
            float3 cLeft = float3(inputShader.eval(fragCoord + float2(-1.0, 0.0)).rgb);
            float3 cRight = float3(inputShader.eval(fragCoord + float2(1.0, 0.0)).rgb);

            // =========================================================================
            // STAGE 1: LOG INPUT / TECHNICAL TRANSFORM (CST)
            // =========================================================================
            float3 c;
            c.r = dot(uMatRow0, inColor) + uMatOffset.r;
            c.g = dot(uMatRow1, inColor) + uMatOffset.g;
            c.b = dot(uMatRow2, inColor) + uMatOffset.b;
            c = clamp(c, 0.0, 1.0);

            // =========================================================================
            // STAGE 2: 3D LUT SAMPLING & INTENSITY BLENDING
            // =========================================================================
            if (uUse3DLut > 0.5 && uLutIntensity > 0.001) {
                float3 lutSample = sample3DLut(c, uLutSize);
                c = clamp(mix(c, lutSample, uLutIntensity), 0.0, 1.0);
            }

            // =========================================================================
            // STAGE 3: TONAL & COLOR GRADING CONTROLS
            // =========================================================================

            // 1. Exposure
            if (abs(uExposure) > 0.001) {
                float expMultiplier = pow(2.0, uExposure * 0.75);
                c = clamp(c * expMultiplier, 0.0, 1.0);
            }

            // 2. White Balance (Temperature & Tint)
            if (abs(uTemperature) > 0.001 || abs(uTint) > 0.001) {
                float tempShift = uTemperature * 0.28;
                float tintShift = uTint * 0.22;
                c.r = c.r * (1.0 + tempShift) * (1.0 - tintShift * 0.5);
                c.g = c.g * (1.0 + tintShift);
                c.b = c.b * (1.0 - tempShift) * (1.0 - tintShift * 0.5);
                c = clamp(c, 0.0, 1.0);
            }

            // 3. Black Level Pedestal
            if (abs(uBlackLevel) > 0.001) {
                c = clamp(c + float3(uBlackLevel * 0.15), 0.0, 1.0);
            }

            // 4. Washed-Out Black Reduction
            if (uWashedOut > 0.001) {
                float pedestalReduction = -0.10 * uWashedOut;
                float contrastBoost = 1.0 + (uWashedOut * 0.28);
                c = clamp(0.5 + (c - 0.5) * contrastBoost + pedestalReduction, 0.0, 1.0);
            }

            // 5. Contrast (S-Curve pivoting around middle-grey)
            if (abs(uContrast) > 0.001) {
                float contrastFactor = 1.0 + uContrast * 0.38;
                c = clamp(0.5 + (c - 0.5) * contrastFactor, 0.0, 1.0);
            }

            // 6. Tonal Zone Sculpting
            float luma = dot(c, float3(0.2126, 0.7152, 0.0722));

            float wBlacks = 1.0 - smoothstep(0.0, 0.25, luma);
            float deltaBlacks = uBlacks * 0.22 * wBlacks * (1.0 - luma);

            float shadowMask = 1.0 - smoothstep(0.0, 0.65, luma);
            float deltaShadows = uShadows * 0.22 * shadowMask * (1.0 - luma);

            float wMidtones = 4.0 * luma * (1.0 - luma);
            float deltaMidtones = uMidtones * 0.25 * wMidtones;

            float highlightMask = smoothstep(0.35, 1.0, luma);
            float deltaHighlights = uHighlights * 0.22 * highlightMask * luma;

            float wWhites = smoothstep(0.70, 1.0, luma);
            float deltaWhites = uWhites * 0.25 * wWhites * luma;

            float deltaShadowRolloff = 0.0;
            if (abs(uShadowRolloff) > 0.001) {
                float toeWeight = (1.0 - smoothstep(0.0, 0.38, luma)) * smoothstep(0.0, 0.18, luma);
                deltaShadowRolloff = uShadowRolloff * 0.18 * toeWeight;
            }

            float deltaHighlightRolloff = 0.0;
            if (abs(uHighlightRolloff) > 0.001) {
                float kneeWeight = smoothstep(0.62, 1.0, luma);
                deltaHighlightRolloff = -uHighlightRolloff * 0.20 * kneeWeight * (luma - 0.62);
            }

            float deltaLumaCurve = 0.0;
            if (abs(uLumaCurve) > 0.001) {
                float curveFactor = 1.0 + uLumaCurve * 0.65;
                float shapedLuma = (luma < 0.5) ? 
                    0.5 * pow(2.0 * luma, curveFactor) : 
                    1.0 - 0.5 * pow(2.0 * (1.0 - luma), curveFactor);
                deltaLumaCurve = shapedLuma - luma;
            }

            c = clamp(c + float3(deltaBlacks + deltaShadows + deltaMidtones + deltaHighlights + deltaWhites + deltaShadowRolloff + deltaHighlightRolloff + deltaLumaCurve), 0.0, 1.0);

            // 7. Color Transform / Matrix Cross-Talk
            if (abs(uColorTransform) > 0.001) {
                float3 filmColor;
                if (uColorTransform > 0.0) {
                    filmColor.r = 1.08 * c.r - 0.05 * c.g - 0.03 * c.b;
                    filmColor.g = -0.02 * c.r + 1.06 * c.g - 0.04 * c.b;
                    filmColor.b = -0.04 * c.r - 0.03 * c.g + 1.07 * c.b;
                } else {
                    filmColor.r = 1.05 * c.r - 0.02 * c.g - 0.03 * c.b;
                    filmColor.g = -0.05 * c.r + 1.08 * c.g - 0.03 * c.b;
                    filmColor.b = 0.01 * c.r - 0.03 * c.g + 1.06 * c.b;
                }
                c = clamp(mix(c, filmColor, abs(uColorTransform)), 0.0, 1.0);
            }

            // 8. Saturation
            if (abs(uSaturation - 1.0) > 0.001) {
                float curLuma = dot(c, float3(0.2126, 0.7152, 0.0722));
                c = clamp(float3(curLuma) + (c - float3(curLuma)) * uSaturation, 0.0, 1.0);
            }

            // 9. Chroma Strength
            if (abs(uChromaStrength - 1.0) > 0.001) {
                float curLuma = dot(c, float3(0.2126, 0.7152, 0.0722));
                c = clamp(float3(curLuma) + (c - float3(curLuma)) * uChromaStrength, 0.0, 1.0);
            }

            // 10. Skin-Tone Protected Vibrance
            float lumaPre = dot(c, float3(0.2126, 0.7152, 0.0722));
            float rgDiff = c.r - c.g;
            float gbDiff = c.g - c.b;
            float rbDiff = c.r - c.b;
            float skinHueMask = smoothstep(0.015, 0.085, rgDiff) *
                                smoothstep(-0.01, 0.045, gbDiff) *
                                smoothstep(0.035, 0.13, rbDiff) *
                                (1.0 - smoothstep(0.40, 0.65, rgDiff));
            float skinLumaMask = smoothstep(0.06, 0.18, lumaPre) *
                                 (1.0 - smoothstep(0.90, 0.99, lumaPre));
            float skinWeight = clamp(skinHueMask * skinLumaMask, 0.0, 1.0);

            if (abs(uVibrance) > 0.001) {
                float maxC = max(c.r, max(c.g, c.b));
                float minC = min(c.r, min(c.g, c.b));
                float sat = maxC - minC;
                float lumaVib = dot(c, float3(0.2126, 0.7152, 0.0722));
                float skinAtten = (uVibrance > 0.0) ? (1.0 - 0.85 * skinWeight) : 1.0;
                float satWeight = (uVibrance > 0.0) ? clamp(1.0 - sat * 0.75, 0.15, 1.0) : 1.0;
                float vibScale = 1.0 + uVibrance * 0.65 * satWeight * skinAtten;
                c = clamp(float3(lumaVib) + (c - float3(lumaVib)) * vibScale, 0.0, 1.0);
            }

            // 11. Selective Vibrant Green / Foliage LUT
            if (uVibrantGreenIntensity > 0.001) {
                float lumaGreen = dot(c, float3(0.2126, 0.7152, 0.0722));
                float greenDomR = smoothstep(-0.035, 0.075, c.g - c.r);
                float greenDomB = smoothstep(0.015, 0.12, c.g - c.b);
                float greenWeight = clamp(greenDomR * greenDomB * (1.0 - skinWeight), 0.0, 1.0);

                if (greenWeight > 0.001) {
                    float gw = greenWeight * uVibrantGreenIntensity;
                    float chromaScale = 1.0 + 0.72 * gw;
                    float3 gc = float3(lumaGreen) + (c - float3(lumaGreen)) * chromaScale;
                    float greenExcess = max(0.0, c.g - (c.r + c.b) * 0.5);
                    gc.g += greenExcess * 0.38 * gw + 0.025 * gw;
                    gc.r -= greenExcess * 0.18 * gw;
                    gc.b -= greenExcess * 0.12 * gw;
                    float foliageContrast = 1.0 + 0.10 * gw;
                    c = clamp((gc - 0.5) * foliageContrast + 0.5, 0.0, 1.0);
                }

                if (skinWeight > 0.001) {
                    float sw = skinWeight * uVibrantGreenIntensity;
                    float skinLuma = dot(c, float3(0.2126, 0.7152, 0.0722));
                    float cleanChromaScale = 1.0 - 0.05 * sw;
                    float3 sc = float3(skinLuma) + (c - float3(skinLuma)) * cleanChromaScale;
                    float fairLift = 0.052 * sw * (1.0 - skinLuma * 0.25);
                    float excessOrange = max(0.0, sc.r - sc.g - 0.12);
                    sc.r = sc.r - excessOrange * 0.12 * sw + fairLift * 0.92;
                    sc.g = sc.g + fairLift * 1.04;
                    sc.b = sc.b + fairLift * 1.08;
                    c = clamp(sc, 0.0, 1.0);
                }
            }

            // 12. Spatial detail / sharpening & noise reduction
            if (uLumaNoiseReduction > 0.001 || uChromaNoiseReduction > 0.001) {
                float lCenter = dot(c, float3(0.2126, 0.7152, 0.0722));
                float lUp = dot(cUp, float3(0.2126, 0.7152, 0.0722));
                float lDown = dot(cDown, float3(0.2126, 0.7152, 0.0722));
                float lLeft = dot(cLeft, float3(0.2126, 0.7152, 0.0722));
                float lRight = dot(cRight, float3(0.2126, 0.7152, 0.0722));

                if (uLumaNoiseReduction > 0.001) {
                    float wU = exp(-pow((lUp - lCenter) * 12.0, 2.0));
                    float wD = exp(-pow((lDown - lCenter) * 12.0, 2.0));
                    float wL = exp(-pow((lLeft - lCenter) * 12.0, 2.0));
                    float wR = exp(-pow((lRight - lCenter) * 12.0, 2.0));
                    float wSum = 1.0 + wU + wD + wL + wR;
                    float smoothLuma = (lCenter + lUp * wU + lDown * wD + lLeft * wL + lRight * wR) / wSum;
                    float lumaDelta = (smoothLuma - lCenter) * uLumaNoiseReduction;
                    c = clamp(c + float3(lumaDelta), 0.0, 1.0);
                }

                if (uChromaNoiseReduction > 0.001) {
                    float3 chrCenter = c - float3(dot(c, float3(0.2126, 0.7152, 0.0722)));
                    float3 chrUp = cUp - float3(lUp);
                    float3 chrDown = cDown - float3(lDown);
                    float3 chrLeft = cLeft - float3(lLeft);
                    float3 chrRight = cRight - float3(lRight);
                    float wU = exp(-pow((lUp - lCenter) * 8.0, 2.0));
                    float wD = exp(-pow((lDown - lCenter) * 8.0, 2.0));
                    float wL = exp(-pow((lLeft - lCenter) * 8.0, 2.0));
                    float wR = exp(-pow((lRight - lCenter) * 8.0, 2.0));
                    float wSum = 1.0 + wU + wD + wL + wR;
                    float3 smoothChroma = (chrCenter + chrUp * wU + chrDown * wD + chrLeft * wL + chrRight * wR) / wSum;
                    float curLuma = dot(c, float3(0.2126, 0.7152, 0.0722));
                    c = clamp(float3(curLuma) + mix(chrCenter, smoothChroma, uChromaNoiseReduction), 0.0, 1.0);
                }
            }

            if (uSharpening > 0.001 || abs(uMicroContrast) > 0.001 || abs(uLocalContrast) > 0.001) {
                float3 neighborAvg = 0.25 * (cUp + cDown + cLeft + cRight);
                float3 highPass = c - neighborAvg;

                if (uSharpening > 0.001) {
                    c = clamp(c + highPass * (uSharpening * 2.2), 0.0, 1.0);
                }

                if (abs(uMicroContrast) > 0.001) {
                    float3 microDetail = sign(highPass) * pow(abs(highPass), float3(0.80));
                    c = clamp(c + microDetail * (uMicroContrast * 0.9), 0.0, 1.0);
                }

                if (abs(uLocalContrast) > 0.001) {
                    float lCur = dot(c, float3(0.2126, 0.7152, 0.0722));
                    float lAvg = dot(neighborAvg, float3(0.2126, 0.7152, 0.0722));
                    float localDelta = (lCur - lAvg) * uLocalContrast * 0.85;
                    c = clamp(c + float3(localDelta), 0.0, 1.0);
                }
            }

            // =========================================================================
            // STAGE 4: OUTPUT TRANSFORM / TONE MAPPING / OUTPUT GAMMA
            // =========================================================================
            if (uToneMappingStrength > 0.001) {
                float3 aces = clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
                c = mix(c, aces, uToneMappingStrength);
            }

            if (uFilmicOutput > 0.5) {
                float highlightCompression = 0.975;
                float inkyBlackAnchor = -0.0137; // -3.5 / 255.0
                c = clamp(c * highlightCompression + inkyBlackAnchor, 0.0, 1.0);
            }

            if (abs(uOutputGamma - 1.0) > 0.001) {
                c = pow(clamp(c, 0.0, 1.0), float3(uOutputGamma));
            }

            return half4(half(c.r), half(c.g), half(c.b), src.a);
        }
    """.trimIndent()
}
