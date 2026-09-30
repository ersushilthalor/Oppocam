package com.example.camera.videopipeline

import android.graphics.ColorMatrix

/**
 * Dedicated Vivo Video Processing Pipeline.
 *
 * Pipeline Structure:
 * LOG -> Natural Rec.709 conversion -> Brand-specific processing -> Final output
 *
 * Character:
 * Rich + HDR + slightly lifted shadows + detailed + cinematic + natural.
 *
 * Targets:
 * - Rich and premium colors while remaining realistic
 * - Strong HDR and dynamic range with excellent highlight control
 * - Slightly lifted shadows for a more open and visible shadow region
 * - Shadow uplift is subtle and does NOT make the image look washed out or flat (true blacks anchored)
 * - Smooth tonal transitions without harsh steps
 * - Slightly richer color depth than iPhone
 * - Natural but detailed sharpening (fine micro-textures without halos)
 * - Strong local contrast and scene depth (ZEISS 3D pop)
 * - Cinematic-looking tonal rendering without becoming dark
 * - Natural skin tones with balanced undertones
 * - Bright, clean, and detailed final output
 */
class VivoVideoPipeline : BaseVideoPipeline(VideoPipelineType.VIVO) {

    override val stageParams: VideoPipelineStageParams = VideoPipelineStageParams(
        sensorExposureBiasEv = -0.05f,
        ispTonemapGamma = 1.16f,
        ispHighlightRollOff = 0.12f,
        bypassHalEdgeSharpening = true,
        highQualityTemporalDenoise = true,
        inputDeGamma = 1.04f,
        linearExposureGain = 1.03f,
        warmthShift = 0.002f,
        tintShift = 0.001f,
        highlightKneeThreshold = 0.68f,
        highlightCompressionStrength = 0.18f,
        shadowToeLimit = 0.28f,
        shadowLiftStrength = 0.052f,
        subjectMidtoneCenter = 0.48f,
        subjectSeparationLift = 0.050f,
        spatialSharpnessStrength = 0.28f,
        localMicroContrastStrength = 0.22f,
        detailRadiusTexels = 1.25f,
        haloProtectionLimit = 0.07f,
        colorMatrix3x3 = floatArrayOf(
            1.035f, -0.010f, -0.006f,
            -0.008f,  1.030f, -0.006f,
            -0.006f, -0.012f,  1.045f
        ),
        skinToneProtectionStrength = 0.35f,
        skinWarmthTargetR = 1.008f,
        skinWarmthTargetG = 1.002f,
        skinWarmthTargetB = 0.992f,
        skyBlueRetention = 0.22f,
        filmicContrastSlope = 1.085f,
        filmicContrastPivot = 0.47f,
        lumaWeightedVibrance = 0.06f,
        globalSaturation = 1.095f,
        blackPointFloor = 0.001f,
        whitePointCeiling = 0.998f,
        outputGamma = 0.97f
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

            // STAGE 3: Brand-Specific Vivo ZEISS Processing
            // 3a. ZEISS T* 3D Spatial Micro-Contrast & Fine Texture Extraction
            float2 stepPx = float2(1.25, 1.25);
            vec3 cN = uContent.eval(clamp(fragCoord + float2(0.0, -stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cS = uContent.eval(clamp(fragCoord + float2(0.0,  stepPx.y), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cE = uContent.eval(clamp(fragCoord + float2( stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 cW = uContent.eval(clamp(fragCoord + float2(-stepPx.x, 0.0), float2(0.0), max(uResolution - 1.0, float2(1.0)))).rgb;
            vec3 neighborMean = (cN + cS + cE + cW) * 0.25;
            float neighborLuma = dot(neighborMean, lumaWeights);
            float microDetail = clamp(luma - neighborLuma, -0.075, 0.075);
            float clarityMask = clamp(1.0 - abs(luma - 0.50) * 2.0, 0.0, 1.0);
            rgb += vec3(microDetail * (0.28 + 0.22 * clarityMask));
            rgb = clamp(rgb, 0.0, 1.0);

            // 3b. Vivo ZEISS Natural Color Chromatic Calibration (richer color depth than iPhone)
            mat3 vivoMatrix = mat3(
                1.035, -0.010, -0.006,
               -0.008,  1.030, -0.006,
               -0.006, -0.012,  1.045
            );
            rgb = clamp(vivoMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            // 3c. Controlled Highlights (Smooth knee compression above 0.68)
            if (luma > 0.68) {
                float knee = (luma - 0.68) / 0.32;
                rgb -= vec3(knee * knee * 0.18);
            }

            // 3d. Subtle Shadow Uplift (Anchored true black prevents washed-out or flat look)
            float shadowT = smoothstep(0.03, 0.26, luma) * (1.0 - smoothstep(0.26, 0.50, luma));
            rgb += vec3(shadowT * 0.052);

            // 3e. Chromatic Micro-Contrast & Texture Clarity
            float midLuma = (rgb.r + rgb.g + rgb.b) / 3.0;
            rgb += (rgb - vec3(midLuma)) * (clarityMask * 0.15);

            // 3f. Natural Skin Tone Fidelity & Rich Color Depth
            float isSkin = clamp((rgb.r - rgb.b) * 2.2, 0.0, 1.0) * clamp((rgb.g - rgb.b) * 1.4, 0.0, 1.0) * smoothstep(0.12, 0.70, luma);
            rgb = mix(rgb, rgb * vec3(1.008, 1.002, 0.992), isSkin * 0.30);

            // Cinematic Tonal Slope & Balanced Saturation
            rgb = (rgb - 0.47) * 1.085 + 0.47;
            float vLuma = dot(rgb, lumaWeights);
            float satFactor = mix(1.095, 1.045, isSkin);
            rgb = mix(vec3(vLuma), rgb, satFactor);

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

            // STAGE 3: Brand-Specific Vivo ZEISS Processing
            vec2 texel = max(uTexelSize, vec2(1.0 / 1920.0, 1.0 / 1080.0)) * 1.25;
            vec3 cN = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0, -texel.y), 0.0, 1.0)).rgb;
            vec3 cS = texture2D(sTexture, clamp(vTextureCoord + vec2(0.0,  texel.y), 0.0, 1.0)).rgb;
            vec3 cE = texture2D(sTexture, clamp(vTextureCoord + vec2( texel.x, 0.0), 0.0, 1.0)).rgb;
            vec3 cW = texture2D(sTexture, clamp(vTextureCoord + vec2(-texel.x, 0.0), 0.0, 1.0)).rgb;
            vec3 neighborMean = (cN + cS + cE + cW) * 0.25;
            float neighborLuma = dot(neighborMean, lumaWeights);
            float microDetail = clamp(luma - neighborLuma, -0.075, 0.075);
            float clarityMask = clamp(1.0 - abs(luma - 0.50) * 2.0, 0.0, 1.0);
            rgb += vec3(microDetail * (0.28 + 0.22 * clarityMask));
            rgb = clamp(rgb, 0.0, 1.0);

            mat3 vivoMatrix = mat3(
                1.035, -0.010, -0.006,
               -0.008,  1.030, -0.006,
               -0.006, -0.012,  1.045
            );
            rgb = clamp(vivoMatrix * rgb, 0.0, 1.0);
            luma = dot(rgb, lumaWeights);

            if (luma > 0.68) {
                float knee = (luma - 0.68) / 0.32;
                rgb -= vec3(knee * knee * 0.18);
            }

            float shadowT = smoothstep(0.03, 0.26, luma) * (1.0 - smoothstep(0.26, 0.50, luma));
            rgb += vec3(shadowT * 0.052);

            float midLuma = (rgb.r + rgb.g + rgb.b) / 3.0;
            rgb += (rgb - vec3(midLuma)) * (clarityMask * 0.15);

            float isSkin = clamp((rgb.r - rgb.b) * 2.2, 0.0, 1.0) * clamp((rgb.g - rgb.b) * 1.4, 0.0, 1.0) * smoothstep(0.12, 0.70, luma);
            rgb = mix(rgb, rgb * vec3(1.008, 1.002, 0.992), isSkin * 0.30);

            rgb = (rgb - 0.47) * 1.085 + 0.47;
            float vLuma = dot(rgb, lumaWeights);
            float satFactor = mix(1.095, 1.045, isSkin);
            rgb = mix(vec3(vLuma), rgb, satFactor);

            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    override fun computeColorMatrix(): ColorMatrix {
        val satMatrix = ColorMatrix().apply { setSaturation(1.095f) }
        val vivoColorMatrix = ColorMatrix(
            floatArrayOf(
                1.035f, -0.010f, -0.006f, 0f, 1.5f,
                -0.008f,  1.030f, -0.006f, 0f, 1.5f,
                -0.006f, -0.012f,  1.045f, 0f, 2.0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        vivoColorMatrix.postConcat(satMatrix)
        return vivoColorMatrix
    }
}
