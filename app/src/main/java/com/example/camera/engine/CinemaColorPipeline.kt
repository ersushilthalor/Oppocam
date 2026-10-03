package com.example.camera.engine

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import com.example.camera.data.CubeLutParser
import com.example.camera.model.CinemaColorProfile
import com.example.camera.model.CinemaConfig
import com.example.camera.model.CinematicLut
import com.example.camera.model.LogBitDepth
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
     * Returns true if any Cinema color grading stage (log profile, primary grade controls,
     * selective Shadows/Highlights/Vibrance, or Creative LUT) is active.
     */
    fun hasActiveTransform(
        config: CinemaConfig?,
        rec2020Params: Rec2020AutoToneParams? = null,
        includeCreativeLut: Boolean = true
    ): Boolean {
        if (config == null) return false
        if (computeCinemaColorMatrix(config, rec2020Params, includeCreativeLut, forGpuShader = false) != null) {
            return true
        }
        if (config.shadows != 0.0f || config.highlights != 0.0f || config.vibrance != 0.0f) {
            return true
        }
        val lut = config.selectedLut
        val intensity = config.lutIntensity.coerceIn(0.0f, 1.0f)
        if (includeCreativeLut && lut != CinematicLut.NONE && intensity > 0.001f) {
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
     * Returns true if selective per-pixel shader processing (Vibrant Green LUT or
     * independent Shadows / Highlights / Vibrance) is active.
     */
    fun requiresSelectiveShader(
        config: CinemaConfig?,
        includeCreativeLut: Boolean = true
    ): Boolean {
        if (config == null) return false
        val vibrantGreenActive = getVibrantGreenLutIntensity(config, includeCreativeLut) > 0.001f
        val selectiveControlsActive = config.shadows != 0.0f ||
                config.highlights != 0.0f ||
                config.vibrance != 0.0f
        return vibrantGreenActive || selectiveControlsActive
    }

    /**
     * Computes the unified 4x5 ColorMatrix for Cinema mode preview and final video export.
     * Returns null if no transform is active (e.g. Native with default parameters and no LUT).
     *
     * @param includeCreativeLut If true, incorporates Stage 3 (Creative LUT) into the matrix.
     * @param forGpuShader If true, omits non-linear selective operations (Shadows, Highlights,
     * Vibrance, and Vibrant Green / Punchy Green LUT) from the 4x5 matrix because they are
     * executed with per-pixel selective masks in the GPU shader (AGSL / OpenGL ES).
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
        if (config.logBitDepth != LogBitDepth.OFF || config.colorProfile == CinemaColorProfile.HLG10 || config.colorProfile == CinemaColorProfile.HDR_LOG) {
            val technicalTransform = computeTechnicalInputTransform(config.colorProfile, rec2020Params)
            if (technicalTransform != null) {
                masterMatrix.postConcat(technicalTransform)
                hasTransform = true
            }
        }

        // =========================================================================
        // STAGE 2: PRIMARY GRADE (Tonal & Exposure Balance)
        // =========================================================================
        val primaryGrade = computePrimaryGradeTransform(config, forGpuShader = forGpuShader)
        if (primaryGrade != null) {
            masterMatrix.postConcat(primaryGrade)
            hasTransform = true
        }

        // =========================================================================
        // STAGE 3: CREATIVE LUT TRANSFORM (Film Stock / Custom .cube)
        // =========================================================================
        if (includeCreativeLut) {
            val creativeLut = computeCreativeLutTransform(config, forGpuShader = forGpuShader)
            if (creativeLut != null) {
                masterMatrix.postConcat(creativeLut)
                hasTransform = true
            }
        }

        // =========================================================================
        // STAGE 4: FINAL OUTPUT TRANSFORM & FILMIC TONE MAPPING
        // =========================================================================
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
        if (lut == CinematicLut.NONE || intensity <= 0.001f || ((config.colorProfile == CinemaColorProfile.HLG10 || config.colorProfile == CinemaColorProfile.HDR_LOG) && lut == CinematicLut.REC_709)) return null

        // When rendering on GPU shader (AGSL Viewfinder or OpenGL Video Processor),
        // VIBRANT_GREEN / PUNCHY_GREEN is executed via true per-pixel selective color masks
        // in the shader so skin tones are 100% isolated from the foliage boost.
        if (forGpuShader && lut.isVibrantGreenLut) {
            return null
        }

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
     * shadow toe, and highlight shoulder into a unified transform.
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

        // 4. Shadow Toe & Highlight Roll-off Tuning
        if (lut.shadowToe != 0.0f) {
            val toeShift = lut.shadowToe * 12f
            val toeMat = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, toeShift,
                0f, 1f, 0f, 0f, toeShift,
                0f, 0f, 1f, 0f, toeShift,
                0f, 0f, 0f, 1f, 0f
            ))
            master.postConcat(toeMat)
        }

        // 5. Preset Film Saturation
        if (lut.saturation != 1.0f) {
            val satMat = ColorMatrix()
            satMat.setSaturation(lut.saturation)
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

        // 1. Independent Shadows & Highlights Tonal Recovery
        val shadows = config.shadows.coerceIn(-1f, 1f)
        val highlights = config.highlights.coerceIn(-1f, 1f)
        if (shadows != 0f || highlights != 0f) {
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            // Shadow mask: concentrated in darks (luma < 0.55), zero in highlights
            val shadowMask = (1.0f - smoothstep(0.0f, 0.65f, luma))
            val shadowDelta = shadows * 0.22f * shadowMask * (1.0f - luma)
            // Highlight mask: concentrated in brights (luma > 0.45), zero in shadows
            val highlightMask = smoothstep(0.35f, 1.0f, luma)
            val highlightDelta = highlights * 0.22f * highlightMask * luma

            val totalDelta = shadowDelta + highlightDelta
            r = (r + totalDelta).coerceIn(0f, 1f)
            g = (g + totalDelta).coerceIn(0f, 1f)
            b = (b + totalDelta).coerceIn(0f, 1f)
        }

        // Compute skin-tone protection weight (human skin: R > G > B with warm peach/beige/brown ratios)
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

        // 2. Independent Vibrance with Skin-Tone Protection
        val vibrance = config.vibrance.coerceIn(-1f, 1f)
        if (vibrance != 0f) {
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val sat = maxC - minC
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            // Positive vibrance selectively boosts muted non-skin colors; skin tones are protected
            val skinAttenuation = if (vibrance > 0f) (1.0f - 0.85f * skinWeight) else 1.0f
            val satWeight = if (vibrance > 0f) (1.0f - sat * 0.75f).coerceIn(0.15f, 1.0f) else 1.0f
            val vibScale = 1.0f + vibrance * 0.65f * satWeight * skinAttenuation
            r = (luma + (r - luma) * vibScale).coerceIn(0f, 1f)
            g = (luma + (g - luma) * vibScale).coerceIn(0f, 1f)
            b = (luma + (b - luma) * vibScale).coerceIn(0f, 1f)
        }

        // 3. Selective Vibrant Green / Punchy Green LUT
        val vGreen = getVibrantGreenLutIntensity(config, includeCreativeLut)
        if (vGreen > 0.001f) {
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            // Selective green/foliage mask: targets yellow-greens, emerald foliage, and deep forest greens
            // Strictly multiplied by (1 - skinWeight) so skin tones are never affected by the green boost
            val greenDomR = smoothstep(-0.035f, 0.075f, g - r)
            val greenDomB = smoothstep(0.015f, 0.12f, g - b)
            val greenWeight = (greenDomR * greenDomB * (1.0f - skinWeight)).coerceIn(0f, 1f)

            if (greenWeight > 0.001f) {
                val gw = greenWeight * vGreen
                // Noticeably boost green saturation, vibrance, and punch
                val chromaScale = 1.0f + 0.72f * gw
                var gr = luma + (r - luma) * chromaScale
                var gg = luma + (g - luma) * chromaScale
                var gb = luma + (b - luma) * chromaScale

                // Extra foliage punch: enrich green channel separation and deepen red/blue contrast in foliage
                val greenExcess = (g - (r + b) * 0.5f).coerceAtLeast(0f)
                gg += greenExcess * 0.38f * gw + 0.025f * gw
                gr -= greenExcess * 0.18f * gw
                gb -= greenExcess * 0.12f * gw

                // Subtle punchy contrast S-curve on foliage
                val foliageContrast = 1.0f + 0.10f * gw
                r = ((gr - 0.5f) * foliageContrast + 0.5f).coerceIn(0f, 1f)
                g = ((gg - 0.5f) * foliageContrast + 0.5f).coerceIn(0f, 1f)
                b = ((gb - 0.5f) * foliageContrast + 0.5f).coerceIn(0f, 1f)
            }

            if (skinWeight > 0.001f) {
                val sw = skinWeight * vGreen
                val skinLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                // Protect skin tones: prevent any saturation increase or unnatural orange/red cast
                // Keep skin tones natural, clean, and slightly bright/fair-looking
                val cleanChromaScale = 1.0f - 0.05f * sw
                var sr = skinLuma + (r - skinLuma) * cleanChromaScale
                var sg = skinLuma + (g - skinLuma) * cleanChromaScale
                var sb = skinLuma + (b - skinLuma) * cleanChromaScale

                // Gentle fairness lift & clean undertone balance (prevents orange/red heaviness)
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

        return floatArrayOf(r, g, b)
    }

    /**
     * Applies the Cinema color pipeline to the live Viewfinder TextureView.
     * Uses hardware AGSL RuntimeShader on Android 13+ (API 33+) when selective per-pixel grading
     * (Vibrant Green / Punchy Green LUT, Shadows, Highlights, or Vibrance) is active,
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
                val baseMat = computeCinemaColorMatrix(
                    config = config,
                    rec2020Params = rec2020Params,
                    includeCreativeLut = includeCreativeLut,
                    forGpuShader = true
                )
                val a = baseMat?.array ?: floatArrayOf(
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
                shader.setFloatUniform("uShadows", config.shadows.coerceIn(-1f, 1f))
                shader.setFloatUniform("uHighlights", config.highlights.coerceIn(-1f, 1f))
                shader.setFloatUniform("uVibrance", config.vibrance.coerceIn(-1f, 1f))
                shader.setFloatUniform(
                    "uVibrantGreenIntensity",
                    getVibrantGreenLutIntensity(config, includeCreativeLut)
                )

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
        uniform float3 uMatRow0;
        uniform float3 uMatRow1;
        uniform float3 uMatRow2;
        uniform float3 uMatOffset;
        uniform float uShadows;
        uniform float uHighlights;
        uniform float uVibrance;
        uniform float uVibrantGreenIntensity;

        half4 main(float2 fragCoord) {
            half4 src = inputShader.eval(fragCoord);
            float3 c = float3(src.r, src.g, src.b);

            // 1. Base 4x5 Cinema ColorMatrix
            float3 graded;
            graded.r = dot(uMatRow0, c) + uMatOffset.r;
            graded.g = dot(uMatRow1, c) + uMatOffset.g;
            graded.b = dot(uMatRow2, c) + uMatOffset.b;
            c = clamp(graded, 0.0, 1.0);

            // 2. Independent Shadows & Highlights Tonal Sculpting
            if (abs(uShadows) > 0.001 || abs(uHighlights) > 0.001) {
                float luma = dot(c, float3(0.2126, 0.7152, 0.0722));
                float shadowMask = 1.0 - smoothstep(0.0, 0.65, luma);
                float shadowDelta = uShadows * 0.22 * shadowMask * (1.0 - luma);
                float highlightMask = smoothstep(0.35, 1.0, luma);
                float highlightDelta = uHighlights * 0.22 * highlightMask * luma;
                c = clamp(c + (shadowDelta + highlightDelta), 0.0, 1.0);
            }

            // Skin-tone protection mask (R > G > B with natural warm human skin ratios)
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

            // 3. Independent Vibrance with Skin-Tone Protection
            if (abs(uVibrance) > 0.001) {
                float maxC = max(c.r, max(c.g, c.b));
                float minC = min(c.r, min(c.g, c.b));
                float sat = maxC - minC;
                float luma = dot(c, float3(0.2126, 0.7152, 0.0722));
                float skinAtten = (uVibrance > 0.0) ? (1.0 - 0.85 * skinWeight) : 1.0;
                float satWeight = (uVibrance > 0.0) ? clamp(1.0 - sat * 0.75, 0.15, 1.0) : 1.0;
                float vibScale = 1.0 + uVibrance * 0.65 * satWeight * skinAtten;
                c = clamp(float3(luma) + (c - float3(luma)) * vibScale, 0.0, 1.0);
            }

            // 4. Selective Vibrant Green / Punchy Green LUT
            if (uVibrantGreenIntensity > 0.001) {
                float luma = dot(c, float3(0.2126, 0.7152, 0.0722));
                float greenDomR = smoothstep(-0.035, 0.075, c.g - c.r);
                float greenDomB = smoothstep(0.015, 0.12, c.g - c.b);
                float greenWeight = clamp(greenDomR * greenDomB * (1.0 - skinWeight), 0.0, 1.0);

                if (greenWeight > 0.001) {
                    float gw = greenWeight * uVibrantGreenIntensity;
                    float chromaScale = 1.0 + 0.72 * gw;
                    float3 gc = float3(luma) + (c - float3(luma)) * chromaScale;
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

            return half4(half(c.r), half(c.g), half(c.b), src.a);
        }
    """.trimIndent()
}
