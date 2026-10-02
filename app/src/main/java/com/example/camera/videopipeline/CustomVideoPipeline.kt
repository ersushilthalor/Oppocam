package com.example.camera.videopipeline

import android.graphics.ColorMatrix
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The unified "Custom Pipeline" for Video Mode.
 *
 * Base: Exact Cinema Mode Natural Profile:
 * - Rec.2020 Log baseline
 * - Natural, neutral rendering without excessive warm/saturated tint
 * - Clean tone mapping matching Cinema Mode
 * - Same highlight and shadow response
 *
 * Every control is completely independent and operates at the genuine image processing level:
 * - Exposure / Brightness
 * - Highlight Recovery
 * - Shadow Recovery
 * - Black Level
 * - Midtone Control
 * - Contrast
 * - Local Contrast
 * - Highlight Roll-off
 * - Shadow Roll-off
 * - White Balance
 *
 * Changing one control does NOT automatically change or override other controls.
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
        invalidateShader()

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
                sensorExposureBiasEv = 0.0f,
                ispTonemapGamma = 1.0f,
                ispHighlightRollOff = cfg.highlightRollOff * 0.5f,
                bypassHalEdgeSharpening = cfg.sharpening > 0.05f || cfg.microContrast > 0.05f || cfg.textureDetail > 0.05f,
                highQualityTemporalDenoise = cfg.temporalNoiseReduction >= 0.35f,
                inputDeGamma = 1.0f,
                linearExposureGain = 2.0f.pow(cfg.exposure),
                warmthShift = cfg.temperature * 0.04f,
                tintShift = cfg.tint * 0.03f,
                highlightKneeThreshold = (0.70f - cfg.highlightRecovery * 0.15f).coerceIn(0.50f, 0.85f),
                highlightCompressionStrength = cfg.highlightRecovery * 0.50f,
                shadowToeLimit = 0.38f,
                shadowLiftStrength = cfg.shadowRecovery * 0.25f,
                subjectMidtoneCenter = 0.46f,
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
                filmicContrastSlope = (1.0f + cfg.contrast * 0.38f).coerceIn(0.60f, 1.50f),
                filmicContrastPivot = 0.46f,
                lumaWeightedVibrance = cfg.vibrance * 0.25f,
                globalSaturation = (1.14f * cfg.saturation * cfg.chromaStrength).coerceIn(0.0f, 2.5f),
                blackPointFloor = cfg.blackLevel.coerceIn(-0.05f, 0.05f),
                whitePointCeiling = 1.0f,
                outputGamma = cfg.outputGamma
            )
        }

    override fun applyToCaptureRequest(
        builder: CaptureRequest.Builder,
        capabilities: HardwareCapabilities,
        baseEvIndex: Int
    ) {
        val cfg = config

        // 1. Disable OEM color effects & scene modes to ensure pristine raw processing
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        )
        // High quality color correction with factory-calibrated AWB (exact Cinema Mode Rec.2020 behavior)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)

        // 2. Hardware AE compensation index
        if (capabilities.minExposureCompensation <= capabilities.maxExposureCompensation) {
            val targetEv = baseEvIndex.coerceIn(
                capabilities.minExposureCompensation,
                capabilities.maxExposureCompensation
            )
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

        // 4. Edge mode: balance OEM hardware sharpening with pipeline spatial processing
        if (cfg.sharpening > 0.05f || cfg.microContrast > 0.05f || cfg.textureDetail > 0.05f) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        } else {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        }

        // 5. Hardware Noise Reduction based on temporal and spatial settings
        builder.set(
            CaptureRequest.NOISE_REDUCTION_MODE,
            if (cfg.temporalNoiseReduction >= 0.40f || cfg.spatialNoiseReduction >= 0.40f) {
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            } else if (cfg.temporalNoiseReduction >= 0.10f || cfg.spatialNoiseReduction >= 0.10f) {
                CaptureRequest.NOISE_REDUCTION_MODE_FAST
            } else {
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            }
        )

        // 6. Hardware Lens Shading & Distortion Correction
        if (cfg.lensShadingCorrection > 0.10f) {
            builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
        }
        if (cfg.distortionCorrection > 0.10f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
        }

        // 7. Hardware White Balance mode
        if (cfg.whiteBalance != com.example.camera.model.WhiteBalanceMode.AUTO) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, cfg.whiteBalance.camera2Mode)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
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

        // 1. Exposure Scaling (independent)
        val expScale = 2.0f.pow(cfg.exposure * 0.70f)

        // 2. Black Level (independent pedestal)
        val blackShift = cfg.blackLevel * 255f

        // 3. Contrast (independent S-slope around 18% middle gray pivot 117f / 255)
        val contrastFactor = 1.0f + (cfg.contrast * 0.38f)
        val midPivot = 117f
        val contrastOffset = (1.0f - contrastFactor) * midPivot

        // 4. Midtone (independent middle gray shift)
        val midShift = cfg.midtoneControl * 25f

        // 5. Shadow Recovery (independent toe lift)
        val shadowOffset = cfg.shadowRecovery * 12f

        // 6. Highlight Recovery (independent highlight compression)
        val highlightComp = 1.0f - (cfg.highlightRecovery * 0.08f)

        // Combined luminance scale & DC offset matching Cinema Mode Rec.2020 profile
        val lumScale = (expScale * contrastFactor * highlightComp * 0.975f).coerceIn(0.5f, 2.5f)
        val dcOffset = (contrastOffset + blackShift + midShift + shadowOffset - 3.5f).coerceIn(-40f, 40f)

        // 7. White Balance & Gains (independent)
        val tempR = (1.0f + cfg.temperature * 0.15f + cfg.tint * 0.05f) * cfg.redGain
        val tempB = (1.0f - cfg.temperature * 0.15f + cfg.tint * 0.05f) * cfg.blueGain
        val tintG = (1.0f - cfg.tint * 0.10f) * cfg.greenGain

        // 8. Rec.2020 Natural Saturation (1.14x baseline matching Cinema Mode)
        val sat = (1.14f * cfg.saturation * cfg.chromaStrength).coerceIn(0.0f, 2.5f)
        val invSat = 1.0f - sat
        val lr = 0.2627f * invSat
        val lg = 0.6780f * invSat
        val lb = 0.0593f * invSat

        val m00 = (lr + sat) * lumScale * tempR
        val m01 = lg * lumScale * tempR
        val m02 = lb * lumScale * tempR

        val m10 = lr * lumScale * tintG
        val m11 = (lg + sat) * lumScale * tintG
        val m12 = lb * lumScale * tintG

        val m20 = lr * lumScale * tempB
        val m21 = lg * lumScale * tempB
        val m22 = (lb + sat) * lumScale * tempB

        matrix.set(floatArrayOf(
            m00, m01, m02, 0f, dcOffset,
            m10, m11, m12, 0f, dcOffset,
            m20, m21, m22, 0f, dcOffset,
            0f,  0f,  0f,  1f, 0f
        ))
        return matrix
    }

    /**
     * Builds a genuine 64-point Rec.2020 Natural Log TonemapCurve for Camera2 hardware ISP
     * matching the exact Natural Log profile of Cinema Mode, with every control completely independent.
     */
    private fun buildRec2020NaturalTonemapCurve(cfg: CustomVideoPipelineConfig): TonemapCurve {
        val numPoints = 64
        val curvePoints = FloatArray(numPoints * 2)

        val expScale = 2.0f.pow(cfg.exposure * 0.70f)
        val knee = 0.62f
        val highlightStrength = cfg.highlightRecovery.coerceIn(0f, 1f)
        val shadowLift = cfg.shadowRecovery.coerceIn(0f, 1f)
        val shadowRollPower = 1.5f + cfg.shadowRollOff.coerceIn(0f, 1f) * 1.5f
        val hlRollPower = 1.5f + cfg.highlightRollOff.coerceIn(0f, 1f) * 1.8f

        // Helper to evaluate base curve at knee
        val alpha = 1.09929682680944f
        val beta = 0.018053968510807f
        val xKneeShifted = (knee * expScale).coerceIn(0f, 1f)
        var yAtKnee = if (xKneeShifted < beta) 4.5f * xKneeShifted else alpha * xKneeShifted.pow(0.45f) - (alpha - 1.0f)
        if (cfg.contrast != 0.0f) {
            val factor = 1.0f + (cfg.contrast * 0.42f)
            yAtKnee = 0.46f + (yAtKnee - 0.46f) * factor
        }
        yAtKnee = yAtKnee.coerceIn(0.0f, 1.0f)

        for (i in 0 until numPoints) {
            val x = i.toFloat() / (numPoints - 1).toFloat()
            if (x <= 0.0001f) {
                curvePoints[i * 2] = x
                curvePoints[i * 2 + 1] = cfg.blackLevel.coerceIn(0f, 0.1f)
                continue
            }
            if (x >= 0.9999f) {
                curvePoints[i * 2] = x
                curvePoints[i * 2 + 1] = 1.0f
                continue
            }

            val xShifted = (x * expScale).coerceIn(0f, 1f)

            var y = when (cfg.lumaCurvePreset) {
                1 -> { // Gentle Filmic S
                    (1.0f / (1.0f + exp(-6.0f * (xShifted - 0.45f)))).coerceIn(0.0f, 1.0f)
                }
                2 -> { // Extended Dynamic Range
                    if (xShifted < beta) 4.5f * xShifted else alpha * xShifted.pow(0.40f) - (alpha - 1.0f)
                }
                3 -> { // Lifted Shadows
                    if (xShifted < beta) 4.5f * xShifted + 0.04f else alpha * xShifted.pow(0.48f) - (alpha - 1.04f)
                }
                else -> { // Rec.2020 Natural Log Base (exact Cinema Mode Rec.2020 / ARIB STD-B67)
                    if (xShifted < beta) {
                        4.5f * xShifted
                    } else {
                        alpha * xShifted.pow(0.45f) - (alpha - 1.0f)
                    }
                }
            }

            // Inky Black Pedestal & Black Level (strictly at 0, zero milky blacks)
            if (xShifted < 0.06f) {
                val t = 1.0f - (xShifted / 0.06f)
                y *= (1.0f - t * t * 0.22f)
            }
            if (cfg.blackLevel != 0.0f) {
                y += cfg.blackLevel * (1.0f - x)
            }

            // Independent Shadow Detail Recovery (smooth toe lift, zero at x=0 and midtones)
            if (shadowLift > 0.0f && xShifted in 0.001f..0.38f) {
                val v = xShifted / 0.38f
                val toeShape = 4.0f * v * (1.0f - v).pow(shadowRollPower)
                y += shadowLift * 0.16f * toeShape
            }

            // Independent Midtone Control (bell curve centered around 18% middle gray, zero at 0 and 1)
            if (cfg.midtoneControl != 0.0f) {
                val midBell = 4.0f * xShifted * (1.0f - xShifted)
                y += cfg.midtoneControl * 0.12f * midBell
            }

            // Independent Midtone Contrast (centered around 18% middle gray ~0.46)
            if (cfg.contrast != 0.0f) {
                val factor = 1.0f + (cfg.contrast * 0.42f)
                val midPivot = 0.46f
                y = midPivot + (y - midPivot) * factor
            }

            // Independent Highlight Control & Roll-off Shoulder
            if (xShifted > knee && (highlightStrength > 0.0f || cfg.highlightRollOff > 0.0f)) {
                val u = (xShifted - knee) / (1.0f - knee)
                val shoulderY = yAtKnee + (1.0f - yAtKnee) * (1.0f - (1.0f - u).pow(hlRollPower))
                val blendWeight = (highlightStrength * 0.78f) * u
                y = y * (1.0f - blendWeight) + shoulderY * blendWeight
            }

            curvePoints[i * 2] = x
            curvePoints[i * 2 + 1] = y.coerceIn(0.0f, 1.0f)
        }

        // Ensure strict monotonicity
        for (i in 1 until numPoints) {
            val prevY = curvePoints[(i - 1) * 2 + 1]
            if (curvePoints[i * 2 + 1] < prevY) {
                curvePoints[i * 2 + 1] = prevY
            }
        }

        return TonemapCurve(curvePoints, curvePoints.clone(), curvePoints.clone())
    }

    companion object {
        /**
         * Builds the real-time AGSL RuntimeShader source code for live preview on TextureView.
         * Embeds the exact Cinema Mode Rec.2020 Natural Log profile with completely independent controls.
         */
        fun buildAgslCode(cfg: CustomVideoPipelineConfig): String {
            val fExposure = String.format(Locale.US, "%.5ff", 2.0f.pow(cfg.exposure))
            val fBlackLevel = String.format(Locale.US, "%.5ff", cfg.blackLevel + cfg.blackClippingControl * 0.015f)
            val fHlRecovery = String.format(Locale.US, "%.5ff", cfg.highlightRecovery)
            val fHlRollOff = String.format(Locale.US, "%.5ff", cfg.highlightRollOff)
            val fHlClipProtect = String.format(Locale.US, "%.5ff", cfg.highlightClippingProtection * 0.04f)
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
            val fRedGain = String.format(Locale.US, "%.5ff", cfg.redGain)
            val fGreenGain = String.format(Locale.US, "%.5ff", cfg.greenGain)
            val fBlueGain = String.format(Locale.US, "%.5ff", cfg.blueGain)
            val fRedCurve = String.format(Locale.US, "%.5ff", cfg.redCurveStrength * 0.25f)
            val fGreenCurve = String.format(Locale.US, "%.5ff", cfg.greenCurveStrength * 0.25f)
            val fBlueCurve = String.format(Locale.US, "%.5ff", cfg.blueCurveStrength * 0.25f)
            val fSharp = String.format(Locale.US, "%.5ff", cfg.sharpening)
            val fMicroContrast = String.format(Locale.US, "%.5ff", cfg.microContrast)
            val fTexture = String.format(Locale.US, "%.5ff", cfg.textureDetail)
            val fDebanding = String.format(Locale.US, "%.5ff", cfg.debanding)
            val fDemosaic = String.format(Locale.US, "%.5ff", cfg.demosaicDetailProcessing)
            val fLensShading = String.format(Locale.US, "%.5ff", cfg.lensShadingCorrection * 0.20f)
            val fDistortion = String.format(Locale.US, "%.5ff", cfg.distortionCorrection * 12.0f)
            val fLumaNr = String.format(Locale.US, "%.5ff", cfg.lumaNoiseReduction)
            val fChromaNr = String.format(Locale.US, "%.5ff", cfg.chromaNoiseReduction)
            val fSpatialNr = String.format(Locale.US, "%.5ff", cfg.spatialNoiseReduction)

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

                // ITU-R BT.2020 exact mathematical transfer conversion
                vec3 toLinearRec2020(vec3 s) {
                    vec3 c = max(s, vec3(0.0));
                    vec3 low = c / 4.5;
                    vec3 high = pow((c + vec3(0.0993)) / 1.0993, vec3(2.2222));
                    return mix(high, low, step(c, vec3(0.0812)));
                }

                vec3 fromLinearRec2020(vec3 lin) {
                    vec3 l = max(lin, vec3(0.0));
                    vec3 low = 4.5 * l;
                    vec3 high = 1.0993 * pow(l, vec3(0.45)) - vec3(0.0993);
                    return mix(high, low, step(l, vec3(0.01805)));
                }

                vec4 main(float2 fragCoord) {
                    float2 step = 1.0 / max(uResolution, float2(1.0, 1.0));
                    float2 normCoord = (fragCoord * step) - float2(0.5);
                    float r2 = dot(normCoord, normCoord);

                    // --- STAGE 0: Lens Distortion & Shading Correction ---
                    float2 distortedCoord = fragCoord + normCoord * (r2 * $fDistortion);
                    float lensGain = 1.0 + r2 * $fLensShading;

                    // --- STAGE 1: 5-Tap Spatial Detail, Micro-Contrast, Noise Reduction & Debanding ---
                    vec4 cCenter = uContent.eval(distortedCoord) * lensGain;
                    vec4 cN = uContent.eval(distortedCoord + float2(0.0, -step.y * 1.25)) * lensGain;
                    vec4 cS = uContent.eval(distortedCoord + float2(0.0,  step.y * 1.25)) * lensGain;
                    vec4 cW = uContent.eval(distortedCoord + float2(-step.x * 1.25, 0.0)) * lensGain;
                    vec4 cE = uContent.eval(distortedCoord + float2( step.x * 1.25, 0.0)) * lensGain;

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

                    // Bilateral edge-preserving spatial/luma noise reduction
                    float edgeWeight = clamp(abs(highPass) * 16.0, 0.0, 1.0);
                    vec3 smoothedColor = 0.5 * baseCleanColor + 0.125 * (cN.rgb + cS.rgb + cW.rgb + cE.rgb);
                    float totalNr = clamp($fLumaNr + $fSpatialNr * 0.5, 0.0, 1.0);
                    vec3 nrColor = mix(smoothedColor, baseCleanColor, edgeWeight + (1.0 - totalNr) * (1.0 - edgeWeight));

                    // Demosaic & Texture detail preservation
                    float textureFactor = (highPass * $fTexture * 0.6) + (highPass * $fDemosaic * 0.4);
                    
                    // Sharpening and micro-contrast injection
                    float detailAmp = $fSharp * 1.0 + $fMicroContrast * 0.70;
                    float clampedHighPass = clamp(highPass * detailAmp + textureFactor, -0.15, 0.15);
                    vec3 detailColor = nrColor + vec3(clampedHighPass);

                    // Debanding dither
                    float dither = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
                    detailColor += vec3(dither * (0.003 * $fDebanding));

                    // Local contrast (Micro-Dynamic)
                    float localRatio = (lCenter + 0.08) / (lAvg + 0.08);
                    vec3 color = mix(detailColor, detailColor * localRatio, $fLocalContrast * 0.35);

                    // Convert to scene linear using ITU-R BT.2020 exact inverse
                    vec3 linearRgb = toLinearRec2020(clamp(color, 0.0, 1.0));

                    // --- STAGE 2: Independent Exposure & Brightness ---
                    linearRgb *= $fExposure;

                    // --- STAGE 3: Independent Black Level Pedestal Anchoring ---
                    linearRgb += vec3($fBlackLevel) * (vec3(1.0) - clamp(linearRgb, 0.0, 1.0));
                    linearRgb = max(linearRgb, vec3(0.0));

                    float curLuma = dot(linearRgb, lumaW);

                    // --- STAGE 4: Independent Shadow Recovery (Toe Lift) ---
                    float shadowLift = 0.0;
                    if (curLuma < 0.38 && curLuma > 0.0) {
                        float shNorm = curLuma / 0.38;
                        float toeLift = 4.0 * shNorm * (1.0 - shNorm) * (1.0 - shNorm);
                        shadowLift = toeLift * ($fShRecovery * 0.22);
                        linearRgb += vec3(shadowLift);
                    }

                    // --- STAGE 5: Independent Shadow Roll-off ---
                    if (curLuma < 0.20 && curLuma > 0.001) {
                        float toeU = curLuma / 0.20;
                        float toeCurve = 4.0 * toeU * (1.0 - toeU) * ($fShRollOff * 0.05);
                        linearRgb += vec3(toeCurve);
                    }

                    // --- STAGE 6: Independent Highlight Recovery ---
                    float hlKnee = 0.62;
                    if (curLuma > hlKnee) {
                        float hlExcess = (curLuma - hlKnee) / 0.38;
                        float hlComp = hlExcess * hlExcess * ($fHlRecovery * 0.16 + $fHlClipProtect);
                        linearRgb = max(vec3(0.0), linearRgb - vec3(hlComp));
                    }

                    // --- STAGE 7: Independent Highlight Roll-off ---
                    if (curLuma > 0.68) {
                        float rollU = (curLuma - 0.68) / 0.32;
                        float shoulder = (1.0 - pow(1.0 - rollU, 1.0 + $fHlRollOff * 1.5)) - rollU;
                        linearRgb += vec3(shoulder * 0.05);
                    }

                    // --- STAGE 8: Independent Midtone Control (18% Gray Bell Curve) ---
                    float midBell = 4.0 * curLuma * (1.0 - curLuma);
                    linearRgb += vec3($fMidtone * 0.15 * midBell);

                    // --- STAGE 9: Independent Contrast (Pivoted at 18% middle gray in linear space ~0.18) ---
                    float cSlope = 1.0 + $fContrast * 0.38;
                    linearRgb = (linearRgb - vec3(0.18)) * cSlope + vec3(0.18);
                    linearRgb = max(linearRgb, vec3(0.0));

                    // --- STAGE 10: Independent White Balance, Color Gains & Gamut Matrix ---
                    float rWb = (1.0 + $fTemp * 0.15 + $fTint * 0.05) * $fRedGain;
                    float gWb = (1.0 - $fTint * 0.10) * $fGreenGain;
                    float bWb = (1.0 - $fTemp * 0.15 + $fTint * 0.05) * $fBlueGain;
                    linearRgb *= vec3(rWb, gWb, bWb);

                    mat3 colorMat = mat3(
                        $m00, $m10, $m20,
                        $m01, $m11, $m21,
                        $m02, $m12, $m22
                    );
                    linearRgb = max(colorMat * linearRgb, vec3(0.0));

                    // Per-channel RGB Curves deviation (independent)
                    float fRedCurve = $fRedCurve;
                    float fGreenCurve = $fGreenCurve;
                    float fBlueCurve = $fBlueCurve;
                    linearRgb.r += (linearRgb.r * (1.0 - linearRgb.r)) * fRedCurve;
                    linearRgb.g += (linearRgb.g * (1.0 - linearRgb.g)) * fGreenCurve;
                    linearRgb.b += (linearRgb.b * (1.0 - linearRgb.b)) * fBlueCurve;
                    linearRgb = max(linearRgb, vec3(0.0));

                    // Convert from scene linear back to display space using exact BT.2020 forward transfer
                    vec3 displayRgb = fromLinearRec2020(linearRgb);

                    // --- STAGE 11: Rec.2020 Natural Saturation & Vibrance (1.14x Cinema Mode Baseline) ---
                    float outLuma = dot(displayRgb, lumaW);
                    vec3 chromaVec = (displayRgb - vec3(outLuma)) * ($fSat * $fChroma * 1.14);

                    float maxC = max(displayRgb.r, max(displayRgb.g, displayRgb.b));
                    float minC = min(displayRgb.r, min(displayRgb.g, displayRgb.b));
                    float satAmount = (maxC - minC) / max(maxC, 0.001);
                    float vibranceBoost = $fVibrance * (1.0 - satAmount) * 0.35;
                    chromaVec *= (1.0 + vibranceBoost);

                    vec3 gradedColor = max(vec3(outLuma) + chromaVec, vec3(0.0));

                    // --- STAGE 12: Filmic Tone Mapping (Cinema Mode Final Output Transform) ---
                    gradedColor = gradedColor * 0.975 - vec3(0.012);
                    vec3 finalColor = clamp(gradedColor, 0.0, 1.0);

                    return vec4(finalColor, cCenter.a);
                }
            """.trimIndent()
        }

        /**
         * Builds the matching OpenGL ES 2.0 fragment shader for recording and transcoding.
         */
        fun buildGlslCode(cfg: CustomVideoPipelineConfig): String {
            val fExposure = String.format(Locale.US, "%.5ff", 2.0f.pow(cfg.exposure))
            val fBlackLevel = String.format(Locale.US, "%.5ff", cfg.blackLevel + cfg.blackClippingControl * 0.015f)
            val fHlRecovery = String.format(Locale.US, "%.5ff", cfg.highlightRecovery)
            val fHlRollOff = String.format(Locale.US, "%.5ff", cfg.highlightRollOff)
            val fHlClipProtect = String.format(Locale.US, "%.5ff", cfg.highlightClippingProtection * 0.04f)
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
            val fRedGain = String.format(Locale.US, "%.5ff", cfg.redGain)
            val fGreenGain = String.format(Locale.US, "%.5ff", cfg.greenGain)
            val fBlueGain = String.format(Locale.US, "%.5ff", cfg.blueGain)
            val fRedCurve = String.format(Locale.US, "%.5ff", cfg.redCurveStrength * 0.25f)
            val fGreenCurve = String.format(Locale.US, "%.5ff", cfg.greenCurveStrength * 0.25f)
            val fBlueCurve = String.format(Locale.US, "%.5ff", cfg.blueCurveStrength * 0.25f)
            val fSharp = String.format(Locale.US, "%.5ff", cfg.sharpening)
            val fMicroContrast = String.format(Locale.US, "%.5ff", cfg.microContrast)
            val fTexture = String.format(Locale.US, "%.5ff", cfg.textureDetail)
            val fDebanding = String.format(Locale.US, "%.5ff", cfg.debanding)
            val fDemosaic = String.format(Locale.US, "%.5ff", cfg.demosaicDetailProcessing)
            val fLensShading = String.format(Locale.US, "%.5ff", cfg.lensShadingCorrection * 0.20f)
            val fDistortion = String.format(Locale.US, "%.5ff", cfg.distortionCorrection * 0.015f)
            val fLumaNr = String.format(Locale.US, "%.5ff", cfg.lumaNoiseReduction)
            val fChromaNr = String.format(Locale.US, "%.5ff", cfg.chromaNoiseReduction)
            val fSpatialNr = String.format(Locale.US, "%.5ff", cfg.spatialNoiseReduction)

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
                    vec3 c = max(s, vec3(0.0));
                    vec3 low = c / 4.5;
                    vec3 high = pow((c + vec3(0.0993)) / 1.0993, vec3(2.2222));
                    return mix(high, low, step(c, vec3(0.0812)));
                }

                vec3 fromLinearRec2020(vec3 lin) {
                    vec3 l = max(lin, vec3(0.0));
                    vec3 low = 4.5 * l;
                    vec3 high = 1.0993 * pow(l, vec3(0.45)) - vec3(0.0993);
                    return mix(high, low, step(l, vec3(0.01805)));
                }

                void main() {
                    vec2 uv = vTextureCoord;
                    vec2 normCoord = uv - vec2(0.5);
                    float r2 = dot(normCoord, normCoord);

                    // Lens Distortion & Shading Correction
                    vec2 distortedUv = uv + normCoord * (r2 * $fDistortion);
                    float lensGain = 1.0 + r2 * $fLensShading;

                    vec2 step = vec2(0.00092, 0.00052);

                    // 5-Tap Spatial Detail & Noise Filtering
                    vec4 cCenter = texture2D(sTexture, distortedUv) * lensGain;
                    vec4 cN = texture2D(sTexture, distortedUv + vec2(0.0, -step.y * 1.25)) * lensGain;
                    vec4 cS = texture2D(sTexture, distortedUv + vec2(0.0,  step.y * 1.25)) * lensGain;
                    vec4 cW = texture2D(sTexture, distortedUv + vec2(-step.x * 1.25, 0.0)) * lensGain;
                    vec4 cE = texture2D(sTexture, distortedUv + vec2( step.x * 1.25, 0.0)) * lensGain;

                    vec3 lumaW = vec3(0.2627, 0.6780, 0.0593);
                    float lCenter = dot(cCenter.rgb, lumaW);
                    float lN = dot(cN.rgb, lumaW);
                    float lS = dot(cS.rgb, lumaW);
                    float lW = dot(cW.rgb, lumaW);
                    float lE = dot(cE.rgb, lumaW);
                    float lAvg = 0.25 * (lN + lS + lW + lE);

                    float highPass = lCenter - lAvg;
                    
                    // Chroma noise filtering
                    vec3 chromaDiff = cCenter.rgb - vec3(lCenter);
                    vec3 avgChromaDiff = 0.25 * ((cN.rgb - vec3(lN)) + (cS.rgb - vec3(lS)) + (cW.rgb - vec3(lW)) + (cE.rgb - vec3(lE)));
                    vec3 filteredChroma = mix(chromaDiff, avgChromaDiff, $fChromaNr * 0.75);
                    vec3 baseCleanColor = vec3(lCenter) + filteredChroma;

                    // Bilateral edge-preserving spatial/luma noise reduction
                    float edgeWeight = clamp(abs(highPass) * 16.0, 0.0, 1.0);
                    vec3 smoothedColor = 0.5 * baseCleanColor + 0.125 * (cN.rgb + cS.rgb + cW.rgb + cE.rgb);
                    float totalNr = clamp($fLumaNr + $fSpatialNr * 0.5, 0.0, 1.0);
                    vec3 nrColor = mix(smoothedColor, baseCleanColor, edgeWeight + (1.0 - totalNr) * (1.0 - edgeWeight));

                    float textureFactor = (highPass * $fTexture * 0.6) + (highPass * $fDemosaic * 0.4);
                    float detailAmp = $fSharp * 1.0 + $fMicroContrast * 0.70;
                    vec3 detailColor = nrColor + vec3(clamp(highPass * detailAmp + textureFactor, -0.15, 0.15));

                    // Debanding dither
                    float dither = fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
                    detailColor += vec3(dither * (0.003 * $fDebanding));

                    // Local contrast (Micro-Dynamic)
                    float localRatio = (lCenter + 0.08) / (lAvg + 0.08);
                    vec3 color = mix(detailColor, detailColor * localRatio, $fLocalContrast * 0.35);

                    // Convert to scene linear using ITU-R BT.2020 exact inverse
                    vec3 linearRgb = toLinearRec2020(clamp(color, 0.0, 1.0));

                    // Independent Exposure & Brightness
                    linearRgb *= $fExposure;

                    // Independent Black Level Pedestal Anchoring
                    linearRgb += vec3($fBlackLevel) * (vec3(1.0) - clamp(linearRgb, 0.0, 1.0));
                    linearRgb = max(linearRgb, vec3(0.0));

                    float curLuma = dot(linearRgb, lumaW);

                    // Independent Shadow Recovery
                    float shadowLift = 0.0;
                    if (curLuma < 0.38 && curLuma > 0.0) {
                        float shNorm = curLuma / 0.38;
                        float toeLift = 4.0 * shNorm * (1.0 - shNorm) * (1.0 - shNorm);
                        shadowLift = toeLift * ($fShRecovery * 0.22);
                        linearRgb += vec3(shadowLift);
                    }

                    // Independent Shadow Roll-off
                    if (curLuma < 0.20 && curLuma > 0.001) {
                        float toeU = curLuma / 0.20;
                        float toeCurve = 4.0 * toeU * (1.0 - toeU) * ($fShRollOff * 0.05);
                        linearRgb += vec3(toeCurve);
                    }

                    // Independent Highlight Recovery
                    float hlKnee = 0.62;
                    if (curLuma > hlKnee) {
                        float hlExcess = (curLuma - hlKnee) / 0.38;
                        float hlComp = hlExcess * hlExcess * ($fHlRecovery * 0.16 + $fHlClipProtect);
                        linearRgb = max(vec3(0.0), linearRgb - vec3(hlComp));
                    }

                    // Independent Highlight Roll-off
                    if (curLuma > 0.68) {
                        float rollU = (curLuma - 0.68) / 0.32;
                        float shoulder = (1.0 - pow(1.0 - rollU, 1.0 + $fHlRollOff * 1.5)) - rollU;
                        linearRgb += vec3(shoulder * 0.05);
                    }

                    // Independent Midtone Control
                    float midBell = 4.0 * curLuma * (1.0 - curLuma);
                    linearRgb += vec3($fMidtone * 0.15 * midBell);

                    // Independent Contrast
                    float cSlope = 1.0 + $fContrast * 0.38;
                    linearRgb = (linearRgb - vec3(0.18)) * cSlope + vec3(0.18);
                    linearRgb = max(linearRgb, vec3(0.0));

                    // Independent White Balance, Color Gains & Gamut Matrix
                    float rWb = (1.0 + $fTemp * 0.15 + $fTint * 0.05) * $fRedGain;
                    float gWb = (1.0 - $fTint * 0.10) * $fGreenGain;
                    float bWb = (1.0 - $fTemp * 0.15 + $fTint * 0.05) * $fBlueGain;
                    linearRgb *= vec3(rWb, gWb, bWb);

                    mat3 colorMat = mat3(
                        $m00, $m10, $m20,
                        $m01, $m11, $m21,
                        $m02, $m12, $m22
                    );
                    linearRgb = max(colorMat * linearRgb, vec3(0.0));

                    // Per-channel RGB curves
                    float fRedCurve = $fRedCurve;
                    float fGreenCurve = $fGreenCurve;
                    float fBlueCurve = $fBlueCurve;
                    linearRgb.r += (linearRgb.r * (1.0 - linearRgb.r)) * fRedCurve;
                    linearRgb.g += (linearRgb.g * (1.0 - linearRgb.g)) * fGreenCurve;
                    linearRgb.b += (linearRgb.b * (1.0 - linearRgb.b)) * fBlueCurve;
                    linearRgb = max(linearRgb, vec3(0.0));

                    // Convert from scene linear back to display space
                    vec3 displayRgb = fromLinearRec2020(linearRgb);

                    // Rec.2020 Natural Saturation & Vibrance (1.14x Cinema Mode Baseline)
                    float outLuma = dot(displayRgb, lumaW);
                    vec3 chromaVec = (displayRgb - vec3(outLuma)) * ($fSat * $fChroma * 1.14);
                    float maxC = max(displayRgb.r, max(displayRgb.g, displayRgb.b));
                    float minC = min(displayRgb.r, min(displayRgb.g, displayRgb.b));
                    float satAmount = (maxC - minC) / max(maxC, 0.001);
                    float vibranceBoost = $fVibrance * (1.0 - satAmount) * 0.35;
                    chromaVec *= (1.0 + vibranceBoost);

                    vec3 gradedColor = max(vec3(outLuma) + chromaVec, vec3(0.0));

                    // Filmic Tone Mapping (Cinema Mode Final Output Transform)
                    gradedColor = gradedColor * 0.975 - vec3(0.012);
                    vec3 finalColor = clamp(gradedColor, 0.0, 1.0);

                    gl_FragColor = vec4(finalColor, cCenter.a);
                }
            """.trimIndent()
        }
    }
}
