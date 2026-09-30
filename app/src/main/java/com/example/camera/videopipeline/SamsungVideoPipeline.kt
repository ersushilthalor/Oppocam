package com.example.camera.videopipeline

import android.graphics.ColorMatrix

/**
 * Dedicated Samsung Video Processing Pipeline.
 *
 * Pipeline Structure:
 * LOG -> Natural Rec.709 conversion -> Brand-specific processing -> Final output
 *
 * Character:
 * Punchy + vibrant + green pop + bright + high-contrast + detailed.
 *
 * Targets:
 * - Punchy and vibrant overall rendering with rich, energetic colors
 * - Noticeable but controlled saturation (vivid without digital clipping)
 * - Green colors POP and look vivid while remaining believable (foliage, grass, nature)
 * - Stronger micro-contrast and perceived sharpness (crisp edges and fine texture)
 * - Slightly lifted shadows for visible shadow detail without crushed blacks
 * - Strong HDR with highlight protection (prevents blown skies or clipped highlights)
 * - Rich greens, blues, and reds with strong subject/background separation
 * - Natural skin tones without excessive orange/red (dedicated skin-tone safeguard)
 * - Bright and energetic output
 */
class SamsungVideoPipeline : BaseVideoPipeline(VideoPipelineType.SAMSUNG) {

    override val stageParams: VideoPipelineStageParams = VideoPipelineStageParams(
        sensorExposureBiasEv = 0.05f,
        ispTonemapGamma = 1.18f,
        ispHighlightRollOff = 0.12f,
        bypassHalEdgeSharpening = true,
        highQualityTemporalDenoise = true,
        inputDeGamma = 1.04f,
        linearExposureGain = 1.05f,
        warmthShift = 0.003f,
        tintShift = -0.002f,
        highlightKneeThreshold = 0.72f,
        highlightCompressionStrength = 0.16f,
        shadowToeLimit = 0.40f,
        shadowLiftStrength = 0.090f,
        subjectMidtoneCenter = 0.50f,
        subjectSeparationLift = 0.080f,
        spatialSharpnessStrength = 0.35f,
        localMicroContrastStrength = 0.20f,
        detailRadiusTexels = 1.00f,
        haloProtectionLimit = 0.08f,
        colorMatrix3x3 = floatArrayOf(
            1.065f, -0.025f, -0.015f,
            -0.018f,  1.060f, -0.012f,
            -0.015f, -0.022f,  1.075f
        ),
        skinToneProtectionStrength = 0.65f,
        skinWarmthTargetR = 1.010f,
        skinWarmthTargetG = 1.000f,
        skinWarmthTargetB = 0.990f,
        skyBlueRetention = 0.25f,
        filmicContrastSlope = 1.14f,
        filmicContrastPivot = 0.46f,
        lumaWeightedVibrance = 0.10f,
        globalSaturation = 1.18f,
        blackPointFloor = 0.001f,
        whitePointCeiling = 0.998f,
        outputGamma = 0.96f
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

            // STAGE 3: Brand-Specific Samsung Processing
            // 3a. Stronger Micro-Contrast & Perceived Sharpness (Crisp 5-tap convolution)
            float2 stepPx = float2(1.0, 1.0);
            vec3 cN = uContent.eval(clamp(fragCoord + float2(0.0, -stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cS = uContent.eval(clamp(fragCoord + float2(0.0,  stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cE = uContent.eval(clamp(fragCoord + float2( stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cW = uContent.eval(clamp(fragCoord + float2(-stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            float neighborLuma = dot((cN + cS + cE + cW) * 0.25, lumaWeights);
            float edgeDetail = clamp(luma - neighborLuma, -0.08, 0.08);
            float detailMask = smoothstep(0.06, 0.22, luma) * (1.0 - smoothstep(0.80, 0.98, luma));
            rgb += vec3(edgeDetail * (0.35 + 0.18 * detailMask));
            rgb = clamp(rgb, 0.0, 1.0);

            // 3b. Samsung Super HDR Tone Mapping:
            // Lifted shadows: open and visible shadow detail without crushed blacks
            float shadowToe = (1.0 - smoothstep(0.02, 0.40, luma));
            float shadowLift = shadowToe * shadowToe * 0.090;

            // Bright midtones and subject separation
            float midtoneGain = sin(clamp(luma, 0.0, 1.0) * 3.14159) * 0.080;
            rgb += vec3(shadowLift + midtoneGain);

            // High-contrast punchy S-curve
            rgb = (rgb - 0.46) * 1.14 + 0.46;

            // Highlight knee protection: keeps bright skies and sunlit clouds clean without digital clipping
            float hlThreshold = 0.72;
            if (luma > hlThreshold) {
                float excess = (luma - hlThreshold) / 0.28;
                rgb -= vec3(excess * excess * 0.16);
            }

            // 3c. Samsung Vivid Chromatic Adaptation: rich, energetic colors across spectrum
            mat3 samsungMatrix = mat3(
                1.065, -0.025, -0.015,
               -0.018,  1.060, -0.012,
               -0.015, -0.022,  1.075
            );
            rgb = clamp(samsungMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            // 3d. Dedicated Green POP Engine: vivid, lush foliage and grass while remaining believable
            float isGreen = clamp((rgb.g - max(rgb.r, rgb.b)) * 3.2, 0.0, 1.0);
            float greenDominance = clamp((rgb.g / (rgb.r + rgb.b + 0.01) - 0.52) * 2.5, 0.0, 1.0);
            float greenPopMask = max(isGreen, greenDominance);
            rgb = mix(rgb, vec3(rgb.r * 0.93, rgb.g * 1.08, rgb.b * 0.91), greenPopMask * 0.45);

            // Rich vibrant sky blue
            float isSky = clamp((rgb.b - rgb.r) * 1.8, 0.0, 1.0) * clamp((rgb.b - rgb.g) * 1.2, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(0.96, 1.01, 1.06), isSky * 0.25);

            // 3e. Punchy Color Separation & Natural Skin Preservation (NO orange/red skin!)
            float newLuma = dot(rgb, lumaWeights);
            float skinDist = max(0.0, rgb.r - max(rgb.g, rgb.b));
            float isSkin = smoothstep(0.04, 0.22, skinDist) * smoothstep(0.12, 0.75, luma);
            float satFactor = mix(1.18, 1.03, isSkin);
            rgb = mix(vec3(newLuma), rgb, satFactor);

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

            // STAGE 3: Brand-Specific Samsung Processing
            vec2 texel = max(uTexelSize, vec2(1.0 / 1920.0, 1.0 / 1080.0)) * 1.0;
            vec3 cN = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0, -texel.y), 0.0, 1.0)).rgb;
            vec3 cS = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0,  texel.y), 0.0, 1.0)).rgb;
            vec3 cE = texture2D(sTexture, clamp(vTextureCoord + vec2( texel.x, 0.0), 0.0, 1.0)).rgb;
            vec3 cW = texture2D(sTexture, clamp(vTextureCoord + vec2(-texel.x, 0.0), 0.0, 1.0)).rgb;
            float neighborLuma = dot((cN + cS + cE + cW) * 0.25, lumaWeights);
            float edgeDetail = clamp(luma - neighborLuma, -0.08, 0.08);
            float detailMask = smoothstep(0.06, 0.22, luma) * (1.0 - smoothstep(0.80, 0.98, luma));
            rgb += vec3(edgeDetail * (0.35 + 0.18 * detailMask));
            rgb = clamp(rgb, 0.0, 1.0);

            float shadowToe = (1.0 - smoothstep(0.02, 0.40, luma));
            float shadowLift = shadowToe * shadowToe * 0.090;
            float midtoneGain = sin(clamp(luma, 0.0, 1.0) * 3.14159) * 0.080;
            rgb += vec3(shadowLift + midtoneGain);

            rgb = (rgb - 0.46) * 1.14 + 0.46;

            float hlThreshold = 0.72;
            if (luma > hlThreshold) {
                float excess = (luma - hlThreshold) / 0.28;
                rgb -= vec3(excess * excess * 0.16);
            }

            mat3 samsungMatrix = mat3(
                1.065, -0.025, -0.015,
               -0.018,  1.060, -0.012,
               -0.015, -0.022,  1.075
            );
            rgb = clamp(samsungMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            float isGreen = clamp((rgb.g - max(rgb.r, rgb.b)) * 3.2, 0.0, 1.0);
            float greenDominance = clamp((rgb.g / (rgb.r + rgb.b + 0.01) - 0.52) * 2.5, 0.0, 1.0);
            float greenPopMask = max(isGreen, greenDominance);
            rgb = mix(rgb, vec3(rgb.r * 0.93, rgb.g * 1.08, rgb.b * 0.91), greenPopMask * 0.45);

            float isSky = clamp((rgb.b - rgb.r) * 1.8, 0.0, 1.0) * clamp((rgb.b - rgb.g) * 1.2, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(0.96, 1.01, 1.06), isSky * 0.25);

            float newLuma = dot(rgb, lumaWeights);
            float skinDist = max(0.0, rgb.r - max(rgb.g, rgb.b));
            float isSkin = smoothstep(0.04, 0.22, skinDist) * smoothstep(0.12, 0.75, luma);
            float satFactor = mix(1.18, 1.03, isSkin);
            rgb = mix(vec3(newLuma), rgb, satFactor);

            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    override fun computeColorMatrix(): ColorMatrix {
        val satMatrix = ColorMatrix().apply { setSaturation(1.18f) }
        val vividMatrix = ColorMatrix(
            floatArrayOf(
                1.065f, -0.025f, -0.015f, 0f, 3.0f,
                -0.018f,  1.060f, -0.012f, 0f, 4.0f, // green pop lift
                -0.015f, -0.022f,  1.075f, 0f, 2.0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        vividMatrix.postConcat(satMatrix)
        return vividMatrix
    }
}
