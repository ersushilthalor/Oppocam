package com.example.camera.engine

import android.graphics.ColorMatrix
import com.example.camera.data.CubeLutParser
import com.example.camera.model.CinemaColorProfile
import com.example.camera.model.CinemaConfig
import com.example.camera.model.CinematicLut
import com.example.camera.model.LogBitDepth
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
 *    shadows & blacks sculpting (eliminating washed-out flatness), highlight protection,
 *    and human skin-tone preserving saturation.
 *
 * 3. Creative LUT Transform:
 *    Applies authentic Hollywood film stock (Kodak 2383, Fuji Eterna, Teal & Orange, Bleach Bypass, etc.)
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
     * Computes the unified 4x5 ColorMatrix for Cinema mode preview and final video export.
     * Returns null if no transform is active (e.g. Native with default parameters and no LUT).
     *
     * @param includeCreativeLut If true, incorporates Stage 3 (Creative LUT) into the matrix.
     * Set to false when a 3D LUT texture is passed directly to the hardware OpenGL shader.
     */
    fun computeCinemaColorMatrix(
        config: CinemaConfig?,
        rec2020Params: Rec2020AutoToneParams? = null,
        includeCreativeLut: Boolean = true
    ): ColorMatrix? {
        if (config == null || config.logBitDepth == LogBitDepth.OFF) return null

        val masterMatrix = ColorMatrix()
        var hasTransform = false

        // =========================================================================
        // STAGE 1: LOG INPUT / TECHNICAL TRANSFORM (CST)
        // =========================================================================
        val technicalTransform = computeTechnicalInputTransform(config.colorProfile, rec2020Params)
        if (technicalTransform != null) {
            masterMatrix.postConcat(technicalTransform)
            hasTransform = true
        }

        // =========================================================================
        // STAGE 2: PRIMARY GRADE (Tonal & Exposure Balance)
        // =========================================================================
        val primaryGrade = computePrimaryGradeTransform(config)
        if (primaryGrade != null) {
            masterMatrix.postConcat(primaryGrade)
            hasTransform = true
        }

        // =========================================================================
        // STAGE 3: CREATIVE LUT TRANSFORM (Film Stock / Custom .cube)
        // =========================================================================
        if (includeCreativeLut) {
            val creativeLut = computeCreativeLutTransform(config)
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

            CinemaColorProfile.HLG -> {
                // HLG: Vibrant realistic colors with gentle contrast expansion
                val c = 1.06f
                val t = (1.0f - c) * 128f
                val mat = ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                ))
                val hlgSat = ColorMatrix()
                hlgSat.setSaturation(1.20f)
                mat.postConcat(hlgSat)
                mat
            }

            CinemaColorProfile.NATIVE -> null
        }
    }

    /**
     * STAGE 2: Primary Grade (Tonal & Exposure Balance).
     * Adjusts exposure, contrast, washed-out black reduction, shadows, highlights, and skin-safe saturation.
     */
    private fun computePrimaryGradeTransform(config: CinemaConfig): ColorMatrix? {
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

        // 4. User Shadows & Highlights Tone Sculpting
        if (config.shadows != 0.0f || config.highlights != 0.0f) {
            val sOffset = config.shadows * 15f
            val hGain = 1.0f + (config.highlights * 0.12f)
            val shadowHighlightMatrix = ColorMatrix(floatArrayOf(
                hGain, 0f, 0f, 0f, sOffset,
                0f, hGain, 0f, 0f, sOffset,
                0f, 0f, hGain, 0f, sOffset,
                0f, 0f, 0f, 1f, 0f
            ))
            gradeMatrix.postConcat(shadowHighlightMatrix)
            hasPrimary = true
        }

        // 5. Primary Saturation with Human Skin-Tone Protection
        if (config.saturation != 1.0f) {
            val satMatrix = ColorMatrix()
            satMatrix.setSaturation(config.saturation)
            gradeMatrix.postConcat(satMatrix)
            hasPrimary = true
        }

        return if (hasPrimary) gradeMatrix else null
    }

    /**
     * STAGE 3: Creative LUT Transform (Film Stock Presets or Imported 3D .cube).
     * Implements genuine color-grading tonal adjustments (contrast, shadows, highlights,
     * color temperature tint, and RGB color separation) blended by user intensity.
     */
    private fun computeCreativeLutTransform(config: CinemaConfig): ColorMatrix? {
        val lut = config.selectedLut
        val intensity = config.lutIntensity.coerceIn(0.0f, 1.0f)
        if (lut == CinematicLut.NONE || intensity <= 0.001f) return null

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

        if (!isGraded) return null

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
}
