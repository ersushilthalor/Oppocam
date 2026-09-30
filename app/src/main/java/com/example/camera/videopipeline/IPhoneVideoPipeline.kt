package com.example.camera.videopipeline

import android.graphics.ColorMatrix

/**
 * Dedicated iPhone Video Processing Pipeline.
 *
 * Pipeline Structure:
 * LOG -> Natural Rec.709 conversion -> Brand-specific processing -> Final output
 *
 * Character:
 * Natural + slightly warm + bright + HDR + realistic depth.
 *
 * Targets:
 * - Very natural and realistic colors
 * - Very slightly warm overall tone (subtle daylight warmth, not yellow/orange)
 * - Accurate and natural skin tones (melanin/peach preservation)
 * - Strong HDR and dynamic range with smooth highlight roll-off
 * - Good shadow detail with deep, clean blacks
 * - Bright, well-exposed foreground (Apple's signature subject exposure)
 * - Natural contrast with NO flat LOG appearance
 * - Realistic reds, greens, and blues with controlled saturation
 * - Fine optical details without excessive sharpening or harsh halos
 * - Natural micro-contrast and realistic scene depth
 */
class IPhoneVideoPipeline : BaseVideoPipeline(VideoPipelineType.IPHONE) {

    override val stageParams: VideoPipelineStageParams = VideoPipelineStageParams(
        sensorExposureBiasEv = -0.10f,
        ispTonemapGamma = 1.15f,
        ispHighlightRollOff = 0.10f,
        bypassHalEdgeSharpening = true,
        highQualityTemporalDenoise = true,
        inputDeGamma = 1.04f,
        linearExposureGain = 1.02f,
        warmthShift = 0.012f,
        tintShift = 0.002f,
        highlightKneeThreshold = 0.68f,
        highlightCompressionStrength = 0.32f,
        shadowToeLimit = 0.28f,
        shadowLiftStrength = 0.045f,
        subjectMidtoneCenter = 0.46f,
        subjectSeparationLift = 0.060f,
        spatialSharpnessStrength = 0.18f,
        localMicroContrastStrength = 0.10f,
        detailRadiusTexels = 1.10f,
        haloProtectionLimit = 0.05f,
        colorMatrix3x3 = floatArrayOf(
            1.025f, -0.012f, -0.005f,
            -0.005f, 1.018f, -0.005f,
            -0.012f, -0.008f, 0.995f
        ),
        skinToneProtectionStrength = 0.35f,
        skinWarmthTargetR = 1.012f,
        skinWarmthTargetG = 1.000f,
        skinWarmthTargetB = 0.988f,
        skyBlueRetention = 0.18f,
        filmicContrastSlope = 1.06f,
        filmicContrastPivot = 0.45f,
        lumaWeightedVibrance = 0.045f,
        globalSaturation = 1.045f,
        blackPointFloor = 0.001f,
        whitePointCeiling = 0.998f,
        outputGamma = 0.98f
    )

    override fun getAgslShaderCode(): String = """
        uniform shader uContent;
        uniform float2 uResolution;

        // --- Stage 1 & 2: LOG -> Natural Rec.709 Shared Foundation ---
        vec3 cameraToSceneLog(vec3 c) {
            vec3 lin = pow(max(c, vec3(0.0)), vec3(2.2));
            return clamp((log2(lin * 16.0 + 0.01) + 6.64) / 10.64, 0.0, 1.0);
        }

        vec3 sceneLogToNaturalRec709(vec3 logVal) {
            vec3 lin = (exp2(logVal * 10.64 - 6.64) - 0.01) / 16.0;
            lin = max(lin, vec3(0.0));
            // Photographic tone curve: middle grey 0.18 -> standard Rec.709 ~0.41, smooth highlight roll-off
            vec3 mapped = lin * (1.0 + lin * 0.45) / (lin * (1.0 + lin * 0.45) + 0.38);
            vec3 r709;
            r709.r = (mapped.r < 0.018) ? (4.5 * mapped.r) : (1.099 * pow(mapped.r, 0.45) - 0.099);
            r709.g = (mapped.g < 0.018) ? (4.5 * mapped.g) : (1.099 * pow(mapped.g, 0.45) - 0.099);
            r709.b = (mapped.b < 0.018) ? (4.5 * mapped.b) : (1.099 * pow(mapped.b, 0.45) - 0.099);
            return clamp(r709, 0.0, 1.0);
        }

        vec4 main(float2 fragCoord) {
            vec4 src = uContent.eval(fragCoord);
            vec3 inputRgb = clamp(src.rgb, 0.0, 1.0);

            // STAGE 1: Sensor/Camera to high dynamic range LOG
            vec3 logRgb = cameraToSceneLog(inputRgb);

            // STAGE 2: Natural LOG -> Rec.709 baseline conversion (NO flat LOG look, true Rec.709 foundation)
            vec3 rgb = sceneLogToNaturalRec709(logRgb);

            vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
            float luma = dot(rgb, lumaWeights);

            // STAGE 3: Brand-Specific iPhone Processing
            // 3a. Fine Optical Micro-Contrast (5-tap convolution, delicate, zero harsh halos)
            float2 stepPx = float2(1.10, 1.10);
            vec3 cN = uContent.eval(clamp(fragCoord + float2(0.0, -stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cS = uContent.eval(clamp(fragCoord + float2(0.0,  stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cE = uContent.eval(clamp(fragCoord + float2( stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cW = uContent.eval(clamp(fragCoord + float2(-stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            float neighborLuma = dot((cN + cS + cE + cW) * 0.25, lumaWeights);
            float highFreqDetail = clamp(luma - neighborLuma, -0.05, 0.05);
            float midtoneDetailWeight = smoothstep(0.08, 0.25, luma) * (1.0 - smoothstep(0.72, 0.95, luma));
            rgb += vec3(highFreqDetail * (0.18 + 0.10 * midtoneDetailWeight));
            rgb = clamp(rgb, 0.0, 1.0);

            // 3b. Apple Chromatic Adaptation: organic subtle daylight warmth (NOT yellow/orange), realistic primaries
            mat3 appleMatrix = mat3(
                1.025, -0.012, -0.005,
               -0.005,  1.018, -0.005,
               -0.012, -0.008,  0.995
            );
            rgb = clamp(appleMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            // 3c. Apple Smart HDR Multi-Zone Tone Mapping:
            // Highlight retention: smooth shoulder roll-off preserves specular highlights & clouds
            float hl = max(0.0, luma - 0.68);
            float hlCompression = hl * hl * 0.32;
            rgb -= vec3(hlCompression);

            // Bright, well-exposed foreground (Subject Separation Bell Curve centered at 0.46)
            float subjectDist = abs(luma - 0.46);
            float subjectMask = clamp(1.0 - 4.5 * subjectDist * subjectDist, 0.0, 1.0);
            rgb += vec3(subjectMask * 0.060);

            // Detailed shadows: reveals rich texture while anchoring true blacks
            float shadowT = smoothstep(0.04, 0.28, luma) * (1.0 - smoothstep(0.28, 0.50, luma));
            rgb += vec3(shadowT * 0.045);

            // 3d. Accurate & Natural Skin Tones (Melanin/Peach preservation) & Sky Fidelity
            float isSkin = clamp((rgb.r - rgb.b) * 2.2, 0.0, 1.0) * clamp((rgb.g - rgb.b) * 1.5, 0.0, 1.0) * smoothstep(0.12, 0.70, luma);
            rgb = mix(rgb, rgb * vec3(1.012, 1.000, 0.988), isSkin * 0.35);

            float isSky = clamp((rgb.b - rgb.r) * 1.8, 0.0, 1.0) * clamp((rgb.b - rgb.g) * 1.3, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(0.985, 1.005, 1.020), isSky * 0.18);

            // 3e. Natural Filmic Contrast & Controlled Saturation
            rgb = (rgb - 0.45) * 1.06 + 0.45;
            float newLuma = dot(rgb, lumaWeights);
            float maxC = max(rgb.r, max(rgb.g, rgb.b));
            float minC = min(rgb.r, min(rgb.g, rgb.b));
            float sat = maxC - minC;
            float vibranceWeight = (1.0 - sat) * (1.0 - isSkin * 0.60) * 0.045;
            rgb = mix(vec3(newLuma), rgb, 1.045 + vibranceWeight);

            return vec4(clamp(rgb, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    override fun getGlFragmentShaderCode(): String = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        uniform vec2 uTexelSize;

        vec3 cameraToSceneLog(vec3 c) {
            vec3 lin = pow(max(c, vec3(0.0)), vec3(2.2));
            return clamp((log2(lin * 16.0 + 0.01) + 6.64) / 10.64, 0.0, 1.0);
        }

        vec3 sceneLogToNaturalRec709(vec3 logVal) {
            vec3 lin = (exp2(logVal * 10.64 - 6.64) - 0.01) / 16.0;
            lin = max(lin, vec3(0.0));
            vec3 mapped = lin * (1.0 + lin * 0.45) / (lin * (1.0 + lin * 0.45) + 0.38);
            vec3 r709;
            r709.r = (mapped.r < 0.018) ? (4.5 * mapped.r) : (1.099 * pow(mapped.r, 0.45) - 0.099);
            r709.g = (mapped.g < 0.018) ? (4.5 * mapped.g) : (1.099 * pow(mapped.g, 0.45) - 0.099);
            r709.b = (mapped.b < 0.018) ? (4.5 * mapped.b) : (1.099 * pow(mapped.b, 0.45) - 0.099);
            return clamp(r709, 0.0, 1.0);
        }

        void main() {
            vec4 src = texture2D(sTexture, vTextureCoord);
            vec3 inputRgb = clamp(src.rgb, 0.0, 1.0);

            // STAGE 1: Sensor/Camera to high dynamic range LOG
            vec3 logRgb = cameraToSceneLog(inputRgb);

            // STAGE 2: Natural LOG -> Rec.709 baseline conversion
            vec3 rgb = sceneLogToNaturalRec709(logRgb);

            vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
            float luma = dot(rgb, lumaWeights);

            // STAGE 3: Brand-Specific iPhone Processing
            vec2 texel = max(uTexelSize, vec2(1.0 / 1920.0, 1.0 / 1080.0)) * 1.10;
            vec3 cN = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0, -texel.y), 0.0, 1.0)).rgb;
            vec3 cS = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0,  texel.y), 0.0, 1.0)).rgb;
            vec3 cE = texture2D(sTexture, clamp(vTextureCoord + vec2( texel.x, 0.0), 0.0, 1.0)).rgb;
            vec3 cW = texture2D(sTexture, clamp(vTextureCoord + vec2(-texel.x, 0.0), 0.0, 1.0)).rgb;
            float neighborLuma = dot((cN + cS + cE + cW) * 0.25, lumaWeights);
            float highFreqDetail = clamp(luma - neighborLuma, -0.05, 0.05);
            float midtoneDetailWeight = smoothstep(0.08, 0.25, luma) * (1.0 - smoothstep(0.72, 0.95, luma));
            rgb += vec3(highFreqDetail * (0.18 + 0.10 * midtoneDetailWeight));
            rgb = clamp(rgb, 0.0, 1.0);

            mat3 appleMatrix = mat3(
                1.025, -0.012, -0.005,
               -0.005,  1.018, -0.005,
               -0.012, -0.008,  0.995
            );
            rgb = clamp(appleMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            // Apple Smart HDR Multi-Zone Tone Mapping
            float hl = max(0.0, luma - 0.68);
            float hlCompression = hl * hl * 0.32;
            rgb -= vec3(hlCompression);

            float subjectDist = abs(luma - 0.46);
            float subjectMask = clamp(1.0 - 4.5 * subjectDist * subjectDist, 0.0, 1.0);
            rgb += vec3(subjectMask * 0.060);

            float shadowT = smoothstep(0.04, 0.28, luma) * (1.0 - smoothstep(0.28, 0.50, luma));
            rgb += vec3(shadowT * 0.045);

            float isSkin = clamp((rgb.r - rgb.b) * 2.2, 0.0, 1.0) * clamp((rgb.g - rgb.b) * 1.5, 0.0, 1.0) * smoothstep(0.12, 0.70, luma);
            rgb = mix(rgb, rgb * vec3(1.012, 1.000, 0.988), isSkin * 0.35);

            float isSky = clamp((rgb.b - rgb.r) * 1.8, 0.0, 1.0) * clamp((rgb.b - rgb.g) * 1.3, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(0.985, 1.005, 1.020), isSky * 0.18);

            rgb = (rgb - 0.45) * 1.06 + 0.45;
            float newLuma = dot(rgb, lumaWeights);
            float maxC = max(rgb.r, max(rgb.g, rgb.b));
            float minC = min(rgb.r, min(rgb.g, rgb.b));
            float sat = maxC - minC;
            float vibranceWeight = (1.0 - sat) * (1.0 - isSkin * 0.60) * 0.045;
            rgb = mix(vec3(newLuma), rgb, 1.045 + vibranceWeight);

            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    override fun computeColorMatrix(): ColorMatrix {
        val satMatrix = ColorMatrix().apply { setSaturation(1.045f) }
        val warmMatrix = ColorMatrix(
            floatArrayOf(
                1.025f, -0.012f, -0.005f, 0f, 4.0f,
                -0.005f, 1.018f, -0.005f, 0f, 1.0f,
                -0.012f, -0.008f, 0.995f, 0f, -2.0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        warmMatrix.postConcat(satMatrix)
        return warmMatrix
    }
}
