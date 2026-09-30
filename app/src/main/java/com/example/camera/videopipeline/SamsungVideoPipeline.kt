package com.example.camera.videopipeline

import android.graphics.ColorMatrix

/**
 * Dedicated Samsung Video Processing Pipeline.
 *
 * Implements Samsung Super HDR / Vivid Pro video philosophy:
 * - Punchy, vibrant colors with distinct chromatic separation
 * - Lifted shadows and bright midtones for a high-energy, luminous image
 * - Good HDR and dynamic range with rich, non-clipping highlights
 * - Strong local contrast across mid-frequencies
 * - Protected skin tones against oversaturation
 */
class SamsungVideoPipeline : BaseVideoPipeline(VideoPipelineType.SAMSUNG) {

    override fun getAgslShaderCode(): String = """
        uniform shader uContent;
        uniform float2 uResolution;

        vec4 main(float2 fragCoord) {
            vec4 src = uContent.eval(fragCoord);
            vec3 rgb = src.rgb;

            // 1. Rec.709 Photometric Luminance
            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));

            // 2. Samsung Vivid Chromatic Adaptation: punchy skies, deep foliage, vibrant warm tones
            mat3 samsungMatrix = mat3(
                1.065, -0.030, -0.020,
                -0.020,  1.055, -0.015,
                -0.015, -0.025,  1.080
            );
            rgb = clamp(samsungMatrix * rgb, 0.0, 1.0);

            // 3. Lifted Shadows & Bright Midtones:
            // High shadow toe expansion reveals dark details without raising true black
            float shadowToe = (1.0 - smoothstep(0.02, 0.45, luma));
            float shadowLift = shadowToe * shadowToe * 0.11;

            // Bright midtones: Exposure gain in midtone band for a luminous image
            float midtoneGain = sin(clamp(luma, 0.0, 1.0) * 3.14159) * 0.085;
            rgb += vec3(shadowLift + midtoneGain);

            // 4. Strong Local Contrast: S-curve with elevated slope
            rgb = (rgb - 0.46) * 1.14 + 0.46;

            // 5. Rich Highlights without excessive digital clipping:
            float hlThreshold = 0.72;
            if (luma > hlThreshold) {
                float excess = (luma - hlThreshold) / 0.28;
                rgb -= vec3(excess * excess * 0.12);
            }

            // 6. Punchy Color Separation & Natural Skin Preservation:
            float newLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            float skinDist = max(0.0, rgb.r - max(rgb.g, rgb.b));
            float isSkin = smoothstep(0.05, 0.25, skinDist);
            float satFactor = mix(1.20, 1.07, isSkin);
            rgb = mix(vec3(newLuma), rgb, satFactor);

            return vec4(clamp(rgb, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    override fun getGlFragmentShaderCode(): String = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;

        void main() {
            vec4 src = texture2D(sTexture, vTextureCoord);
            vec3 rgb = src.rgb;

            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));

            mat3 samsungMatrix = mat3(
                1.065, -0.030, -0.020,
                -0.020,  1.055, -0.015,
                -0.015, -0.025,  1.080
            );
            rgb = clamp(samsungMatrix * rgb, 0.0, 1.0);

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

            float newLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            float skinDist = max(0.0, rgb.r - max(rgb.g, rgb.b));
            float isSkin = smoothstep(0.05, 0.25, skinDist);
            float satFactor = mix(1.20, 1.07, isSkin);
            rgb = mix(vec3(newLuma), rgb, satFactor);

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
