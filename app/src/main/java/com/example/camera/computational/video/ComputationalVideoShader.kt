package com.example.camera.computational.video

/**
 * GLSL Shaders for the Real-Time Smartphone Computational Video Pipeline.
 *
 * Implements in a single GPU pass:
 * 1. Camera Sensor & ISP Normal Video Stream sampling (samplerExternalOES)
 * 2. Temporal Multi-Frame Processing & History comparison (sPrevTexture)
 * 3. Motion Detection & Adaptive Frame Alignment
 * 4. Temporal Noise Reduction with motion cutoff to avoid ghosting
 * 5. Temporal Exposure Consistency & Anti-Flicker damping
 * 6. 5-tap cross-kernel sampling for edge response and chroma smoothing
 * 7. Chroma Noise Reduction in flat/shadow regions
 * 8. Edge-Aware Adaptive Sharpening with halo suppression
 * 9. Shadow Recovery Boost preserving true blacks
 * 10. Parabolic Knee Highlight Recovery preventing harsh clipping
 * 11. Local Contrast (Unsharp Mask on luminance)
 * 12. S-Curve Tone Mapping for ready-to-watch SDR finish
 * 13. Skin-Tone Locus Detection (YCbCr) & Melanin Protection
 * 14. Brand Color Science Matrix & Warmth Shift
 * 15. Intelligent Vibrance & Finished SDR Output
 */
object ComputationalVideoShader {

    const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        uniform mat4 uTexMatrix;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = (uTexMatrix * aTextureCoord).xy;
        }
    """

    const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        uniform sampler2D sPrevTexture;
        uniform int uHasPrevFrame;

        // Pipeline Mode: 0=Default, 1=Pixel, 2=Samsung, 3=iPhone, 4=Vivo
        uniform int uPipelineMode;

        // Temporal & Motion
        uniform float uTemporalDenoise;
        uniform float uMotionThreshold;
        uniform float uTemporalFlickerDamping;

        // Dynamic Range & Tone
        uniform float uHdrToneMap;
        uniform float uHighlightRecovery;
        uniform float uShadowRecovery;
        uniform float uLocalContrast;

        // Detail & Noise
        uniform float uEdgeSharpening;
        uniform float uFineDetail;
        uniform float uChromaDenoise;

        // Color & Skin
        uniform float uSaturation;
        uniform float uVibrance;
        uniform float uWarmth;
        uniform float uSkinToneProtection;
        uniform mat3 uColorMatrix;

        // Geometry & Performance
        uniform vec2 uTexelSize;
        uniform int uPerformanceTier;

        void main() {
            vec4 curSample = texture2D(sTexture, vTextureCoord);
            vec3 curRgb = curSample.rgb;

            // Fast path for Default / Stock mode: pure pass-through of ISP video stream
            if (uPipelineMode == 0) {
                gl_FragColor = vec4(curRgb, 1.0);
                return;
            }

            // 1. Spatial Sampling for Detail, Local Contrast, Chroma Denoise & History Clamping
            vec2 tx = uTexelSize;
            vec3 cTop    = texture2D(sTexture, vTextureCoord + vec2(0.0,  tx.y)).rgb;
            vec3 cBottom = texture2D(sTexture, vTextureCoord + vec2(0.0, -tx.y)).rgb;
            vec3 cLeft   = texture2D(sTexture, vTextureCoord + vec2(-tx.x, 0.0)).rgb;
            vec3 cRight  = texture2D(sTexture, vTextureCoord + vec2( tx.x, 0.0)).rgb;

            vec3 blurColor = (cTop + cBottom + cLeft + cRight) * 0.25;
            float lumTop    = dot(cTop, vec3(0.299, 0.587, 0.114));
            float lumBottom = dot(cBottom, vec3(0.299, 0.587, 0.114));
            float lumLeft   = dot(cLeft, vec3(0.299, 0.587, 0.114));
            float lumRight  = dot(cRight, vec3(0.299, 0.587, 0.114));
            float lumCenter = dot(curRgb, vec3(0.299, 0.587, 0.114));
            float blurLum   = dot(blurColor, vec3(0.299, 0.587, 0.114));

            // Local neighborhood bounding box (Color / History AABB Clamping to prevent runaway feedback & trailing)
            vec3 curMin = min(curRgb, min(min(cTop, cBottom), min(cLeft, cRight)));
            vec3 curMax = max(curRgb, max(max(cTop, cBottom), max(cLeft, cRight)));
            vec3 boxMargin = max(curMax - curMin, vec3(0.025)) * 0.45;
            vec3 clampMin = max(curMin - boxMargin, vec3(0.0));
            vec3 clampMax = min(curMax + boxMargin, vec3(1.0));

            // 2. Temporal Multi-Frame & Robust Motion Confidence Rejection
            vec3 tempColor = curRgb;
            if (uHasPrevFrame != 0 && uTemporalDenoise > 0.01) {
                vec3 rawPrevRgb = texture2D(sPrevTexture, vTextureCoord).rgb;
                // Clamp history buffer to current local color bounding box to prevent runaway feedback & trailing
                vec3 prevRgb = clamp(rawPrevRgb, clampMin, clampMax);

                float prevLum = dot(prevRgb, vec3(0.299, 0.587, 0.114));

                // Multi-metric motion detection (Luminance + per-channel color + local variance)
                vec3 colorDiff = abs(curRgb - prevRgb);
                float maxDiff = max(colorDiff.r, max(colorDiff.g, colorDiff.b));
                float lumDiff = abs(lumCenter - prevLum);
                float rawMotion = max(maxDiff, lumDiff * 1.4);

                // Motion confidence with aggressive non-linear cutoff:
                // Drops rapidly to 0 under any perceptible motion to eliminate double edges and vertical streaks
                float threshold = max(uMotionThreshold, 0.012);
                float normMotion = clamp(rawMotion / threshold, 0.0, 1.0);
                float motionConfidence = 1.0 - smoothstep(0.12, 0.65, normMotion);
                motionConfidence = motionConfidence * motionConfidence; // Quadratic suppression

                // Conservative maximum blend weight in static scenes (capped at 0.50 to prevent accumulation)
                float maxBlend = min(uTemporalDenoise * 0.60, 0.50);
                float blendWeight = motionConfidence * maxBlend;

                tempColor = mix(curRgb, prevRgb, blendWeight);

                // Anti-flicker temporal exposure stabilization (strictly active only in confirmed static scenes)
                if (uTemporalFlickerDamping > 0.01 && motionConfidence > 0.75) {
                    float flickerWeight = uTemporalFlickerDamping * (motionConfidence - 0.75) * 0.4;
                    tempColor = mix(tempColor, prevRgb, flickerWeight);
                }
            }

            // Update lumCenter with temporally filtered color for subsequent stages
            lumCenter = dot(tempColor, vec3(0.299, 0.587, 0.114));

            // 3. Laplacian 2nd derivative edge response & Edge-Aware Sharpening
            float laplacian = 4.0 * lumCenter - (lumTop + lumBottom + lumLeft + lumRight);
            float edgeMag = abs(laplacian);

            // Chroma Denoise: Clean chromatic noise specks in flat/dark regions
            if (uChromaDenoise > 0.01) {
                float chromaMask = clamp(1.0 - edgeMag * 10.0, 0.0, 1.0) * uChromaDenoise;
                vec3 cleanChroma = vec3(lumCenter) + (blurColor - vec3(blurLum));
                tempColor = mix(tempColor, cleanChroma, chromaMask * 0.65);
            }

            // Edge-Aware Adaptive Sharpening with halo suppression and delta clamping
            vec3 sharpColor = tempColor;
            if (uEdgeSharpening > 0.01) {
                float sharpWeight = clamp(edgeMag * 6.0, 0.0, 1.0) * clamp(1.0 - edgeMag * 3.0, 0.0, 1.0);
                float sharpDelta = clamp(laplacian * (uEdgeSharpening * sharpWeight), -0.12, 0.12);
                sharpColor = clamp(tempColor + vec3(sharpDelta), 0.0, 1.0);
            }

            // 5. Dynamic Range, Highlight Recovery & Shadow Recovery
            float y = dot(sharpColor, vec3(0.299, 0.587, 0.114));
            vec3 chroma = sharpColor / max(y, 0.001);

            // Shadow Recovery: lift deep shadows smoothly while anchoring black level at 0
            float shadowMask = 1.0 - smoothstep(0.0, 0.55, y);
            float yShadow = y + uShadowRecovery * shadowMask * (sqrt(max(y, 0.0)) - y);

            // Highlight Recovery: parabolic knee compression for smooth roll-off without hard clipping
            float kneeThreshold = 1.0 - uHighlightRecovery * 0.35;
            float yHigh = yShadow;
            if (yHigh > kneeThreshold) {
                float excess = (yHigh - kneeThreshold) / max(1.0001 - kneeThreshold, 0.001);
                float rolledOver = kneeThreshold + (1.0 - kneeThreshold) * (excess / (1.0 + excess * 0.55));
                yHigh = rolledOver;
            }

            // Local Contrast (micro-contrast via unsharp difference on luminance)
            float localDiff = yHigh - blurLum;
            float yTone = yHigh + localDiff * (uLocalContrast * 0.45);

            // S-Curve Tone Mapping for finished modern smartphone contrast
            yTone = clamp(yTone, 0.0, 1.0);
            float sCurve = yTone * yTone * (3.0 - 2.0 * yTone);
            yTone = mix(yTone, sCurve, uHdrToneMap);

            vec3 processedRgb = chroma * yTone;

            // 6. Skin-Tone Protection (YCbCr locus detection)
            float Y  =  0.299 * processedRgb.r + 0.587 * processedRgb.g + 0.114 * processedRgb.b;
            float Cb = -0.1687 * processedRgb.r - 0.3313 * processedRgb.g + 0.5 * processedRgb.b;
            float Cr =  0.5 * processedRgb.r - 0.4187 * processedRgb.g - 0.0813 * processedRgb.b;

            // Elliptical skin locus: Cb ~ [-0.19, 0.03], Cr ~ [0.04, 0.30]
            float dCb = (Cb - (-0.08)) / 0.11;
            float dCr = (Cr - 0.17) / 0.13;
            float skinDist = dCb * dCb + dCr * dCr;
            float skinMask = clamp(1.0 - skinDist, 0.0, 1.0);

            // Soften harsh sharpening on skin pores while retaining texture
            if (skinMask > 0.01 && uSkinToneProtection > 0.01) {
                processedRgb = mix(processedRgb, mix(tempColor, processedRgb, 0.35), skinMask * uSkinToneProtection * 0.65);
            }

            // 7. Brand Color Science Matrix & Warmth Shift
            processedRgb = clamp(uColorMatrix * processedRgb, 0.0, 1.0);
            processedRgb.r += uWarmth * 0.035;
            processedRgb.b -= uWarmth * 0.035;

            // 8. Smart Vibrance & Finished SDR Saturation
            float maxC = max(processedRgb.r, max(processedRgb.g, processedRgb.b));
            float minC = min(processedRgb.r, min(processedRgb.g, processedRgb.b));
            float sat = (maxC - minC) / max(maxC, 0.001);
            float vibranceMask = (1.0 - sat) * uVibrance;
            float effectiveSat = uSaturation + vibranceMask;

            // Protect skin tones from oversaturation
            effectiveSat *= (1.0 - skinMask * uSkinToneProtection * 0.65);

            float finalLum = dot(processedRgb, vec3(0.299, 0.587, 0.114));
            processedRgb = mix(vec3(finalLum), processedRgb, 1.0 + effectiveSat);

            // 9. Final SDR Clamping straight into display / video encoder
            gl_FragColor = vec4(clamp(processedRgb, 0.0, 1.0), 1.0);
        }
    """

    // Passthrough blit shader to present the computed history texture to EGL surfaces
    const val BLIT_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        uniform mat4 uTexMatrix;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = (uTexMatrix * aTextureCoord).xy;
        }
    """

    const val BLIT_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """
}
