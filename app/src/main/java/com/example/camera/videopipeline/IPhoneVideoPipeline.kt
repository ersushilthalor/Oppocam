package com.example.camera.videopipeline

import android.graphics.ColorMatrix

/**
 * Dedicated iPhone Video Processing Pipeline.
 *
 * Implements an independent 6-stage Apple Smart HDR computational video pipeline:
 * - Stage 0 (ISP): Sensor highlight headroom (-0.15 EV) & custom linearized sensor TonemapCurve
 * - Stage 1 (Linearization): De-gamma & ambient daylight warmth pre-adaptation
 * - Stage 2 (Spatial Convolution): 5-tap optical detail & mid-frequency micro-contrast with halo suppression
 * - Stage 3 (Chromatic Adaptation): 3x3 Apple color matrix for organic warmth, rich melanin & true sky
 * - Stage 4 (Multi-Zone Smart HDR): Smooth shoulder highlight compression, midtone subject separation
 *   bell curve (0.30 - 0.65), and deep detailed shadow toe recovery
 * - Stage 5 (Memory Color Protection): Melanin/peach skin-tone & sky-cyan protection
 * - Stage 6 (Filmic Output): S-curve contrast centered at 0.46, luma-weighted vibrance & black-point lock
 */
class IPhoneVideoPipeline : BaseVideoPipeline(VideoPipelineType.IPHONE) {

    override val stageParams: VideoPipelineStageParams = VideoPipelineStageParams(
        sensorExposureBiasEv = -0.15f,
        ispTonemapGamma = 1.14f,
        ispHighlightRollOff = 0.08f,
        bypassHalEdgeSharpening = true,
        highQualityTemporalDenoise = true,
        inputDeGamma = 1.06f,
        linearExposureGain = 1.03f,
        warmthShift = 0.018f,
        tintShift = 0.004f,
        highlightKneeThreshold = 0.65f,
        highlightCompressionStrength = 0.38f,
        shadowToeLimit = 0.30f,
        shadowLiftStrength = 0.050f,
        subjectMidtoneCenter = 0.48f,
        subjectSeparationLift = 0.055f,
        spatialSharpnessStrength = 0.22f,
        localMicroContrastStrength = 0.14f,
        detailRadiusTexels = 1.25f,
        haloProtectionLimit = 0.07f,
        colorMatrix3x3 = floatArrayOf(
            1.040f, -0.015f, -0.010f,
            -0.008f, 1.025f, -0.005f,
            -0.018f, -0.010f, 0.985f
        ),
        skinToneProtectionStrength = 0.30f,
        skinWarmthTargetR = 1.015f,
        skinWarmthTargetG = 1.000f,
        skinWarmthTargetB = 0.985f,
        skyBlueRetention = 0.18f,
        filmicContrastSlope = 1.07f,
        filmicContrastPivot = 0.46f,
        lumaWeightedVibrance = 0.065f,
        globalSaturation = 1.055f,
        blackPointFloor = 0.003f,
        whitePointCeiling = 0.997f,
        outputGamma = 0.97f
    )

    override fun getAgslShaderCode(): String = """
        uniform shader uContent;
        uniform float2 uResolution;

        vec4 main(float2 fragCoord) {
            vec4 src = uContent.eval(fragCoord);
            vec3 rgb = clamp(src.rgb, 0.0, 1.0);

            // Stage 1: Sensor Linearization & Ambient Daylight Pre-Adaptation
            rgb = pow(rgb, vec3(1.06)) * 1.03;
            rgb.r += 0.012;
            rgb.g += 0.003;
            rgb.b -= 0.010;
            rgb = clamp(rgb, 0.0, 1.0);

            // Rec.709 Photometric Luminance
            vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
            float luma = dot(rgb, lumaWeights);

            // Stage 2: 5-Tap Spatial Detail & Local Micro-Contrast Convolution
            float2 stepPx = float2(1.25, 1.25);
            vec3 cN = uContent.eval(clamp(fragCoord + float2(0.0, -stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cS = uContent.eval(clamp(fragCoord + float2(0.0,  stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cE = uContent.eval(clamp(fragCoord + float2( stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cW = uContent.eval(clamp(fragCoord + float2(-stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            float neighborLuma = dot((cN + cS + cE + cW) * 0.25, lumaWeights);
            float highFreqDetail = clamp(luma - neighborLuma, -0.07, 0.07);
            float midtoneDetailWeight = smoothstep(0.06, 0.25, luma) * (1.0 - smoothstep(0.75, 0.96, luma));
            rgb += vec3(highFreqDetail * (0.22 + 0.14 * midtoneDetailWeight));
            rgb = clamp(rgb, 0.0, 1.0);

            // Stage 3: Apple Chromatic Adaptation: organic warmth, rich melanin undertones, accurate sky
            mat3 appleMatrix = mat3(
                1.040, -0.015, -0.010,
                -0.008, 1.025, -0.005,
                -0.018, -0.010, 0.985
            );
            rgb = clamp(appleMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            // Stage 4: Apple Smart HDR Multi-Zone Tone Mapping
            // 4a. Highlight Retention: smooth shoulder roll-off prevents harsh clipping of specular highlights & clouds
            float hl = max(0.0, luma - 0.65);
            float hlCompression = hl * hl * 0.38;
            rgb -= vec3(hlCompression);

            // 4b. Foreground / Subject Separation: midtones (0.30 - 0.65) where human subjects/faces reside
            float subjectDist = abs(luma - 0.48);
            float subjectMask = clamp(1.0 - 4.0 * subjectDist * subjectDist, 0.0, 1.0);
            rgb += vec3(subjectMask * 0.055);

            // 4c. Detailed Shadows with Contrast (avoids flat log look while revealing shadow texture)
            float shadowT = smoothstep(0.05, 0.30, luma) * (1.0 - smoothstep(0.30, 0.55, luma));
            rgb += vec3(shadowT * 0.050);

            // Stage 5: Memory Color Protection (Natural Melanin/Peach Skin & Sky Retention)
            float isSkin = clamp((rgb.r - rgb.b) * 2.2, 0.0, 1.0) * clamp((rgb.g - rgb.b) * 1.4, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(1.015, 1.000, 0.985), isSkin * 0.30);
            float isSky = clamp((rgb.b - rgb.r) * 2.0, 0.0, 1.0) * clamp((rgb.b - rgb.g) * 1.5, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(0.975, 1.005, 1.025), isSky * 0.18);

            // Stage 6: Filmic S-Curve Contrast, Luma-Weighted Vibrance & Display Encoding
            rgb = (rgb - 0.46) * 1.07 + 0.46;
            float newLuma = dot(rgb, lumaWeights);
            float maxC = max(rgb.r, max(rgb.g, rgb.b));
            float minC = min(rgb.r, min(rgb.g, rgb.b));
            float sat = maxC - minC;
            float vibranceWeight = (1.0 - sat) * (1.0 - isSkin * 0.65) * 0.065;
            rgb = mix(vec3(newLuma), rgb, 1.055 + vibranceWeight);
            rgb = pow(clamp((rgb - 0.003) / 0.994, 0.0, 1.0), vec3(0.97));

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

            // Stage 1: Sensor Linearization & Ambient Daylight Pre-Adaptation
            rgb = pow(rgb, vec3(1.06)) * 1.03;
            rgb.r += 0.012;
            rgb.g += 0.003;
            rgb.b -= 0.010;
            rgb = clamp(rgb, 0.0, 1.0);

            vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
            float luma = dot(rgb, lumaWeights);

            // Stage 2: 5-Tap Spatial Detail & Local Micro-Contrast Convolution
            vec2 texel = max(uTexelSize, vec2(1.0 / 1920.0, 1.0 / 1080.0)) * 1.25;
            vec3 cN = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0, -texel.y), 0.0, 1.0)).rgb;
            vec3 cS = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0,  texel.y), 0.0, 1.0)).rgb;
            vec3 cE = texture2D(sTexture, clamp(vTextureCoord + vec2( texel.x, 0.0), 0.0, 1.0)).rgb;
            vec3 cW = texture2D(sTexture, clamp(vTextureCoord + vec2(-texel.x, 0.0), 0.0, 1.0)).rgb;
            float neighborLuma = dot((cN + cS + cE + cW) * 0.25, lumaWeights);
            float highFreqDetail = clamp(luma - neighborLuma, -0.07, 0.07);
            float midtoneDetailWeight = smoothstep(0.06, 0.25, luma) * (1.0 - smoothstep(0.75, 0.96, luma));
            rgb += vec3(highFreqDetail * (0.22 + 0.14 * midtoneDetailWeight));
            rgb = clamp(rgb, 0.0, 1.0);

            // Stage 3: Apple Chromatic Adaptation
            mat3 appleMatrix = mat3(
                1.040, -0.015, -0.010,
                -0.008, 1.025, -0.005,
                -0.018, -0.010, 0.985
            );
            rgb = clamp(appleMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            // Stage 4: Apple Smart HDR Multi-Zone Tone Mapping
            float hl = max(0.0, luma - 0.65);
            float hlCompression = hl * hl * 0.38;
            rgb -= vec3(hlCompression);

            float subjectDist = abs(luma - 0.48);
            float subjectMask = clamp(1.0 - 4.0 * subjectDist * subjectDist, 0.0, 1.0);
            rgb += vec3(subjectMask * 0.055);

            float shadowT = smoothstep(0.05, 0.30, luma) * (1.0 - smoothstep(0.30, 0.55, luma));
            rgb += vec3(shadowT * 0.050);

            // Stage 5: Memory Color Protection
            float isSkin = clamp((rgb.r - rgb.b) * 2.2, 0.0, 1.0) * clamp((rgb.g - rgb.b) * 1.4, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(1.015, 1.000, 0.985), isSkin * 0.30);
            float isSky = clamp((rgb.b - rgb.r) * 2.0, 0.0, 1.0) * clamp((rgb.b - rgb.g) * 1.5, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(0.975, 1.005, 1.025), isSky * 0.18);

            // Stage 6: Filmic S-Curve Contrast, Luma-Weighted Vibrance & Display Encoding
            rgb = (rgb - 0.46) * 1.07 + 0.46;
            float newLuma = dot(rgb, lumaWeights);
            float maxC = max(rgb.r, max(rgb.g, rgb.b));
            float minC = min(rgb.r, min(rgb.g, rgb.b));
            float sat = maxC - minC;
            float vibranceWeight = (1.0 - sat) * (1.0 - isSkin * 0.65) * 0.065;
            rgb = mix(vec3(newLuma), rgb, 1.055 + vibranceWeight);
            rgb = pow(clamp((rgb - 0.003) / 0.994, 0.0, 1.0), vec3(0.97));

            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    override fun computeColorMatrix(): ColorMatrix {
        val mat = ColorMatrix(floatArrayOf(
            1.05f, -0.01f, -0.01f, 0f, 4f,
            -0.01f, 1.03f, -0.01f, 0f, 2f,
            -0.02f, -0.01f, 0.99f, 0f, -2f,
            0f, 0f, 0f, 1f, 0f
        ))
        val sat = ColorMatrix().apply { setSaturation(1.06f) }
        mat.postConcat(sat)
        return mat
    }
}
