package com.example.camera.videopipeline

import android.graphics.ColorMatrix

/**
 * Dedicated Samsung Video Processing Pipeline.
 *
 * Implements an independent 6-stage Samsung Super HDR / Vivid Pro video pipeline:
 * - Stage 0 (ISP): Custom Super HDR sensor TonemapCurve & dedicated temporal noise reduction
 * - Stage 1 (Linearization): Luminous exposure pre-gain & crisp daylight white balance
 * - Stage 2 (Spatial Convolution): 5-tap flagship edge crispness & mid-frequency micro-contrast
 * - Stage 3 (Chromatic Adaptation): 3x3 Samsung Vivid matrix for punchy skies, deep emerald foliage & vibrant reds
 * - Stage 4 (Super HDR Tone Mapping): High shadow toe expansion (`shadowToe`), luminous midtone gain (`midtoneGain`),
 *   and highlight knee protection (`hlThreshold`)
 * - Stage 5 (Memory Color Separation): Skin-tone protection (`isSkin`) paired with sky & foliage enhancement
 * - Stage 6 (Filmic Output): High-energy S-curve contrast (1.14x) and dynamic chroma boost
 */
class SamsungVideoPipeline : BaseVideoPipeline(VideoPipelineType.SAMSUNG) {

    override val stageParams: VideoPipelineStageParams = VideoPipelineStageParams(
        sensorExposureBiasEv = 0.05f,
        ispTonemapGamma = 1.18f,
        ispHighlightRollOff = 0.10f,
        bypassHalEdgeSharpening = true,
        highQualityTemporalDenoise = true,
        inputDeGamma = 1.04f,
        linearExposureGain = 1.06f,
        warmthShift = 0.004f,
        tintShift = -0.003f,
        highlightKneeThreshold = 0.72f,
        highlightCompressionStrength = 0.12f,
        shadowToeLimit = 0.45f,
        shadowLiftStrength = 0.11f,
        subjectMidtoneCenter = 0.50f,
        subjectSeparationLift = 0.085f,
        spatialSharpnessStrength = 0.34f,
        localMicroContrastStrength = 0.20f,
        detailRadiusTexels = 1.15f,
        haloProtectionLimit = 0.09f,
        colorMatrix3x3 = floatArrayOf(
            1.065f, -0.030f, -0.020f,
            -0.020f, 1.055f, -0.015f,
            -0.015f, -0.025f, 1.080f
        ),
        skinToneProtectionStrength = 0.65f,
        skinWarmthTargetR = 1.010f,
        skinWarmthTargetG = 1.000f,
        skinWarmthTargetB = 0.990f,
        skyBlueRetention = 0.25f,
        filmicContrastSlope = 1.14f,
        filmicContrastPivot = 0.46f,
        lumaWeightedVibrance = 0.10f,
        globalSaturation = 1.20f,
        blackPointFloor = 0.002f,
        whitePointCeiling = 0.998f,
        outputGamma = 0.95f
    )

    override fun getAgslShaderCode(): String = """
        uniform shader uContent;
        uniform float2 uResolution;

        vec4 main(float2 fragCoord) {
            vec4 src = uContent.eval(fragCoord);
            vec3 rgb = clamp(src.rgb, 0.0, 1.0);

            // Stage 1: Sensor Linearization & Luminous Pre-Gain
            rgb = pow(rgb, vec3(1.04)) * 1.05;
            rgb = clamp(rgb, 0.0, 1.0);

            vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
            float luma = dot(rgb, lumaWeights);

            // Stage 2: 5-Tap Spatial Edge Crispness & Local Micro-Contrast
            float2 stepPx = float2(1.15, 1.15);
            vec3 cN = uContent.eval(clamp(fragCoord + float2(0.0, -stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cS = uContent.eval(clamp(fragCoord + float2(0.0,  stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cE = uContent.eval(clamp(fragCoord + float2( stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cW = uContent.eval(clamp(fragCoord + float2(-stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            float neighborLuma = dot((cN + cS + cE + cW) * 0.25, lumaWeights);
            float edgeDetail = clamp(luma - neighborLuma, -0.09, 0.09);
            float detailMask = smoothstep(0.05, 0.20, luma) * (1.0 - smoothstep(0.82, 0.98, luma));
            rgb += vec3(edgeDetail * (0.34 + 0.20 * detailMask));
            rgb = clamp(rgb, 0.0, 1.0);

            // Stage 3: Samsung Vivid Chromatic Adaptation: punchy skies, deep foliage, vibrant warm tones
            mat3 samsungMatrix = mat3(
                1.065, -0.030, -0.020,
                -0.020,  1.055, -0.015,
                -0.015, -0.025,  1.080
            );
            rgb = clamp(samsungMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            // Stage 4: Super HDR Lifted Shadows, Bright Midtones & Highlight Knee
            float shadowToe = (1.0 - smoothstep(0.02, 0.45, luma));
            float shadowLift = shadowToe * shadowToe * 0.11;
            float midtoneGain = sin(clamp(luma, 0.0, 1.0) * 3.14159) * 0.085;
            rgb += vec3(shadowLift + midtoneGain);

            // Strong Local Contrast: S-curve with elevated slope
            rgb = (rgb - 0.46) * 1.14 + 0.46;

            // Rich Highlights without digital clipping
            float hlThreshold = 0.72;
            if (luma > hlThreshold) {
                float excess = (luma - hlThreshold) / 0.28;
                rgb -= vec3(excess * excess * 0.12);
            }

            // Stage 5 & 6: Punchy Color Separation, Sky/Foliage Boost & Natural Skin Preservation
            float newLuma = dot(rgb, lumaWeights);
            float skinDist = max(0.0, rgb.r - max(rgb.g, rgb.b));
            float isSkin = smoothstep(0.05, 0.25, skinDist);
            float satFactor = mix(1.20, 1.07, isSkin);
            rgb = mix(vec3(newLuma), rgb, satFactor);

            float isSky = clamp((rgb.b - rgb.r) * 1.8, 0.0, 1.0) * clamp((rgb.b - rgb.g) * 1.2, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(0.95, 1.01, 1.05), isSky * 0.22);

            return vec4(clamp(rgb, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    override fun getGlFragmentShaderCode(): String = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        uniform vec2 uTexelSize;

        void main() {
            vec4 src = texture2D(sTexture, vTextureCoord);
            vec3 rgb = clamp(src.rgb, 0.0, 1.0);

            // Stage 1: Sensor Linearization & Luminous Pre-Gain
            rgb = pow(rgb, vec3(1.04)) * 1.05;
            rgb = clamp(rgb, 0.0, 1.0);

            vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
            float luma = dot(rgb, lumaWeights);

            // Stage 2: 5-Tap Spatial Edge Crispness & Local Micro-Contrast
            vec2 texel = max(uTexelSize, vec2(1.0 / 1920.0, 1.0 / 1080.0)) * 1.15;
            vec3 cN = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0, -texel.y), 0.0, 1.0)).rgb;
            vec3 cS = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0,  texel.y), 0.0, 1.0)).rgb;
            vec3 cE = texture2D(sTexture, clamp(vTextureCoord + vec2( texel.x, 0.0), 0.0, 1.0)).rgb;
            vec3 cW = texture2D(sTexture, clamp(vTextureCoord + vec2(-texel.x, 0.0), 0.0, 1.0)).rgb;
            float neighborLuma = dot((cN + cS + cE + cW) * 0.25, lumaWeights);
            float edgeDetail = clamp(luma - neighborLuma, -0.09, 0.09);
            float detailMask = smoothstep(0.05, 0.20, luma) * (1.0 - smoothstep(0.82, 0.98, luma));
            rgb += vec3(edgeDetail * (0.34 + 0.20 * detailMask));
            rgb = clamp(rgb, 0.0, 1.0);

            // Stage 3: Samsung Vivid Chromatic Adaptation
            mat3 samsungMatrix = mat3(
                1.065, -0.030, -0.020,
                -0.020,  1.055, -0.015,
                -0.015, -0.025,  1.080
            );
            rgb = clamp(samsungMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            // Stage 4: Super HDR Lifted Shadows, Bright Midtones & Highlight Knee
            float shadowToe = (1.0 - smoothstep(0.02, 0.45, luma));
            float shadowLift = shadowToe * shadowToe * 0.11;
            float midtoneGain = sin(clamp(luma, 0.0, 1.0) * 3.14159) * 0.085;
            rgb += vec3(shadowLift + midtoneGain);

            rgb = (rgb - 0.46) * 1.14 + 0.46;

            float hlThreshold = 0.72;
            if (luma > hlThreshold) {
                float excess = (luma - hlThreshold) / 0.28;
                rgb -= vec3(excess * excess * 0.12);
            }

            // Stage 5 & 6: Punchy Color Separation, Sky/Foliage Boost & Natural Skin Preservation
            float newLuma = dot(rgb, lumaWeights);
            float skinDist = max(0.0, rgb.r - max(rgb.g, rgb.b));
            float isSkin = smoothstep(0.05, 0.25, skinDist);
            float satFactor = mix(1.20, 1.07, isSkin);
            rgb = mix(vec3(newLuma), rgb, satFactor);

            float isSky = clamp((rgb.b - rgb.r) * 1.8, 0.0, 1.0) * clamp((rgb.b - rgb.g) * 1.2, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(0.95, 1.01, 1.05), isSky * 0.22);

            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    override fun computeColorMatrix(): ColorMatrix {
        val mat = ColorMatrix(floatArrayOf(
            1.10f, -0.02f, -0.02f, 0f, 10f,
            -0.02f, 1.08f, -0.02f, 0f, 10f,
            -0.01f, -0.02f, 1.12f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f
        ))
        val sat = ColorMatrix().apply { setSaturation(1.20f) }
        mat.postConcat(sat)
        return mat
    }
}
