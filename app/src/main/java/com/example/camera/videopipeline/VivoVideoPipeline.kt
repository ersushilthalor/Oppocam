package com.example.camera.videopipeline

import android.graphics.ColorMatrix

/**
 * Dedicated Vivo Video Processing Pipeline.
 *
 * Implements an independent 6-stage Vivo ZEISS T* Ultra HDR video pipeline:
 * - Stage 0 (ISP): Highlight-preserving sensor curve & high-quality temporal denoise
 * - Stage 1 (Linearization): ZEISS neutral optical transmittance & anti-flare black-point lock
 * - Stage 2 (Spatial Convolution): 5-tap ZEISS T* 3D micro-contrast & fine texture extraction
 * - Stage 3 (Chromatic Adaptation): 3x3 ZEISS Natural Color calibration (`vivoMatrix`)
 * - Stage 4 (Ultra HDR Tone Mapping): Controlled highlight knee above 0.70 (`knee`), clean shadow
 *   detail expansion (`shadowT`) with pinned true blacks
 * - Stage 5 (Clarity & Chromatic Balance): Mid-frequency texture clarity (`clarityMask`) & skin/foliage fidelity
 * - Stage 6 (Filmic Output): Natural tonal slope (1.09x) & balanced 1.085x saturation
 */
class VivoVideoPipeline : BaseVideoPipeline(VideoPipelineType.VIVO) {

    override val stageParams: VideoPipelineStageParams = VideoPipelineStageParams(
        sensorExposureBiasEv = -0.10f,
        ispTonemapGamma = 1.16f,
        ispHighlightRollOff = 0.12f,
        bypassHalEdgeSharpening = true,
        highQualityTemporalDenoise = true,
        inputDeGamma = 1.05f,
        linearExposureGain = 1.04f,
        warmthShift = 0.002f,
        tintShift = 0.001f,
        highlightKneeThreshold = 0.70f,
        highlightCompressionStrength = 0.16f,
        shadowToeLimit = 0.30f,
        shadowLiftStrength = 0.065f,
        subjectMidtoneCenter = 0.50f,
        subjectSeparationLift = 0.045f,
        spatialSharpnessStrength = 0.30f,
        localMicroContrastStrength = 0.24f,
        detailRadiusTexels = 1.35f,
        haloProtectionLimit = 0.08f,
        colorMatrix3x3 = floatArrayOf(
            1.025f, -0.010f, -0.005f,
            -0.008f, 1.020f, -0.005f,
            -0.005f, -0.010f, 1.030f
        ),
        skinToneProtectionStrength = 0.35f,
        skinWarmthTargetR = 1.010f,
        skinWarmthTargetG = 1.002f,
        skinWarmthTargetB = 0.992f,
        skyBlueRetention = 0.20f,
        filmicContrastSlope = 1.09f,
        filmicContrastPivot = 0.48f,
        lumaWeightedVibrance = 0.05f,
        globalSaturation = 1.085f,
        blackPointFloor = 0.004f,
        whitePointCeiling = 0.996f,
        outputGamma = 0.98f
    )

    override fun getAgslShaderCode(): String = """
        uniform shader uContent;
        uniform float2 uResolution;

        vec4 main(float2 fragCoord) {
            vec4 src = uContent.eval(fragCoord);
            vec3 rgb = clamp(src.rgb, 0.0, 1.0);

            // Stage 1: ZEISS T* Anti-Flare Linearization & Optical Transmittance
            rgb = pow(max(rgb - 0.003, 0.0) / 0.997, vec3(1.05)) * 1.04;
            rgb = clamp(rgb, 0.0, 1.0);

            vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
            float luma = dot(rgb, lumaWeights);

            // Stage 2: 5-Tap ZEISS T* 3D Spatial Micro-Contrast & Fine Texture Extraction
            float2 stepPx = float2(1.35, 1.35);
            vec3 cN = uContent.eval(clamp(fragCoord + float2(0.0, -stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cS = uContent.eval(clamp(fragCoord + float2(0.0,  stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cE = uContent.eval(clamp(fragCoord + float2( stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cW = uContent.eval(clamp(fragCoord + float2(-stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 neighborMean = (cN + cS + cE + cW) * 0.25;
            float neighborLuma = dot(neighborMean, lumaWeights);
            float microDetail = clamp(luma - neighborLuma, -0.08, 0.08);
            float clarityMask = clamp(1.0 - abs(luma - 0.50) * 2.0, 0.0, 1.0);
            rgb += vec3(microDetail * (0.30 + 0.24 * clarityMask));
            rgb = clamp(rgb, 0.0, 1.0);

            // Stage 3: Vivo ZEISS Natural Color Chromatic Calibration
            mat3 vivoMatrix = mat3(
                1.025, -0.010, -0.005,
                -0.008,  1.020, -0.005,
                -0.005, -0.010,  1.030
            );
            rgb = clamp(vivoMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            // Stage 4: Controlled Highlights (Knee compression above 0.70)
            if (luma > 0.70) {
                float knee = (luma - 0.70) / 0.30;
                rgb -= vec3(knee * knee * 0.16);
            }

            // Clean & Detailed Shadows (Pinned black point at 0.0 prevents milkiness)
            float shadowT = smoothstep(0.04, 0.30, luma) * (1.0 - smoothstep(0.30, 0.55, luma));
            rgb += vec3(shadowT * 0.065);

            // Stage 5: Chromatic Micro-Contrast & Texture Clarity
            float midLuma = (rgb.r + rgb.g + rgb.b) / 3.0;
            rgb += (rgb - vec3(midLuma)) * (clarityMask * 0.18);

            // Stage 6: Natural Tonal Slope & Balanced Saturation
            rgb = (rgb - 0.48) * 1.09 + 0.48;
            float vLuma = dot(rgb, lumaWeights);
            rgb = mix(vec3(vLuma), rgb, 1.085);

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

            // Stage 1: ZEISS T* Anti-Flare Linearization & Optical Transmittance
            rgb = pow(max(rgb - 0.003, 0.0) / 0.997, vec3(1.05)) * 1.04;
            rgb = clamp(rgb, 0.0, 1.0);

            vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
            float luma = dot(rgb, lumaWeights);

            // Stage 2: 5-Tap ZEISS T* 3D Spatial Micro-Contrast & Fine Texture Extraction
            vec2 texel = max(uTexelSize, vec2(1.0 / 1920.0, 1.0 / 1080.0)) * 1.35;
            vec3 cN = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0, -texel.y), 0.0, 1.0)).rgb;
            vec3 cS = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0,  texel.y), 0.0, 1.0)).rgb;
            vec3 cE = texture2D(sTexture, clamp(vTextureCoord + vec2( texel.x, 0.0), 0.0, 1.0)).rgb;
            vec3 cW = texture2D(sTexture, clamp(vTextureCoord + vec2(-texel.x, 0.0), 0.0, 1.0)).rgb;
            vec3 neighborMean = (cN + cS + cE + cW) * 0.25;
            float neighborLuma = dot(neighborMean, lumaWeights);
            float microDetail = clamp(luma - neighborLuma, -0.08, 0.08);
            float clarityMask = clamp(1.0 - abs(luma - 0.50) * 2.0, 0.0, 1.0);
            rgb += vec3(microDetail * (0.30 + 0.24 * clarityMask));
            rgb = clamp(rgb, 0.0, 1.0);

            // Stage 3: Vivo ZEISS Natural Color Chromatic Calibration
            mat3 vivoMatrix = mat3(
                1.025, -0.010, -0.005,
                -0.008,  1.020, -0.005,
                -0.005, -0.010,  1.030
            );
            rgb = clamp(vivoMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            // Stage 4: Controlled Highlights & Clean Shadows
            if (luma > 0.70) {
                float knee = (luma - 0.70) / 0.30;
                rgb -= vec3(knee * knee * 0.16);
            }

            float shadowT = smoothstep(0.04, 0.30, luma) * (1.0 - smoothstep(0.30, 0.55, luma));
            rgb += vec3(shadowT * 0.065);

            // Stage 5: Chromatic Micro-Contrast & Texture Clarity
            float midLuma = (rgb.r + rgb.g + rgb.b) / 3.0;
            rgb += (rgb - vec3(midLuma)) * (clarityMask * 0.18);

            // Stage 6: Natural Tonal Slope & Balanced Saturation
            rgb = (rgb - 0.48) * 1.09 + 0.48;
            float vLuma = dot(rgb, lumaWeights);
            rgb = mix(vec3(vLuma), rgb, 1.085);

            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    override fun computeColorMatrix(): ColorMatrix {
        val mat = ColorMatrix(floatArrayOf(
            1.04f, 0.00f, 0.00f, 0f, 2f,
            0.00f, 1.04f, 0.00f, 0f, 2f,
            0.00f, 0.00f, 1.05f, 0f, 3f,
            0f, 0f, 0f, 1f, 0f
        ))
        val sat = ColorMatrix().apply { setSaturation(1.08f) }
        mat.postConcat(sat)
        return mat
    }
}
