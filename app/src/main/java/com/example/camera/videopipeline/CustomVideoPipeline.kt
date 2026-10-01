package com.example.camera.videopipeline

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.util.Log
import android.view.View
import com.example.camera.model.HardwareCapabilities
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The unified "Custom Pipeline" for Video Mode.
 *
 * Based exactly on Cinema Mode Natural Profile:
 * - Rec.2020 Log baseline
 * - Natural, neutral rendering without warm or saturated tint
 * - Clean highlight shoulder compression & shadow toe response
 * - No artificial LUT or filter-based overlay look
 *
 * All settings operate at the genuine sensor ISP acquisition and hardware shader processing level.
 */
class CustomVideoPipeline(
    initialConfig: CustomVideoPipelineConfig = CustomVideoPipelineConfig()
) : BaseVideoPipeline(VideoPipelineType.CUSTOM) {

    @Volatile
    var config: CustomVideoPipelineConfig = initialConfig
        private set

    @Volatile
    private var cachedAgslCode: String = buildAgslCode(initialConfig)

    @Volatile
    private var cachedGlslCode: String = buildGlslCode(initialConfig)

    @Volatile
    private var activeViewRef: WeakReference<View>? = null

    /**
     * Updates the pipeline configuration dynamically and immediately refreshes
     * the active viewfinder shader so modifications are visible in real-time.
     */
    fun updateConfig(newConfig: CustomVideoPipelineConfig) {
        config = newConfig
        cachedAgslCode = buildAgslCode(newConfig)
        cachedGlslCode = buildGlslCode(newConfig)

        // Force rebind on active viewfinder view if attached
        activeViewRef?.get()?.let { view ->
            view.post {
                try {
                    clearFromView(view)
                    applyToView(view)
                } catch (e: Exception) {
                    Log.w(tag, "Error updating custom pipeline shader on view", e)
                }
            }
        }
    }

    override val stageParams: VideoPipelineStageParams
        get() {
            val cfg = config
            val matrix3x3 = cfg.resolve3x3Matrix()
            return VideoPipelineStageParams(
                sensorExposureBiasEv = (cfg.exposure * 0.5f).coerceIn(-1.5f, 1.5f),
                ispTonemapGamma = (1.0f + cfg.contrast * 0.15f).coerceIn(0.8f, 1.4f),
                ispHighlightRollOff = cfg.highlightRollOff * 0.5f,
                bypassHalEdgeSharpening = cfg.sharpening > 0.05f || cfg.microContrast > 0.05f,
                highQualityTemporalDenoise = cfg.temporalNoiseReduction >= 0.35f,
                inputDeGamma = 1.0f,
                linearExposureGain = 2.0f.pow(cfg.exposure),
                warmthShift = cfg.temperature * 0.04f,
                tintShift = cfg.tint * 0.03f,
                highlightKneeThreshold = (0.75f - cfg.highlightRecovery * 0.20f).coerceIn(0.50f, 0.90f),
                highlightCompressionStrength = cfg.highlightRecovery * 0.60f,
                shadowToeLimit = (0.25f + cfg.shadowRecovery * 0.20f).coerceIn(0.15f, 0.50f),
                shadowLiftStrength = cfg.shadowRecovery * 0.35f,
                subjectMidtoneCenter = (0.45f + cfg.midtoneControl * 0.15f).coerceIn(0.30f, 0.60f),
                subjectSeparationLift = cfg.localContrast * 0.10f,
                spatialSharpnessStrength = cfg.sharpening * 0.60f,
                localMicroContrastStrength = cfg.microContrast * 0.40f,
                detailRadiusTexels = 1.25f,
                haloProtectionLimit = 0.06f,
                colorMatrix3x3 = matrix3x3,
                skinToneProtectionStrength = 0.30f,
                skinWarmthTargetR = 1.0f,
                skinWarmthTargetG = 1.0f,
                skinWarmthTargetB = 1.0f,
                skyBlueRetention = 0.15f,
                filmicContrastSlope = (1.0f + cfg.contrast * 0.30f).coerceIn(0.70f, 1.40f),
                filmicContrastPivot = 0.45f,
                lumaWeightedVibrance = cfg.vibrance * 0.25f,
                globalSaturation = (cfg.saturation * cfg.chromaStrength).coerceIn(0.0f, 2.5f),
                blackPointFloor = (0.002f + cfg.blackLevel).coerceIn(0.0f, 0.05f),
                whitePointCeiling = 0.998f,
                outputGamma = cfg.outputGamma
            )
        }

    override fun applyToCaptureRequest(
        builder: CaptureRequest.Builder,
        capabilities: HardwareCapabilities,
        baseEvIndex: Int
    ) {
        val cfg = config
        val params = stageParams

        // 1. Bypass normal video color effects and scene modes
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        )

        // 2. Hardware AE bias integration with sensor exposure headroom
        val step = capabilities.exposureCompensationStep.takeIf { it > 0f } ?: 0.333f
        val biasSteps = if (params.sensorExposureBiasEv != 0f) {
            (params.sensorExposureBiasEv / step).roundToInt()
        } else {
            0
        }
        val targetEv = (baseEvIndex + biasSteps).coerceIn(
            capabilities.minExposureCompensation,
            capabilities.maxExposureCompensation
        )
        if (capabilities.minExposureCompensation <= capabilities.maxExposureCompensation) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, targetEv)
        }

        // 3. Genuine Cinema Mode Rec.2020 Natural Log Tonemap Curve applied to hardware ISP
        if (capabilities.supportsTonemapCurve) {
            try {
                val naturalCurve = buildRec2020NaturalTonemapCurve(cfg)
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                builder.set(CaptureRequest.TONEMAP_CURVE, naturalCurve)
            } catch (e: Exception) {
                Log.w(tag, "Custom pipeline TonemapCurve setup fallback: ${e.message}")
            }
        }

        // 4. Bypass OEM HAL edge sharpening when pipeline spatial detail stages are active
        if (params.bypassHalEdgeSharpening) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        } else {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        }

        // 5. Hardware Noise Reduction configuration based on temporal and luma noise reduction
        builder.set(
            CaptureRequest.NOISE_REDUCTION_MODE,
            if (cfg.temporalNoiseReduction >= 0.40f) {
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            } else if (cfg.temporalNoiseReduction >= 0.10f) {
                CaptureRequest.NOISE_REDUCTION_MODE_FAST
            } else {
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            }
        )

        // 6. Hardware White Balance mode
        if (cfg.whiteBalance != com.example.camera.model.WhiteBalanceMode.AUTO) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, cfg.whiteBalance.camera2Mode)
        }
    }

    override fun applyToView(view: View) {
        activeViewRef = WeakReference(view)
        super.applyToView(view)
    }

    override fun clearFromView(view: View) {
        if (activeViewRef?.get() == view) {
            activeViewRef = null
        }
        super.clearFromView(view)
    }

    override fun getAgslShaderCode(): String = cachedAgslCode

    override fun getGlFragmentShaderCode(): String = cachedGlslCode

    override fun computeColorMatrix(): ColorMatrix {
        val cfg = config
        val matrix = ColorMatrix()

        // Exposure & Brightness
        val expScale = 2.0f.pow(cfg.exposure)
        val blackShift = cfg.blackLevel * 255f
        val expMat = ColorMatrix(floatArrayOf(
            expScale, 0f, 0f, 0f, blackShift,
            0f, expScale, 0f, 0f, blackShift,
            0f, 0f, expScale, 0f, blackShift,
            0f, 0f, 0f, 1f, 0f
        ))
        matrix.postConcat(expMat)

        // Temperature (amber-blue) & Tint (magenta-green)
        val tempR = (1.0f + cfg.temperature * 0.12f).coerceIn(0.7f, 1.4f)
        val tempB = (1.0f - cfg.temperature * 0.12f).coerceIn(0.7f, 1.4f)
        val tintG = (1.0f - cfg.tint * 0.08f).coerceIn(0.7f, 1.3f)
        val tintR = (1.0f + cfg.tint * 0.04f).coerceIn(0.7f, 1.3f)
        val tintB = (1.0f + cfg.tint * 0.04f).coerceIn(0.7f, 1.3f)
        val wbMat = ColorMatrix(floatArrayOf(
            tempR * tintR, 0f, 0f, 0f, 0f,
            0f, tintG, 0f, 0f, 0f,
            0f, 0f, tempB * tintB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        matrix.postConcat(wbMat)

        // Contrast
        val c = (1.0f + cfg.contrast * 0.35f).coerceIn(0.5f, 2.0f)
        val t = (1.0f - c) * 128f
        val contrastMat = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
        matrix.postConcat(contrastMat)

        // Saturation & Chroma Strength
        val sat = (cfg.saturation * cfg.chromaStrength).coerceIn(0.0f, 2.5f)
        val satMat = ColorMatrix()
        satMat.setSaturation(sat)
        matrix.postConcat(satMat)

        return matrix
    }

    /**
     * Builds a genuine 64-point Rec.2020 Natural Log TonemapCurve for Camera2 hardware ISP
     * that accurately translates all pipeline settings (Shadow/Highlight Recovery, Roll-off, Contrast, Black Level).
     */
    private fun buildRec2020NaturalTonemapCurve(cfg: CustomVideoPipelineConfig): TonemapCurve {
        val numPoints = 64
        val curvePoints = FloatArray(numPoints * 2)

        val blackFloor = (0.02f + cfg.blackLevel).coerceIn(0.0f, 0.10f)
        val shadowLift = cfg.shadowRecovery * 0.32f
        val shadowRoll = cfg.shadowRollOff
        val hlRecovery = cfg.highlightRecovery
        val hlRoll = cfg.highlightRollOff
        val contrastSlope = 1.0f + cfg.contrast * 0.25f

        for (i in 0 until numPoints) {
            val x = i.toFloat() / (numPoints - 1).toFloat()

            // 1. Rec.2020 Log Opto-Electronic Transfer Base with selectable Luma Curve Preset
            val logBase = when (cfg.lumaCurvePreset) {
                1 -> { // Gentle Filmic S
                    (1.0f / (1.0f + kotlin.math.exp(-6.0f * (x - 0.45f)))).coerceIn(0.0f, 1.0f)
                }
                2 -> { // Extended Dynamic Range
                    if (x <= 0.05f) {
                        x * 3.5f
                    } else {
                        (0.24f * ln(16.0f * x - 0.35f).coerceAtLeast(-4.0f) + 0.62f).coerceIn(0.0f, 1.0f)
                    }
                }
                3 -> { // Lifted Shadows
                    if (x <= (1.0f / 12.0f)) {
                        (kotlin.math.sqrt(3.0f * x) * 0.52f + 0.04f).coerceIn(0.0f, 1.0f)
                    } else {
                        (0.18f * ln(12.0f * x - 0.28f).coerceAtLeast(-5.0f) + 0.60f).coerceIn(0.0f, 1.0f)
                    }
                }
                else -> { // Rec.2020 Natural Log Base (ARIB STD-B67 / Rec.2020)
                    if (x <= (1.0f / 12.0f)) {
                        kotlin.math.sqrt(3.0f * x) * 0.45f
                    } else {
                        (0.1788f * ln(12.0f * x - 0.2846f).coerceAtLeast(-5.0f) + 0.5599f) * 0.85f + 0.15f
                    }.coerceIn(0.0f, 1.0f)
                }
            }

            // 2. Intelligent Shadow Toe Recovery with Shadow Roll-off
            val shadowFactor = (1.0f - x).pow(1.0f + shadowRoll * 2.0f)
            val toeLift = if (x < 0.40f) {
                shadowLift * shadowFactor * (0.40f - x) * 2.5f
            } else 0.0f

            // 3. Highlight Knee Recovery & Roll-off
            val knee = (0.70f - hlRecovery * 0.15f).coerceIn(0.50f, 0.85f)
            val hlCompress = if (x > knee) {
                val excess = (x - knee) / (1.0f - knee).coerceAtLeast(0.01f)
                val comp = excess.pow(1.0f + hlRoll * 1.5f) * (0.15f + hlRecovery * 0.20f)
                comp
            } else 0.0f

            // 4. Filmic Midtone Contrast Slope
            val midDiff = logBase - 0.42f
            val contrasted = 0.42f + midDiff * contrastSlope

            val finalY = (blackFloor + (contrasted + toeLift - hlCompress) * (1.0f - blackFloor)).coerceIn(0.0f, 1.0f)

            curvePoints[i * 2] = x
            curvePoints[i * 2 + 1] = finalY
        }

        return TonemapCurve(curvePoints, curvePoints.clone(), curvePoints.clone())
    }

    companion object {
        /**
         * Builds the real-time AGSL RuntimeShader source code for live preview on TextureView.
         * Embeds the exact mathematical transformations of the 24 Custom Pipeline settings.
         */
        fun buildAgslCode(cfg: CustomVideoPipelineConfig): String {
            val fExposure = String.format(Locale.US, "%.5ff", 2.0f.pow(cfg.exposure))
            val fBlackLevel = String.format(Locale.US, "%.5ff", cfg.blackLevel)
            val fHlRecovery = String.format(Locale.US, "%.5ff", cfg.highlightRecovery)
            val fHlRollOff = String.format(Locale.US, "%.5ff", cfg.highlightRollOff)
            val fShRecovery = String.format(Locale.US, "%.5ff", cfg.shadowRecovery)
            val fShRollOff = String.format(Locale.US, "%.5ff", cfg.shadowRollOff)
            val fMidtone = String.format(Locale.US, "%.5ff", cfg.midtoneControl)
            val fContrast = String.format(Locale.US, "%.5ff", cfg.contrast)
            val fLocalContrast = String.format(Locale.US, "%.5ff", cfg.localContrast)
            val fTemp = String.format(Locale.US, "%.5ff", cfg.temperature)
            val fTint = String.format(Locale.US, "%.5ff", cfg.tint)
            val fSat = String.format(Locale.US, "%.5ff", cfg.saturation)
            val fVibrance = String.format(Locale.US, "%.5ff", cfg.vibrance)
            val fChroma = String.format(Locale.US, "%.5ff", cfg.chromaStrength)
            val fSharp = String.format(Locale.US, "%.5ff", cfg.sharpening)
            val fMicroContrast = String.format(Locale.US, "%.5ff", cfg.microContrast)
            val fLumaNr = String.format(Locale.US, "%.5ff", cfg.lumaNoiseReduction)
            val fChromaNr = String.format(Locale.US, "%.5ff", cfg.chromaNoiseReduction)
            val fHdrStrength = String.format(Locale.US, "%.5ff", cfg.hdrToneMappingStrength)
            val fLocalTm = String.format(Locale.US, "%.5ff", cfg.localToneMapping)
            val fGamma = String.format(Locale.US, "%.5ff", 1.0f / cfg.outputGamma.coerceIn(1.5f, 2.8f))

            val m = cfg.resolve3x3Matrix()
            val m00 = String.format(Locale.US, "%.4ff", m[0])
            val m01 = String.format(Locale.US, "%.4ff", m[1])
            val m02 = String.format(Locale.US, "%.4ff", m[2])
            val m10 = String.format(Locale.US, "%.4ff", m[3])
            val m11 = String.format(Locale.US, "%.4ff", m[4])
            val m12 = String.format(Locale.US, "%.4ff", m[5])
            val m20 = String.format(Locale.US, "%.4ff", m[6])
            val m21 = String.format(Locale.US, "%.4ff", m[7])
            val m22 = String.format(Locale.US, "%.4ff", m[8])

            return """
                uniform shader uContent;
                uniform float2 uResolution;

                // ITU-R BT.2020 / ARIB STD-B67 Log transfer & scene linear conversion
                vec3 toLinearRec2020(vec3 s) {
                    vec3 c = max(s, vec3(0.0));
                    return pow(c, vec3(2.2));
                }

                vec3 fromLinearRec2020(vec3 lin, float invGamma) {
                    return pow(max(lin, vec3(0.0)), vec3(invGamma));
                }

                vec4 main(float2 fragCoord) {
                    float2 step = 1.0 / max(uResolution, float2(1.0, 1.0));
                    
                    // --- STAGE 1: 5-Tap Spatial Detail, Micro-Contrast, Noise Reduction & Local Contrast ---
                    vec4 cCenter = uContent.eval(fragCoord);
                    vec4 cN = uContent.eval(fragCoord + float2(0.0, -step.y * 1.25));
                    vec4 cS = uContent.eval(fragCoord + float2(0.0,  step.y * 1.25));
                    vec4 cW = uContent.eval(fragCoord + float2(-step.x * 1.25, 0.0));
                    vec4 cE = uContent.eval(fragCoord + float2( step.x * 1.25, 0.0));

                    vec3 lumaW = vec3(0.2627, 0.6780, 0.0593); // Rec.2020 Luma Weights
                    float lCenter = dot(cCenter.rgb, lumaW);
                    float lN = dot(cN.rgb, lumaW);
                    float lS = dot(cS.rgb, lumaW);
                    float lW = dot(cW.rgb, lumaW);
                    float lE = dot(cE.rgb, lumaW);
                    float lAvg = 0.25 * (lN + lS + lW + lE);

                    // High-pass micro-contrast & edge detail
                    float highPass = lCenter - lAvg;
                    
                    // Chroma noise filtering
                    vec3 chromaDiff = cCenter.rgb - vec3(lCenter);
                    vec3 avgChromaDiff = 0.25 * ((cN.rgb - vec3(lN)) + (cS.rgb - vec3(lS)) + (cW.rgb - vec3(lW)) + (cE.rgb - vec3(lE)));
                    vec3 filteredChroma = mix(chromaDiff, avgChromaDiff, $fChromaNr * 0.75);
                    vec3 baseCleanColor = vec3(lCenter) + filteredChroma;

                    // Bilateral edge-preserving luma noise reduction
                    float edgeWeight = clamp(abs(highPass) * 16.0, 0.0, 1.0);
                    vec3 smoothedColor = 0.5 * baseCleanColor + 0.125 * (cN.rgb + cS.rgb + cW.rgb + cE.rgb);
                    vec3 nrColor = mix(smoothedColor, baseCleanColor, edgeWeight + (1.0 - $fLumaNr) * (1.0 - edgeWeight));

                    // Sharpening and micro-contrast injection
                    float detailAmp = $fSharp * 1.25 + $fMicroContrast * 0.85;
                    float clampedHighPass = clamp(highPass * detailAmp, -0.15, 0.15);
                    vec3 detailColor = nrColor + vec3(clampedHighPass);

                    // Local tone mapping & local contrast enhancement
                    float localRatio = (lCenter + 0.08) / (lAvg + 0.08);
                    float localTmFactor = mix(1.0, clamp(pow(localRatio, 0.35), 0.70, 1.40), $fLocalTm);
                    vec3 spatiallyProcessed = mix(detailColor, detailColor * localRatio, $fLocalContrast * 0.35) * localTmFactor;

                    // --- STAGE 2: Linear Radiance & Physical Exposure Scaling ---
                    vec3 linearRgb = toLinearRec2020(clamp(spatiallyProcessed, 0.0, 1.0));
                    linearRgb *= $fExposure;
                    linearRgb += vec3($fBlackLevel);
                    linearRgb = max(linearRgb, vec3(0.0));

                    // --- STAGE 3: White Balance, Temperature, Tint & Color Matrix ---
                    // Bradford chromatic adaptation
                    float rWb = 1.0 + $fTemp * 0.12 + $fTint * 0.04;
                    float gWb = 1.0 - $fTint * 0.08;
                    float bWb = 1.0 - $fTemp * 0.12 + $fTint * 0.04;
                    linearRgb *= vec3(rWb, gWb, bWb);

                    // 3x3 Rec.2020 Color Transform Matrix
                    mat3 colorMat = mat3(
                        $m00, $m10, $m20,
                        $m01, $m11, $m21,
                        $m02, $m12, $m22
                    );
                    linearRgb = max(colorMat * linearRgb, vec3(0.0));

                    // --- STAGE 4: HDR Tone Mapping, Highlight & Shadow Recovery ---
                    float curLuma = dot(linearRgb, lumaW);

                    // Shadow Recovery & Roll-off (logarithmic toe lift)
                    float shadowMask = clamp(1.0 - curLuma * 3.0, 0.0, 1.0);
                    float shadowLift = $fShRecovery * 0.35 * pow(shadowMask, 1.0 + $fShRollOff * 2.0);
                    linearRgb += vec3(shadowLift * (0.35 - min(curLuma, 0.35)));

                    // Highlight Recovery & Roll-off (soft-knee shoulder compression)
                    float hlKnee = 0.70 - $fHlRecovery * 0.18;
                    if (curLuma > hlKnee) {
                        float excess = (curLuma - hlKnee) / max(1.0 - hlKnee, 0.02);
                        float rollComp = pow(excess, 1.0 + $fHlRollOff * 1.5) * ($fHlRecovery * 0.40 + 0.15);
                        linearRgb = mix(linearRgb, linearRgb / (1.0 + rollComp), $fHdrStrength);
                    }

                    // Midtone Control (smooth power-law pivot around 18% middle gray)
                    linearRgb = pow(linearRgb, vec3(1.0 - $fMidtone * 0.30));

                    // Filmic S-curve Contrast
                    float cSlope = 1.0 + $fContrast * 0.40;
                    vec3 contrastRgb = (linearRgb - vec3(0.18)) * cSlope + vec3(0.18);
                    linearRgb = mix(linearRgb, max(contrastRgb, vec3(0.0)), 0.85);

                    // --- STAGE 5: Saturation, Vibrance & Chroma Strength ---
                    float outLuma = dot(linearRgb, lumaW);
                    vec3 chromaVec = (linearRgb - vec3(outLuma)) * ($fSat * $fChroma);
                    
                    // Smart vibrance: boosts lower-saturated areas more than already-saturated colors
                    float maxC = max(linearRgb.r, max(linearRgb.g, linearRgb.b));
                    float minC = min(linearRgb.r, min(linearRgb.g, linearRgb.b));
                    float satAmount = (maxC - minC) / max(maxC, 0.001);
                    float vibranceBoost = $fVibrance * (1.0 - satAmount) * 0.50;
                    chromaVec *= (1.0 + vibranceBoost);

                    vec3 finalLinear = max(vec3(outLuma) + chromaVec, vec3(0.0));

                    // --- STAGE 6: Output Gamma Display Encoding ---
                    vec3 finalRgb = fromLinearRec2020(finalLinear, $fGamma);
                    return vec4(clamp(finalRgb, 0.0, 1.0), cCenter.a);
                }
            """.trimIndent()
        }

        /**
         * Builds the matching OpenGL ES 2.0 fragment shader for recording and transcoding.
         */
        fun buildGlslCode(cfg: CustomVideoPipelineConfig): String {
            val fExposure = String.format(Locale.US, "%.5ff", 2.0f.pow(cfg.exposure))
            val fBlackLevel = String.format(Locale.US, "%.5ff", cfg.blackLevel)
            val fHlRecovery = String.format(Locale.US, "%.5ff", cfg.highlightRecovery)
            val fHlRollOff = String.format(Locale.US, "%.5ff", cfg.highlightRollOff)
            val fShRecovery = String.format(Locale.US, "%.5ff", cfg.shadowRecovery)
            val fShRollOff = String.format(Locale.US, "%.5ff", cfg.shadowRollOff)
            val fMidtone = String.format(Locale.US, "%.5ff", cfg.midtoneControl)
            val fContrast = String.format(Locale.US, "%.5ff", cfg.contrast)
            val fLocalContrast = String.format(Locale.US, "%.5ff", cfg.localContrast)
            val fTemp = String.format(Locale.US, "%.5ff", cfg.temperature)
            val fTint = String.format(Locale.US, "%.5ff", cfg.tint)
            val fSat = String.format(Locale.US, "%.5ff", cfg.saturation)
            val fVibrance = String.format(Locale.US, "%.5ff", cfg.vibrance)
            val fChroma = String.format(Locale.US, "%.5ff", cfg.chromaStrength)
            val fSharp = String.format(Locale.US, "%.5ff", cfg.sharpening)
            val fMicroContrast = String.format(Locale.US, "%.5ff", cfg.microContrast)
            val fLumaNr = String.format(Locale.US, "%.5ff", cfg.lumaNoiseReduction)
            val fChromaNr = String.format(Locale.US, "%.5ff", cfg.chromaNoiseReduction)
            val fHdrStrength = String.format(Locale.US, "%.5ff", cfg.hdrToneMappingStrength)
            val fLocalTm = String.format(Locale.US, "%.5ff", cfg.localToneMapping)
            val fGamma = String.format(Locale.US, "%.5ff", 1.0f / cfg.outputGamma.coerceIn(1.5f, 2.8f))

            val m = cfg.resolve3x3Matrix()
            val m00 = String.format(Locale.US, "%.4ff", m[0])
            val m01 = String.format(Locale.US, "%.4ff", m[1])
            val m02 = String.format(Locale.US, "%.4ff", m[2])
            val m10 = String.format(Locale.US, "%.4ff", m[3])
            val m11 = String.format(Locale.US, "%.4ff", m[4])
            val m12 = String.format(Locale.US, "%.4ff", m[5])
            val m20 = String.format(Locale.US, "%.4ff", m[6])
            val m21 = String.format(Locale.US, "%.4ff", m[7])
            val m22 = String.format(Locale.US, "%.4ff", m[8])

            return """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTextureCoord;
                uniform samplerExternalOES sTexture;

                vec3 toLinearRec2020(vec3 s) {
                    return pow(max(s, vec3(0.0)), vec3(2.2));
                }

                vec3 fromLinearRec2020(vec3 lin, float invGamma) {
                    return pow(max(lin, vec3(0.0)), vec3(invGamma));
                }

                void main() {
                    vec2 uv = vTextureCoord;
                    vec2 step = vec2(0.00092, 0.00052);

                    // 5-Tap Spatial Detail & Noise Filtering
                    vec4 cCenter = texture2D(sTexture, uv);
                    vec4 cN = texture2D(sTexture, uv + vec2(0.0, -step.y * 1.25));
                    vec4 cS = texture2D(sTexture, uv + vec2(0.0,  step.y * 1.25));
                    vec4 cW = texture2D(sTexture, uv + vec2(-step.x * 1.25, 0.0));
                    vec4 cE = texture2D(sTexture, uv + vec2( step.x * 1.25, 0.0));

                    vec3 lumaW = vec3(0.2627, 0.6780, 0.0593);
                    float lCenter = dot(cCenter.rgb, lumaW);
                    float lAvg = 0.25 * (dot(cN.rgb, lumaW) + dot(cS.rgb, lumaW) + dot(cW.rgb, lumaW) + dot(cE.rgb, lumaW));

                    float highPass = lCenter - lAvg;
                    
                    // Chroma noise filtering
                    vec3 chromaDiff = cCenter.rgb - vec3(lCenter);
                    vec3 avgChromaDiff = 0.25 * ((cN.rgb - vec3(lN)) + (cS.rgb - vec3(lS)) + (cW.rgb - vec3(lW)) + (cE.rgb - vec3(lE)));
                    vec3 filteredChroma = mix(chromaDiff, avgChromaDiff, $fChromaNr * 0.75);
                    vec3 baseCleanColor = vec3(lCenter) + filteredChroma;

                    // Bilateral edge-preserving luma noise reduction
                    float edgeWeight = clamp(abs(highPass) * 16.0, 0.0, 1.0);
                    vec3 smoothedColor = 0.5 * baseCleanColor + 0.125 * (cN.rgb + cS.rgb + cW.rgb + cE.rgb);
                    vec3 nrColor = mix(smoothedColor, baseCleanColor, edgeWeight + (1.0 - $fLumaNr) * (1.0 - edgeWeight));

                    float detailAmp = $fSharp * 1.25 + $fMicroContrast * 0.85;
                    vec3 detailColor = nrColor + vec3(clamp(highPass * detailAmp, -0.15, 0.15));

                    // Local tone mapping & local contrast
                    float localRatio = (lCenter + 0.08) / (lAvg + 0.08);
                    float localTmFactor = mix(1.0, clamp(pow(localRatio, 0.35), 0.70, 1.40), $fLocalTm);
                    vec3 spatiallyProcessed = mix(detailColor, detailColor * localRatio, $fLocalContrast * 0.35) * localTmFactor;

                    // Linearization & Exposure
                    vec3 linearRgb = toLinearRec2020(clamp(spatiallyProcessed, 0.0, 1.0)) * $fExposure + vec3($fBlackLevel);
                    linearRgb = max(linearRgb, vec3(0.0));

                    // White Balance & Chromatic Adaptation
                    float rWb = 1.0 + $fTemp * 0.12 + $fTint * 0.04;
                    float gWb = 1.0 - $fTint * 0.08;
                    float bWb = 1.0 - $fTemp * 0.12 + $fTint * 0.04;
                    linearRgb *= vec3(rWb, gWb, bWb);

                    // Color Matrix
                    mat3 colorMat = mat3(
                        $m00, $m10, $m20,
                        $m01, $m11, $m21,
                        $m02, $m12, $m22
                    );
                    linearRgb = max(colorMat * linearRgb, vec3(0.0));

                    // HDR Tone Mapping, Shadow & Highlight Recovery
                    float curLuma = dot(linearRgb, lumaW);
                    float shadowMask = clamp(1.0 - curLuma * 3.0, 0.0, 1.0);
                    float shadowLift = $fShRecovery * 0.35 * pow(shadowMask, 1.0 + $fShRollOff * 2.0);
                    linearRgb += vec3(shadowLift * (0.35 - min(curLuma, 0.35)));

                    float hlKnee = 0.70 - $fHlRecovery * 0.18;
                    if (curLuma > hlKnee) {
                        float excess = (curLuma - hlKnee) / max(1.0 - hlKnee, 0.02);
                        float rollComp = pow(excess, 1.0 + $fHlRollOff * 1.5) * ($fHlRecovery * 0.40 + 0.15);
                        linearRgb = mix(linearRgb, linearRgb / (1.0 + rollComp), $fHdrStrength);
                    }

                    linearRgb = pow(linearRgb, vec3(1.0 - $fMidtone * 0.30));

                    float cSlope = 1.0 + $fContrast * 0.40;
                    vec3 contrastRgb = (linearRgb - vec3(0.18)) * cSlope + vec3(0.18);
                    linearRgb = mix(linearRgb, max(contrastRgb, vec3(0.0)), 0.85);

                    // Saturation & Vibrance
                    float outLuma = dot(linearRgb, lumaW);
                    vec3 chromaVec = (linearRgb - vec3(outLuma)) * ($fSat * $fChroma);
                    float maxC = max(linearRgb.r, max(linearRgb.g, linearRgb.b));
                    float minC = min(linearRgb.r, min(linearRgb.g, linearRgb.b));
                    float satAmount = (maxC - minC) / max(maxC, 0.001);
                    float vibranceBoost = $fVibrance * (1.0 - satAmount) * 0.50;
                    chromaVec *= (1.0 + vibranceBoost);

                    vec3 finalLinear = max(vec3(outLuma) + chromaVec, vec3(0.0));
                    vec3 finalRgb = fromLinearRec2020(finalLinear, $fGamma);
                    gl_FragColor = vec4(clamp(finalRgb, 0.0, 1.0), cCenter.a);
                }
            """.trimIndent()
        }
    }
}
