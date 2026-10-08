package com.example.camera.engine

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import com.example.camera.model.VideoAdjustments
import kotlin.math.pow

/**
 * High-performance Video Adjustments Pipeline for Normal Video Mode.
 * Unifies the parameter mathematical transformation across live viewfinder preview
 * (GPU AGSL RuntimeShader uniforms with zero per-frame CPU allocations)
 * and hardware video recording export (VideoMirrorTranscoder OpenGL shader).
 */
object VideoAdjustmentsPipeline {

    /**
     * Unified AGSL Shader Code for Android 13+ (API 33+) including Android 16.
     * Evaluates exact luminance-based tonal masks for Highlights and Shadows,
     * tone curves, color balance, and spatial GPU effects (Vignette, Grain, Soft Light, Bloom, Flash, Halation)
     * completely on the GPU with zero CPU Canvas overhead.
     */
    val AGSL_VIDEO_ADJUSTMENTS_SHADER: String = """
        uniform shader uContent;
        uniform float2 uResolution;
        uniform float uTime;
        uniform float uExposure;
        uniform float uTonality;
        uniform float uContrast;
        uniform float uSaturation;
        uniform float uColorVibrance;
        uniform float uHighlights;
        uniform float uShadows;
        uniform float uTemperature;
        uniform float uTint;
        uniform float uCurveBlacks;
        uniform float uCurveShadows;
        uniform float uCurveMidtones;
        uniform float uCurveHighlights;
        uniform float uCurveWhites;
        uniform float uColorBalanceR;
        uniform float uColorBalanceG;
        uniform float uColorBalanceB;
        uniform float uVignette;
        uniform float uGrain;
        uniform float uSoftLight;
        uniform float uBloom;
        uniform float uFlash;
        uniform float uHalation;

        vec4 main(float2 fragCoord) {
            vec4 color = uContent.eval(fragCoord);
            vec3 rgb = color.rgb;
            vec2 uv = (uResolution.x > 0.0 && uResolution.y > 0.0) ? (fragCoord / uResolution) : vec2(0.5, 0.5);

            // 1. Rec.709 Luminance for accurate tonal separation
            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));

            // 2. Luminance-based Tonal Masks (Smoothstep parabolic masks)
            // Shadows: affects only darker regions (luma < 0.50), tapers to 0 at midtones
            float shadowT = 1.0 - smoothstep(0.0, 0.50, luma);
            float shadowMask = shadowT * shadowT;
            float totalShadows = uShadows + uCurveShadows;
            float shadowAdjustment = (totalShadows / 100.0) * 0.35 * shadowMask;

            // Highlights: affects only brighter regions (luma > 0.45), tapers to 0 below midtones
            float hlT = smoothstep(0.45, 1.0, luma);
            float hlMask = hlT * hlT;
            float totalHighlights = uHighlights + uCurveHighlights;
            float hlAdjustment = (totalHighlights / 100.0) * 0.35 * hlMask;

            // Curve Blacks (< 0.25) & Whites (> 0.75)
            float blackT = 1.0 - smoothstep(0.0, 0.25, luma);
            float blackAdjustment = (uCurveBlacks / 100.0) * 0.25 * (blackT * blackT);

            float whiteT = smoothstep(0.75, 1.0, luma);
            float whiteAdjustment = (uCurveWhites / 100.0) * 0.25 * (whiteT * whiteT);

            // Curve Midtones: bell curve centered at 0.5
            float midDist = abs(luma - 0.5);
            float midMask = clamp(1.0 - 4.0 * midDist * midDist, 0.0, 1.0);
            float midAdjustment = (uCurveMidtones / 100.0) * 0.25 * midMask;

            rgb += vec3(shadowAdjustment + hlAdjustment + blackAdjustment + whiteAdjustment + midAdjustment);

            // 3. Tonality (smooth global tone shift)
            rgb += vec3((uTonality / 100.0) * 0.10);

            // 4. Exposure (photometric gain 2^(EV * 0.45))
            if (abs(uExposure) > 0.001) {
                rgb *= pow(2.0, uExposure * 0.45);
            }

            // 5. Contrast (S-curve around mid-gray 0.5)
            if (abs(uContrast) > 0.001) {
                float c = 1.0 + (uContrast / 100.0) * 0.65;
                rgb = (rgb - 0.5) * c + 0.5;
            }

            // 6. White Balance (Temperature & Tint)
            if (abs(uTemperature) > 0.001 || abs(uTint) > 0.001) {
                float tFactor = (uTemperature / 100.0) * 0.22;
                float tintFactor = (uTint / 100.0) * 0.18;
                rgb.r *= (1.0 + tFactor) * (1.0 + tintFactor * 0.5);
                rgb.g *= (1.0 - tintFactor);
                rgb.b *= (1.0 - tFactor) * (1.0 + tintFactor * 0.5);
            }

            // 7. Saturation & Vibrance
            float totalSat = uSaturation + (uColorVibrance * 0.65);
            if (abs(totalSat) > 0.001) {
                float newLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                float s = max(0.0, 1.0 + (totalSat / 100.0));
                rgb = mix(vec3(newLuma), rgb, s);
            }

            // 8. Color Balance (R-C, G-M, B-Y)
            rgb.r += (uColorBalanceR / 100.0) * 0.08;
            rgb.g += (uColorBalanceG / 100.0) * 0.08;
            rgb.b += (uColorBalanceB / 100.0) * 0.08;

            // 9. Spatial: Vignette
            if (uVignette > 0.001) {
                float d = length(uv - 0.5);
                float vFactor = 1.0 - smoothstep(0.35, 0.85, d) * (uVignette / 100.0) * 0.92;
                rgb *= vFactor;
            }

            // 10. Spatial: Film Grain (100% GPU procedural noise, 0 CPU loops)
            if (uGrain > 0.001) {
                float noise = (fract(sin(dot(uv * 1234.56 + uTime, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) * (uGrain / 100.0) * 0.16;
                rgb = clamp(rgb + vec3(noise), 0.0, 1.0);
            }

            // 11. Spatial: Soft Light
            if (uSoftLight > 0.001) {
                vec3 softGlow = vec3(0.98, 0.95, 0.90) * (uSoftLight / 100.0) * 0.12;
                rgb = clamp(rgb + softGlow, 0.0, 1.0);
            }

            // 12. Spatial: Bloom, Halation, Flash
            if (uBloom > 0.001) {
                float bDist = length(uv - vec2(0.5, 0.42));
                float bFactor = (1.0 - smoothstep(0.0, 0.65, bDist)) * (uBloom / 100.0) * 0.15;
                rgb += vec3(1.0, 0.85, 0.3) * bFactor;
            }
            if (uHalation > 0.001) {
                float hDist = length(uv - 0.5);
                float hFactor = smoothstep(0.35, 0.85, hDist) * (uHalation / 100.0) * 0.15;
                rgb.r += hFactor;
            }
            if (uFlash > 0.001) {
                float yDist = abs(uv.y - 0.48);
                float fStreak = (1.0 - smoothstep(0.0, 0.03, yDist)) * (uFlash / 100.0) * 0.35;
                rgb += vec3(0.6, 0.8, 1.0) * fStreak;
            }

            return vec4(clamp(rgb, 0.0, 1.0), color.a);
        }
    """.trimIndent()

    /**
     * Unified OpenGL ES 2.0 Fragment Shader Code for real-time live video recording & preview split.
     * Evaluates the identical mathematical adjustments completely on GPU hardware with zero CPU allocations.
     * If adjustments are default (uBypassAdjustments == 1), executes single texture2D lookup with negligible overhead.
     */
    val GLES_VIDEO_ADJUSTMENTS_FRAGMENT_SHADER: String = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        uniform vec2 uResolution;
        uniform float uTime;
        uniform int uBypassAdjustments;

        uniform float uExposure;
        uniform float uTonality;
        uniform float uContrast;
        uniform float uSaturation;
        uniform float uColorVibrance;
        uniform float uHighlights;
        uniform float uShadows;
        uniform float uTemperature;
        uniform float uTint;
        uniform float uClarity;
        uniform float uSharpness;

        uniform float uCurveBlacks;
        uniform float uCurveShadows;
        uniform float uCurveMidtones;
        uniform float uCurveHighlights;
        uniform float uCurveWhites;

        uniform float uColorBalanceR;
        uniform float uColorBalanceG;
        uniform float uColorBalanceB;

        uniform float uVignette;
        uniform float uGrain;
        uniform float uSoftLight;
        uniform float uBloom;
        uniform float uFlash;
        uniform float uHalation;
        uniform float uMicroContrast;

        void main() {
            vec4 color = texture2D(sTexture, vTextureCoord);
            if (uBypassAdjustments == 1) {
                gl_FragColor = color;
                return;
            }

            vec3 rgb = color.rgb;
            vec2 uv = (uResolution.x > 0.0 && uResolution.y > 0.0) ? vTextureCoord : vec2(0.5, 0.5);

            // Clarity / Sharpness / MicroContrast convolution
            if (abs(uClarity) > 0.001 || uSharpness > 0.001 || uMicroContrast > 0.001) {
                vec2 texel = vec2(1.0 / max(uResolution.x, 1.0), 1.0 / max(uResolution.y, 1.0));
                vec3 cUp = texture2D(sTexture, vTextureCoord + vec2(0.0, -texel.y)).rgb;
                vec3 cDown = texture2D(sTexture, vTextureCoord + vec2(0.0, texel.y)).rgb;
                vec3 cLeft = texture2D(sTexture, vTextureCoord + vec2(-texel.x, 0.0)).rgb;
                vec3 cRight = texture2D(sTexture, vTextureCoord + vec2(texel.x, 0.0)).rgb;
                vec3 laplacian = (cUp + cDown + cLeft + cRight) * 0.25 - rgb;
                float totalSharpness = (uSharpness * 0.006) + (uClarity * 0.005) + (uMicroContrast * 0.005);
                rgb = clamp(rgb - laplacian * totalSharpness, 0.0, 1.0);
            }

            // 1. Rec.709 Luminance for accurate tonal separation
            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));

            // 2. Luminance-based Tonal Masks (Smoothstep parabolic masks)
            float shadowT = 1.0 - smoothstep(0.0, 0.50, luma);
            float shadowMask = shadowT * shadowT;
            float totalShadows = uShadows + uCurveShadows;
            float shadowAdjustment = (totalShadows / 100.0) * 0.35 * shadowMask;

            float hlT = smoothstep(0.45, 1.0, luma);
            float hlMask = hlT * hlT;
            float totalHighlights = uHighlights + uCurveHighlights;
            float hlAdjustment = (totalHighlights / 100.0) * 0.35 * hlMask;

            float blackT = 1.0 - smoothstep(0.0, 0.25, luma);
            float blackAdjustment = (uCurveBlacks / 100.0) * 0.25 * (blackT * blackT);

            float whiteT = smoothstep(0.75, 1.0, luma);
            float whiteAdjustment = (uCurveWhites / 100.0) * 0.25 * (whiteT * whiteT);

            float midDist = abs(luma - 0.5);
            float midMask = clamp(1.0 - 4.0 * midDist * midDist, 0.0, 1.0);
            float midAdjustment = (uCurveMidtones / 100.0) * 0.25 * midMask;

            rgb += vec3(shadowAdjustment + hlAdjustment + blackAdjustment + whiteAdjustment + midAdjustment);

            // 3. Tonality
            rgb += vec3((uTonality / 100.0) * 0.10);

            // 4. Exposure
            if (abs(uExposure) > 0.001) {
                rgb *= pow(2.0, uExposure * 0.45);
            }

            // 5. Contrast
            if (abs(uContrast) > 0.001) {
                float c = 1.0 + (uContrast / 100.0) * 0.65;
                rgb = (rgb - 0.5) * c + 0.5;
            }

            // 6. White Balance (Temperature & Tint)
            if (abs(uTemperature) > 0.001 || abs(uTint) > 0.001) {
                float tFactor = (uTemperature / 100.0) * 0.22;
                float tintFactor = (uTint / 100.0) * 0.18;
                rgb.r *= (1.0 + tFactor) * (1.0 + tintFactor * 0.5);
                rgb.g *= (1.0 - tintFactor);
                rgb.b *= (1.0 - tFactor) * (1.0 + tintFactor * 0.5);
            }

            // 7. Saturation & Vibrance
            float totalSat = uSaturation + (uColorVibrance * 0.65);
            if (abs(totalSat) > 0.001) {
                float newLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                float s = max(0.0, 1.0 + (totalSat / 100.0));
                rgb = mix(vec3(newLuma), rgb, s);
            }

            // 8. Color Balance (R-C, G-M, B-Y)
            rgb.r += (uColorBalanceR / 100.0) * 0.08;
            rgb.g += (uColorBalanceG / 100.0) * 0.08;
            rgb.b += (uColorBalanceB / 100.0) * 0.08;

            // 9. Spatial: Vignette
            if (uVignette > 0.001) {
                float d = length(uv - 0.5);
                float vFactor = 1.0 - smoothstep(0.35, 0.85, d) * (uVignette / 100.0) * 0.92;
                rgb *= vFactor;
            }

            // 10. Spatial: Film Grain
            if (uGrain > 0.001) {
                float noise = (fract(sin(dot(uv * 1234.56 + uTime, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) * (uGrain / 100.0) * 0.16;
                rgb = clamp(rgb + vec3(noise), 0.0, 1.0);
            }

            // 11. Spatial: Soft Light
            if (uSoftLight > 0.001) {
                vec3 softGlow = vec3(0.98, 0.95, 0.90) * (uSoftLight / 100.0) * 0.12;
                rgb = clamp(rgb + softGlow, 0.0, 1.0);
            }

            // 12. Spatial: Bloom, Halation, Flash
            if (uBloom > 0.001) {
                float bDist = length(uv - vec2(0.5, 0.42));
                float bFactor = (1.0 - smoothstep(0.0, 0.65, bDist)) * (uBloom / 100.0) * 0.15;
                rgb += vec3(1.0, 0.85, 0.3) * bFactor;
            }
            if (uHalation > 0.001) {
                float hDist = length(uv - 0.5);
                float hFactor = smoothstep(0.35, 0.85, hDist) * (uHalation / 100.0) * 0.15;
                rgb.r += hFactor;
            }
            if (uFlash > 0.001) {
                float yDist = abs(uv.y - 0.48);
                float fStreak = (1.0 - smoothstep(0.0, 0.03, yDist)) * (uFlash / 100.0) * 0.35;
                rgb += vec3(0.6, 0.8, 1.0) * fStreak;
            }

            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), color.a);
        }
    """.trimIndent()

    class VideoAdjustmentsShaderHandles(programId: Int) {
        val uBypassAdjustmentsHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uBypassAdjustments")
        val uResolutionHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uResolution")
        val uTimeHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uTime")
        val uExposureHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uExposure")
        val uTonalityHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uTonality")
        val uContrastHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uContrast")
        val uSaturationHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uSaturation")
        val uColorVibranceHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uColorVibrance")
        val uHighlightsHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uHighlights")
        val uShadowsHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uShadows")
        val uTemperatureHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uTemperature")
        val uTintHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uTint")
        val uClarityHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uClarity")
        val uSharpnessHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uSharpness")
        val uCurveBlacksHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uCurveBlacks")
        val uCurveShadowsHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uCurveShadows")
        val uCurveMidtonesHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uCurveMidtones")
        val uCurveHighlightsHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uCurveHighlights")
        val uCurveWhitesHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uCurveWhites")
        val uColorBalanceRHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uColorBalanceR")
        val uColorBalanceGHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uColorBalanceG")
        val uColorBalanceBHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uColorBalanceB")
        val uVignetteHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uVignette")
        val uGrainHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uGrain")
        val uSoftLightHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uSoftLight")
        val uBloomHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uBloom")
        val uFlashHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uFlash")
        val uHalationHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uHalation")
        val uMicroContrastHandle: Int = android.opengl.GLES20.glGetUniformLocation(programId, "uMicroContrast")
    }

    fun bindUniforms(
        handles: VideoAdjustmentsShaderHandles,
        adjustments: VideoAdjustments?,
        width: Int,
        height: Int,
        timeSec: Float
    ) {
        if (adjustments == null || adjustments.isDefault) {
            android.opengl.GLES20.glUniform1i(handles.uBypassAdjustmentsHandle, 1)
            return
        }

        android.opengl.GLES20.glUniform1i(handles.uBypassAdjustmentsHandle, 0)
        android.opengl.GLES20.glUniform2f(handles.uResolutionHandle, width.toFloat().coerceAtLeast(1f), height.toFloat().coerceAtLeast(1f))
        android.opengl.GLES20.glUniform1f(handles.uTimeHandle, timeSec % 100.0f)
        android.opengl.GLES20.glUniform1f(handles.uExposureHandle, adjustments.exposure)
        android.opengl.GLES20.glUniform1f(handles.uTonalityHandle, adjustments.tonality)
        android.opengl.GLES20.glUniform1f(handles.uContrastHandle, adjustments.contrast)
        android.opengl.GLES20.glUniform1f(handles.uSaturationHandle, adjustments.saturation)
        android.opengl.GLES20.glUniform1f(handles.uColorVibranceHandle, adjustments.colorVibrance)
        android.opengl.GLES20.glUniform1f(handles.uHighlightsHandle, adjustments.highlights)
        android.opengl.GLES20.glUniform1f(handles.uShadowsHandle, adjustments.shadows)
        android.opengl.GLES20.glUniform1f(handles.uTemperatureHandle, adjustments.temperature)
        android.opengl.GLES20.glUniform1f(handles.uTintHandle, adjustments.tint)
        android.opengl.GLES20.glUniform1f(handles.uClarityHandle, adjustments.clarity)
        android.opengl.GLES20.glUniform1f(handles.uSharpnessHandle, adjustments.sharpness)
        android.opengl.GLES20.glUniform1f(handles.uCurveBlacksHandle, adjustments.curveBlacks)
        android.opengl.GLES20.glUniform1f(handles.uCurveShadowsHandle, adjustments.curveShadows)
        android.opengl.GLES20.glUniform1f(handles.uCurveMidtonesHandle, adjustments.curveMidtones)
        android.opengl.GLES20.glUniform1f(handles.uCurveHighlightsHandle, adjustments.curveHighlights)
        android.opengl.GLES20.glUniform1f(handles.uCurveWhitesHandle, adjustments.curveWhites)
        android.opengl.GLES20.glUniform1f(handles.uColorBalanceRHandle, adjustments.colorBalanceR)
        android.opengl.GLES20.glUniform1f(handles.uColorBalanceGHandle, adjustments.colorBalanceG)
        android.opengl.GLES20.glUniform1f(handles.uColorBalanceBHandle, adjustments.colorBalanceB)
        android.opengl.GLES20.glUniform1f(handles.uVignetteHandle, adjustments.vignette)
        val totalGrain = adjustments.grain + adjustments.textureFilmGrain
        android.opengl.GLES20.glUniform1f(handles.uGrainHandle, totalGrain)
        android.opengl.GLES20.glUniform1f(handles.uSoftLightHandle, adjustments.lightFxSoftLight)
        android.opengl.GLES20.glUniform1f(handles.uBloomHandle, adjustments.lightFxBloom)
        android.opengl.GLES20.glUniform1f(handles.uFlashHandle, adjustments.lightFxFlash)
        android.opengl.GLES20.glUniform1f(handles.uHalationHandle, adjustments.textureHalation)
        android.opengl.GLES20.glUniform1f(handles.uMicroContrastHandle, adjustments.textureMicroContrast)
    }

    val isGpuShaderSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @Volatile
    private var runtimeShaderInstance: Any? = null
    @Volatile
    private var cachedRenderEffect: RenderEffect? = null
    private var fallbackPaint: Paint? = null
    private var frameTimeCounter: Float = 0f

    /**
     * Applies video adjustments directly to the target View (TextureView preview) in real time.
     * On Android 13+ (API 33+), uses GPU RuntimeShader uniforms with ZERO allocations on slider movement.
     * On API < 33, falls back to hardware layer ColorMatrix.
     */
    fun applyToView(view: View, adjustments: VideoAdjustments?) {
        if (adjustments == null || adjustments.isDefault) {
            clearAdjustments(view)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applyGpuShader(view, adjustments)
        } else {
            val matrix = computeColorMatrix(adjustments)
            if (matrix != null) {
                val paint = fallbackPaint ?: Paint().also { fallbackPaint = it }
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            } else {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            view.invalidate()
        }
    }

    private var currentAttachedView: java.lang.ref.WeakReference<View>? = null

    /**
     * Clears all shader/layer effects from the View.
     */
    fun clearAdjustments(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(null)
            } catch (ignored: Throwable) {}
        }
        if (view.layerType != View.LAYER_TYPE_NONE) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        if (currentAttachedView?.get() == view) {
            currentAttachedView = null
        }
    }

    private fun applyGpuShader(view: View, adjustments: VideoAdjustments) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

            var shader = runtimeShaderInstance as? RuntimeShader
            if (shader == null) {
                shader = RuntimeShader(AGSL_VIDEO_ADJUSTMENTS_SHADER)
                runtimeShaderInstance = shader
            }

            // Update resolution uniform
            val w = view.width.toFloat().coerceAtLeast(1.0f)
            val h = view.height.toFloat().coerceAtLeast(1.0f)
            shader.setFloatUniform("uResolution", w, h)

            // Update dynamic time seed for film grain GPU noise
            frameTimeCounter = (frameTimeCounter + 0.016f) % 100.0f
            shader.setFloatUniform("uTime", frameTimeCounter)

            // Update all adjustment uniforms directly on the GPU
            shader.setFloatUniform("uExposure", adjustments.exposure)
            shader.setFloatUniform("uTonality", adjustments.tonality)
            shader.setFloatUniform("uContrast", adjustments.contrast)
            shader.setFloatUniform("uSaturation", adjustments.saturation)
            shader.setFloatUniform("uColorVibrance", adjustments.colorVibrance)
            shader.setFloatUniform("uHighlights", adjustments.highlights)
            shader.setFloatUniform("uShadows", adjustments.shadows)
            shader.setFloatUniform("uTemperature", adjustments.temperature)
            shader.setFloatUniform("uTint", adjustments.tint)
            shader.setFloatUniform("uCurveBlacks", adjustments.curveBlacks)
            shader.setFloatUniform("uCurveShadows", adjustments.curveShadows)
            shader.setFloatUniform("uCurveMidtones", adjustments.curveMidtones)
            shader.setFloatUniform("uCurveHighlights", adjustments.curveHighlights)
            shader.setFloatUniform("uCurveWhites", adjustments.curveWhites)
            shader.setFloatUniform("uColorBalanceR", adjustments.colorBalanceR)
            shader.setFloatUniform("uColorBalanceG", adjustments.colorBalanceG)
            shader.setFloatUniform("uColorBalanceB", adjustments.colorBalanceB)
            shader.setFloatUniform("uVignette", adjustments.vignette)
            val totalGrain = adjustments.grain + adjustments.textureFilmGrain
            shader.setFloatUniform("uGrain", totalGrain)
            shader.setFloatUniform("uSoftLight", adjustments.lightFxSoftLight)
            shader.setFloatUniform("uBloom", adjustments.lightFxBloom)
            shader.setFloatUniform("uFlash", adjustments.lightFxFlash)
            shader.setFloatUniform("uHalation", adjustments.textureHalation)

            // Attach RenderEffect only once to avoid rebuilding the pipeline
            if (cachedRenderEffect == null || currentAttachedView?.get() != view) {
                val effect = RenderEffect.createRuntimeShaderEffect(shader, "uContent")
                cachedRenderEffect = effect
                currentAttachedView = java.lang.ref.WeakReference(view)
                view.setRenderEffect(effect)
            }

            // Ensure fallback layer is cleared when GPU shader is active
            if (view.layerType != View.LAYER_TYPE_NONE) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }

            view.invalidate()
        } catch (e: Exception) {
            // Fall back gracefully if AGSL is unavailable
            applyFallbackMatrix(view, adjustments)
        }
    }

    private fun applyFallbackMatrix(view: View, adjustments: VideoAdjustments) {
        val matrix = computeColorMatrix(adjustments)
        if (matrix != null) {
            val paint = fallbackPaint ?: Paint().also { fallbackPaint = it }
            paint.colorFilter = ColorMatrixColorFilter(matrix)
            view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    /**
     * Computes the 4x5 Android ColorMatrix for live preview fallback and video transcoding.
     * Returns null if all adjustments are at default (0).
     */
    fun computeColorMatrix(adjustments: VideoAdjustments?): ColorMatrix? {
        if (adjustments == null || adjustments.isDefault) return null

        val masterMatrix = ColorMatrix()
        var hasTransform = false

        // 1. Exposure & Tonality Transform
        val exp = adjustments.exposure
        val tonality = adjustments.tonality
        if (exp != 0.0f || tonality != 0f) {
            // Exposure factor: 2^(EV)
            val expGain = 2.0f.pow(exp * 0.45f)
            val toneShift = (tonality / 100f) * 25.0f

            val expMat = ColorMatrix(floatArrayOf(
                expGain, 0f, 0f, 0f, toneShift,
                0f, expGain, 0f, 0f, toneShift,
                0f, 0f, expGain, 0f, toneShift,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(expMat)
            hasTransform = true
        }

        // 2. Contrast & Curve Transform
        val contrast = adjustments.contrast
        val curveBlacks = adjustments.curveBlacks
        val curveWhites = adjustments.curveWhites
        val curveMidtones = adjustments.curveMidtones
        if (contrast != 0f || curveBlacks != 0f || curveWhites != 0f || curveMidtones != 0f) {
            val c = 1.0f + (contrast / 100f) * 0.65f
            val offset = 128f * (1.0f - c) + (curveWhites - curveBlacks) * 0.25f + (curveMidtones * 0.2f)

            val contrastMat = ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, offset,
                0f, c, 0f, 0f, offset,
                0f, 0f, c, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(contrastMat)
            hasTransform = true
        }

        // 3. Highlights & Shadows Linear Approximation for Fallback
        val highlights = adjustments.highlights
        val shadows = adjustments.shadows
        val curveHighlights = adjustments.curveHighlights
        val curveShadows = adjustments.curveShadows
        val totalHighlights = highlights + curveHighlights
        val totalShadows = shadows + curveShadows
        if (totalHighlights != 0f || totalShadows != 0f) {
            val highScale = 1.0f - (totalHighlights / 100f) * 0.20f
            val shadowLift = (totalShadows / 100f) * 28.0f

            val hlMat = ColorMatrix(floatArrayOf(
                highScale, 0f, 0f, 0f, shadowLift,
                0f, highScale, 0f, 0f, shadowLift,
                0f, 0f, highScale, 0f, shadowLift,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(hlMat)
            hasTransform = true
        }

        // 4. White Balance: Temperature & Tint
        val temp = adjustments.temperature
        val tint = adjustments.tint
        if (temp != 0f || tint != 0f) {
            val tFactor = temp / 100f
            val rTemp = 1.0f + tFactor * 0.22f
            val bTemp = 1.0f - tFactor * 0.22f

            val tintFactor = tint / 100f
            val gTint = 1.0f - tintFactor * 0.18f
            val rTint = 1.0f + tintFactor * 0.09f
            val bTint = 1.0f + tintFactor * 0.09f

            val rScale = rTemp * rTint
            val gScale = gTint
            val bScale = bTemp * bTint

            val wbMat = ColorMatrix(floatArrayOf(
                rScale, 0f, 0f, 0f, 0f,
                0f, gScale, 0f, 0f, 0f,
                0f, 0f, bScale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(wbMat)
            hasTransform = true
        }

        // 5. Saturation & Vibrance
        val sat = adjustments.saturation
        val vib = adjustments.colorVibrance
        val totalSat = sat + (vib * 0.65f)
        if (totalSat != 0f) {
            val s = (1.0f + (totalSat / 100f)).coerceAtLeast(0f)
            val satMat = ColorMatrix().apply { setSaturation(s) }
            masterMatrix.postConcat(satMat)
            hasTransform = true
        }

        // 6. Advanced Color Balance (R-C, G-M, B-Y)
        val balR = adjustments.colorBalanceR
        val balG = adjustments.colorBalanceG
        val balB = adjustments.colorBalanceB
        if (balR != 0f || balG != 0f || balB != 0f) {
            val rShift = (balR / 100f) * 20.0f
            val gShift = (balG / 100f) * 20.0f
            val bShift = (balB / 100f) * 20.0f

            val balMat = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, rShift,
                0f, 1f, 0f, 0f, gShift,
                0f, 0f, 1f, 0f, bShift,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(balMat)
            hasTransform = true
        }

        // 7. Light FX & Texture subtle tonal influence
        val halation = adjustments.textureHalation
        val softLight = adjustments.lightFxSoftLight
        if (halation > 0f || softLight > 0f) {
            val rGlow = (halation / 100f) * 8.0f
            val softGlow = (softLight / 100f) * 10.0f

            val fxMat = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, rGlow + softGlow,
                0f, 1f, 0f, 0f, softGlow * 0.7f,
                0f, 0f, 1f, 0f, softGlow * 0.5f,
                0f, 0f, 0f, 1f, 0f
            ))
            masterMatrix.postConcat(fxMat)
            hasTransform = true
        }

        return if (hasTransform) masterMatrix else null
    }

    /**
     * Checks if any spatial effect (Vignette, Grain, Light FX, Sharpness) is active.
     */
    fun hasSpatialEffects(adjustments: VideoAdjustments?): Boolean {
        if (adjustments == null) return false
        return adjustments.vignette > 0f ||
                adjustments.grain > 0f ||
                adjustments.textureFilmGrain > 0f ||
                adjustments.lightFxFlash > 0f ||
                adjustments.lightFxBloom > 0f ||
                adjustments.lightFxSoftLight > 0f ||
                adjustments.textureHalation > 0f ||
                adjustments.clarity != 0f ||
                adjustments.sharpness > 0f
    }
}
