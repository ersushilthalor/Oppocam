package com.example.camera.videopipeline

import android.graphics.ColorMatrix

/**
 * Dedicated iPhone Video Processing Pipeline.
 *
 * Implements Apple Smart HDR computational video philosophy:
 * - Ultra-realistic natural colors with signature ambient daylight warmth
 * - Dedicated subject separation & depth enhancement in midtone luminance (0.30 - 0.65)
 * - Wide dynamic range with smooth shoulder highlight compression
 * - Deep, detailed shadows that retain contrast (strictly avoiding flat/log appearance)
 * - Protected melanin and peach skin tones
 * - Realistic local contrast and clean tonal gradations
 */
class IPhoneVideoPipeline : BaseVideoPipeline(VideoPipelineType.IPHONE) {

    override fun getAgslShaderCode(): String = """
        uniform shader uContent;
        uniform float2 uResolution;

        vec4 main(float2 fragCoord) {
            vec4 src = uContent.eval(fragCoord);
            vec3 rgb = src.rgb;

            // 1. Rec.709 Photometric Luminance
            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));

            // 2. Apple Chromatic Adaptation: organic warmth, rich melanin undertones, accurate sky
            mat3 appleMatrix = mat3(
                1.040, -0.015, -0.010,
                -0.008, 1.025, -0.005,
                -0.018, -0.010, 0.985
            );
            rgb = clamp(appleMatrix * rgb, 0.0, 1.0);

            // 3. Apple Smart HDR Highlight Retention:
            // Smooth shoulder roll-off prevents harsh clipping of specular highlights & clouds
            float hl = max(0.0, luma - 0.65);
            float hlCompression = hl * hl * 0.38;
            rgb -= vec3(hlCompression);

            // 4. Foreground / Subject Separation:
            // Midtones (0.30 - 0.65) where human subjects/faces reside are gently elevated with micro-contrast
            float subjectDist = abs(luma - 0.48);
            float subjectMask = clamp(1.0 - 4.0 * subjectDist * subjectDist, 0.0, 1.0);
            rgb += vec3(subjectMask * 0.055);

            // 5. Detailed Shadows with Contrast (avoids flat log look while revealing shadow texture)
            float shadowT = smoothstep(0.05, 0.30, luma) * (1.0 - smoothstep(0.30, 0.55, luma));
            rgb += vec3(shadowT * 0.050);

            // 6. Realistic Local Contrast (S-curve centered at 0.46)
            rgb = (rgb - 0.46) * 1.07 + 0.46;

            // 7. Natural Skin Tone Protection
            float isSkin = clamp((rgb.r - rgb.b) * 2.2, 0.0, 1.0) * clamp((rgb.g - rgb.b) * 1.4, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(1.015, 1.000, 0.985), isSkin * 0.30);

            // 8. Controlled Vibrance: Clean, natural color fidelity
            float newLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            rgb = mix(vec3(newLuma), rgb, 1.055);

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

            mat3 appleMatrix = mat3(
                1.040, -0.015, -0.010,
                -0.008, 1.025, -0.005,
                -0.018, -0.010, 0.985
            );
            rgb = clamp(appleMatrix * rgb, 0.0, 1.0);

            float hl = max(0.0, luma - 0.65);
            float hlCompression = hl * hl * 0.38;
            rgb -= vec3(hlCompression);

            float subjectDist = abs(luma - 0.48);
            float subjectMask = clamp(1.0 - 4.0 * subjectDist * subjectDist, 0.0, 1.0);
            rgb += vec3(subjectMask * 0.055);

            float shadowT = smoothstep(0.05, 0.30, luma) * (1.0 - smoothstep(0.30, 0.55, luma));
            rgb += vec3(shadowT * 0.050);

            rgb = (rgb - 0.46) * 1.07 + 0.46;

            float isSkin = clamp((rgb.r - rgb.b) * 2.2, 0.0, 1.0) * clamp((rgb.g - rgb.b) * 1.4, 0.0, 1.0);
            rgb = mix(rgb, rgb * vec3(1.015, 1.000, 0.985), isSkin * 0.30);

            float newLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            rgb = mix(vec3(newLuma), rgb, 1.055);

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
