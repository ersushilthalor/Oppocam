package com.example.camera.videopipeline

import android.graphics.ColorMatrix

/**
 * Dedicated Vivo Video Processing Pipeline.
 *
 * Implements Vivo Zeiss-inspired Ultra HDR video philosophy:
 * - Natural but rich colors with neutral chromatic calibration
 * - Strong HDR with bright exposure and controlled highlights
 * - Clean, deep, detailed shadows without noise or muddiness
 * - Strong micro-contrast and fine texture rendering for realistic scene depth
 * - Balanced saturation (1.08x) avoiding overcooked punchiness
 * - Flagship computational tone mapping with exceptional dynamic range
 */
class VivoVideoPipeline : BaseVideoPipeline(VideoPipelineType.VIVO) {

    override fun getAgslShaderCode(): String = """
        uniform shader uContent;
        uniform float2 uResolution;

        vec4 main(float2 fragCoord) {
            vec4 src = uContent.eval(fragCoord);
            vec3 rgb = src.rgb;

            // 1. Rec.709 Photometric Luminance
            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));

            // 2. Vivo Zeiss Natural Color Chromatic Calibration:
            // High chromatic accuracy with deep tonal fidelity, authentic color graduation
            mat3 vivoMatrix = mat3(
                1.025, -0.010, -0.005,
                -0.008,  1.020, -0.005,
                -0.005, -0.010,  1.030
            );
            rgb = clamp(vivoMatrix * rgb, 0.0, 1.0);

            // 3. Controlled Highlights:
            // Advanced knee compression above 0.70 keeping bright sky and streetlights contained
            if (luma > 0.70) {
                float knee = (luma - 0.70) / 0.30;
                rgb -= vec3(knee * knee * 0.16);
            }

            // 4. Clean & Detailed Shadows:
            // Pinned black point at 0.0 prevents milkiness while expanding shadow detail between 0.04 and 0.32
            float shadowT = smoothstep(0.04, 0.30, luma) * (1.0 - smoothstep(0.30, 0.55, luma));
            rgb += vec3(shadowT * 0.065);

            // 5. Strong Micro-Contrast & Fine Detail (Texture Clarity):
            // Emphasizes mid-frequency contrast, texture, and scene depth
            float clarityMask = clamp(1.0 - abs(luma - 0.50) * 2.0, 0.0, 1.0);
            float midLuma = (rgb.r + rgb.g + rgb.b) / 3.0;
            rgb += (rgb - vec3(midLuma)) * (clarityMask * 0.18);

            // Natural tonal slope
            rgb = (rgb - 0.48) * 1.09 + 0.48;

            // 6. Balanced Saturation:
            // Rich yet natural (1.08x), avoiding overcooked punchiness
            float vLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            rgb = mix(vec3(vLuma), rgb, 1.085);

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

            mat3 vivoMatrix = mat3(
                1.025, -0.010, -0.005,
                -0.008,  1.020, -0.005,
                -0.005, -0.010,  1.030
            );
            rgb = clamp(vivoMatrix * rgb, 0.0, 1.0);

            if (luma > 0.70) {
                float knee = (luma - 0.70) / 0.30;
                rgb -= vec3(knee * knee * 0.16);
            }

            float shadowT = smoothstep(0.04, 0.30, luma) * (1.0 - smoothstep(0.30, 0.55, luma));
            rgb += vec3(shadowT * 0.065);

            float clarityMask = clamp(1.0 - abs(luma - 0.50) * 2.0, 0.0, 1.0);
            float midLuma = (rgb.r + rgb.g + rgb.b) / 3.0;
            rgb += (rgb - vec3(midLuma)) * (clarityMask * 0.18);

            rgb = (rgb - 0.48) * 1.09 + 0.48;

            float vLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
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
