package com.cinedepth.pro.ui.blur

import org.intellij.lang.annotations.Language

@Language("AGSL")
const val REFINEMENT_SHADER_SRC = """
uniform shader image;
uniform shader depthMap;
uniform float2 resolution;
uniform float edgeRefine;

float getLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec4 main(float2 coord) {
    float raw = depthMap.eval(coord).r;
    vec3 centerColor = image.eval(coord).rgb;
    float centerLuma = getLuma(centerColor);
    
    float totalWeight = 1.0;
    float weightedDepth = raw;
    
    const float radius = 3.0;
    const float rangeSigma = 0.10;
    const float rangeSigmaSq = 2.0 * rangeSigma * rangeSigma;
    
    for (float dy = -radius; dy <= radius; dy += 1.0) {
        for (float dx = -radius; dx <= radius; dx += 1.0) {
            if (dx == 0.0 && dy == 0.0) continue;
            
            vec2 offset = vec2(dx, dy);
            vec2 sampleCoord = clamp(coord + offset, vec2(0.0), resolution - 1.0);
            
            float sampleDepth = depthMap.eval(sampleCoord).r;
            vec3 sampleColor = image.eval(sampleCoord).rgb;
            float sampleLuma = getLuma(sampleColor);
            
            float lumaDiff = sampleLuma - centerLuma;
            float weight = exp(-(lumaDiff * lumaDiff) / rangeSigmaSq);
            
            weightedDepth += sampleDepth * weight;
            totalWeight += weight;
        }
    }
    float refined = weightedDepth / totalWeight;
    float edgeLuma = 0.0;
    float edgeDepth = 0.0;
    for (float dy = -1.0; dy <= 1.0; dy += 1.0) {
        for (float dx = -1.0; dx <= 1.0; dx += 1.0) {
            if (dx == 0.0 && dy == 0.0) continue;

            vec2 sampleCoord = clamp(coord + vec2(dx, dy), vec2(0.0), resolution - 1.0);
            float sampleDepth = depthMap.eval(sampleCoord).r;
            float sampleLuma = getLuma(image.eval(sampleCoord).rgb);
            edgeLuma = max(edgeLuma, abs(sampleLuma - centerLuma));
            edgeDepth = max(edgeDepth, abs(sampleDepth - raw));
        }
    }
    float edgeStrength = smoothstep(0.010, 0.085, edgeLuma + edgeDepth * 1.85);
    float snapStrength = edgeStrength * mix(0.10, 1.0, edgeRefine);
    float sharpened = refined + (raw - refined) * mix(0.35, 1.35, edgeRefine) * edgeStrength;
    refined = clamp(mix(refined, sharpened, snapStrength), 0.0, 1.0);
    return vec4(refined, refined, refined, 1.0);
}
"""

@Language("AGSL")
const val CONTOUR_MATTE_SHADER_SRC = """
uniform shader image;
uniform shader refinedDepthMap;
uniform shader segMask;       // ML Kit portrait segmentation (0=bg, 1=person)
uniform float2 resolution;
uniform float focus;
uniform float focusDeadZone;
uniform float falloffWidth;
uniform float edgeSoftness;
uniform float edgeExpand;
uniform float quality;
uniform float refineStrength;
uniform float hasSegMask;     // 1.0 if segmentation mask is available, 0.0 otherwise

float getLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec2 getChroma(vec3 c) {
    float y = getLuma(c);
    return vec2(c.b - y, c.r - y);
}

float baseMatte(float depth) {
    float transitionStart = max(0.0, focusDeadZone * 0.55 - edgeExpand * 0.02);
    float transitionEnd = focusDeadZone + falloffWidth * (0.28 + edgeSoftness * 0.22) + edgeExpand * 0.035;
    float dist = abs(depth - focus);
    return 1.0 - smoothstep(transitionStart, transitionEnd, dist);
}

vec4 main(float2 coord) {
    vec3 centerColor = image.eval(coord).rgb;
    float centerDepth = refinedDepthMap.eval(coord).r;
    float centerRaw = clamp(baseMatte(centerDepth), 0.0, 1.0);
    float centerLuma = getLuma(centerColor);
    vec2 centerChroma = getChroma(centerColor);

    // ── Segmentation mask: the hair/portrait prior ──────────────
    float centerSeg = segMask.eval(coord).r;  // 0=background, 1=person

    float localAverage = centerRaw;
    float localCount = 1.0;
    float guidedSum = centerRaw * mix(1.15, 1.4, quality);
    float guidedWeight = mix(1.15, 1.4, quality);
    float lumaEdge = 0.0;
    float chromaEdge = 0.0;
    float depthEdge = 0.0;
    float segEdge = 0.0;
    float localForegroundMax = centerRaw;
    float localBackgroundMin = centerRaw;

    for (float dy = -1.0; dy <= 1.0; dy += 1.0) {
        for (float dx = -1.0; dx <= 1.0; dx += 1.0) {
            if (dx == 0.0 && dy == 0.0) continue;

            vec2 sampleCoord = clamp(coord + vec2(dx, dy), vec2(0.0), resolution - 1.0);
            vec3 sampleColor = image.eval(sampleCoord).rgb;
            float sampleDepth = refinedDepthMap.eval(sampleCoord).r;
            float sampleRaw = clamp(baseMatte(sampleDepth), 0.0, 1.0);
            float sampleLuma = getLuma(sampleColor);
            vec2 sampleChroma = getChroma(sampleColor);
            float sampleSeg = segMask.eval(sampleCoord).r;

            float lumaDiff = abs(sampleLuma - centerLuma);
            float chromaDiff = length(sampleChroma - centerChroma);
            float depthDiff = abs(sampleDepth - centerDepth);

            lumaEdge = max(lumaEdge, lumaDiff);
            chromaEdge = max(chromaEdge, chromaDiff);
            depthEdge = max(depthEdge, depthDiff);
            segEdge = max(segEdge, abs(sampleSeg - centerSeg));
            localAverage += sampleRaw;
            localCount += 1.0;
            localForegroundMax = max(localForegroundMax, sampleRaw);
            localBackgroundMin = min(localBackgroundMin, sampleRaw);

            float spatialWeight = (dx == 0.0 || dy == 0.0) ? 1.0 : 0.78;
            float guideWeight = spatialWeight *
                exp(-(lumaDiff * lumaDiff) / mix(0.018, 0.012, quality)) *
                exp(-(chromaDiff * chromaDiff) / mix(0.028, 0.020, quality)) *
                exp(-(depthDiff * depthDiff) / mix(0.016, 0.010, quality));

            // When seg mask is available, also weight by seg similarity
            // This prevents blur from crossing person/background boundaries
            if (hasSegMask > 0.5) {
                float segDiff = abs(sampleSeg - centerSeg);
                guideWeight *= exp(-(segDiff * segDiff) / 0.08);
            }

            guidedSum += sampleRaw * guideWeight;
            guidedWeight += guideWeight;
        }
    }

    localAverage /= localCount;
    float guidedAverage = guidedSum / max(0.0001, guidedWeight);
    float contourStrength = smoothstep(0.035, 0.22, lumaEdge + chromaEdge * 0.85 + depthEdge * 1.45);
    float transitionBand = clamp(centerRaw * (1.0 - centerRaw) * 4.0, 0.0, 1.0);
    float stableBase = contourStrength < 0.12 ? centerRaw * 0.80 + localAverage * 0.20 : centerRaw;
    float refineAmount = mix(0.18, 1.0, refineStrength);
    float hairConfidence = contourStrength * transitionBand * quality * refineAmount;

    // ── Seg-mask boosted hair confidence ────────────────────────
    // When we're on a seg boundary (segEdge high) AND the seg says
    // "this pixel IS person", boost hair confidence dramatically.
    // This catches hair strands that depth misses entirely.
    if (hasSegMask > 0.5) {
        float segBoundary = smoothstep(0.08, 0.50, segEdge);
        float segIsPerson = smoothstep(0.30, 0.65, centerSeg);
        hairConfidence = max(hairConfidence, segBoundary * segIsPerson * quality * refineAmount * 0.85);
    }

    float refinedTransition = stableBase;
    if (hairConfidence > 0.24) {
        float foregroundSupport = mix(guidedAverage, localForegroundMax, (0.28 + edgeSoftness * 0.12) + refineStrength * 0.20);
        refinedTransition = max(centerRaw, foregroundSupport);
    } else if (transitionBand > 0.08) {
        refinedTransition = mix(centerRaw, guidedAverage, 0.20 + refineStrength * 0.22);
    }

    float matteBoost = hairConfidence * ((0.05 + edgeSoftness * 0.14) + refineStrength * 0.23);
    float edgeProtected = hairConfidence > 0.28 && (localForegroundMax - localBackgroundMin) > 0.34
        ? max(refinedTransition, localForegroundMax * mix(0.84, 0.92, refineStrength))
        : refinedTransition;
    float smoothed = mix(edgeProtected, guidedAverage, mix(0.08, 0.22, quality) * (1.0 - contourStrength) * (1.0 - refineStrength * 0.45));
    float alpha = clamp(smoothed + matteBoost, 0.0, 1.0);

    // ── Final seg-mask fusion ──────────────────────────────────
    // At person boundaries (hair), blend the depth-based alpha with
    // the segmentation confidence. The seg mask is the "truth" for
    // thin structures; depth is the "truth" for z-ordering.
    if (hasSegMask > 0.5) {
        // How much are we on a seg boundary?
        float segBoundaryZone = smoothstep(0.03, 0.30, segEdge);
        // In boundary zones, trust the seg mask for the alpha shape
        // but keep depth-based alpha for the overall intensity
        float segAlpha = smoothstep(0.10, 0.45, centerSeg);
        // Blend: in boundary zones, seg dominates; away from boundaries, depth dominates
        float fusionWeight = segBoundaryZone * refineAmount * 0.92;
        alpha = mix(alpha, max(alpha, segAlpha), fusionWeight);
        // Anti-halo: on person side near boundary, clamp alpha to prevent
        // blurred background from bleeding into the foreground edge
        float personNearEdge = segBoundaryZone * smoothstep(0.35, 0.65, centerSeg);
        alpha = mix(alpha, max(alpha, 0.95), personNearEdge * refineAmount * 0.80);
    }

    return vec4(alpha, alpha, alpha, 1.0);
}
"""

@Language("AGSL")
const val BOKEH_SHADER_SRC = """
uniform shader image;
uniform shader refinedDepthMap;
uniform shader contourMatte;
uniform shader segMask;       // ML Kit portrait segmentation
uniform float2 resolution;
uniform float focus;
uniform float focusDeadZone;
uniform float falloffWidth;
uniform float edgeExpand;
uniform float maxBlurRadius;
uniform int lensEffectType;
uniform float highlightBoost;
uniform float vignetteStrength;
uniform float highResScale;
uniform float hasSegMask;     // 1.0 if seg mask available

const float GOLDEN_ANGLE = 2.39996323;

float getLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float getCoC(float depth) {
    float dist = abs(depth - focus);
    float effectiveDist = max(0.0, dist - edgeExpand * 0.085);
    float coc = smoothstep(focusDeadZone, focusDeadZone + falloffWidth, effectiveDist);
    float opticalCurve = mix(pow(coc, 1.10), pow(coc, 1.72), smoothstep(0.24, 1.0, coc));
    return opticalCurve * maxBlurRadius;
}

vec3 applyHighlightBoost(vec3 color, float boost, float coc, float r_norm, int type) {
    float luma = getLuma(color);
    float hotspot = smoothstep(0.78, 0.98, luma);
    float cocGate = smoothstep(4.0, 20.0, coc);
    float rimGate = smoothstep(0.42, 0.96, r_norm);
    float shaped = hotspot * hotspot * (0.60 + rimGate * 0.40);
    if (type == 2) shaped *= 1.16;
    if (type == 3) shaped *= 0.90;
    if (type == 4) shaped *= 1.05;
    float factor = shaped * boost * cocGate * 1.8;
    return clamp(color * (1.0 + factor), 0.0, 1.0);
}

float getLensWeight(float r_norm, float theta, int type) {
    if (type == 1) return mix(1.32, 0.84, smoothstep(0.0, 1.0, r_norm)); // Creamy
    if (type == 2) { // Bubble
        float ring = smoothstep(0.38, 0.76, r_norm) * (1.0 - smoothstep(0.82, 1.0, r_norm));
        return 0.62 + ring * 1.10 + smoothstep(0.82, 1.0, r_norm) * 0.22;
    }
    if (type == 3) return mix(1.12, 0.70, smoothstep(0.0, 1.0, r_norm)); // Bloom
    if (type == 4) { // Star
        float spokes = pow(abs(cos(theta * 2.0)), 4.0);
        float rim = smoothstep(0.34, 1.0, r_norm);
        return 0.78 + rim * 0.24 + spokes * rim * 0.66;
    }
    if (type == 5) { // Hexagon
        float a = mod(theta, 1.04719755);
        float r_hex = 0.8660254 / cos(a - 0.52359877);
        float edge = smoothstep(r_hex + 0.03, r_hex - 0.03, r_norm);
        return mix(0.86, 1.05, edge);
    }
    if (type == 6) { // Anamorphic — oval shape with horizontal emphasis
        float rim = smoothstep(0.50, 1.0, r_norm);
        float horizontalBias = pow(abs(cos(theta)), 1.6);
        return 0.90 + rim * 0.18 + horizontalBias * rim * 0.32;
    }
    return 1.08 - smoothstep(0.60, 1.0, r_norm) * 0.22; // Classic
}

float getSampleRadiusNorm(float t, int type) {
    if (type == 1) return pow(t, 0.92);
    if (type == 2) return pow(t, 0.70);
    if (type == 3) return pow(t, 0.82);
    if (type == 4) return pow(t, 0.74);
    if (type == 5) return pow(t, 0.78);
    if (type == 6) return pow(t, 0.86);
    return pow(t, 0.88);
}

float getChromaticAberration(float coc, int type) {
    float base = mix(0.0002, 0.0011, smoothstep(2.0, 22.0, coc));
    if (type == 2) return base * 1.2;
    if (type == 4) return base * 0.85;
    if (type == 6) return base * 1.6; // Anamorphic: stronger fringing
    return base;
}

vec4 main(float2 coord) {
    vec2 uv = coord / resolution;
    vec3 sourceColor = image.eval(coord).rgb;
    float centerDepth = refinedDepthMap.eval(coord).r;
    float centerMatte = contourMatte.eval(coord).r;
    float centerSeg = segMask.eval(coord).r;
    float centerCoC = getCoC(centerDepth);
    // Seg mask boosts matte protection on person boundaries (hair, glasses)
    float segBoost = (hasSegMask > 0.5) ? smoothstep(0.25, 0.60, centerSeg) * 0.45 : 0.0;
    float effectiveMatte = clamp(centerMatte + segBoost, 0.0, 1.0);
    // CoC damping: reduce protection for far-away pixels, BUT preserve a
    // minimum floor when seg mask confidently says "person" (anti-halo)
    float cocDamping = 1.0 - smoothstep(1.4, 8.5, centerCoC);
    float segFloor = (hasSegMask > 0.5) ? smoothstep(0.40, 0.70, centerSeg) * 0.55 : 0.0;
    float matteProtection = effectiveMatte * max(cocDamping, segFloor);
    float blurGate = smoothstep(2.0, 18.0, centerCoC);

    if (centerCoC <= 0.2 || matteProtection >= 0.985) {
        vec4 color = vec4(sourceColor, 1.0);
        if (vignetteStrength > 0.0) {
            float r = length(uv - 0.5) * 2.0;
            color.rgb *= 1.0 - smoothstep(0.5, 1.5, r) * vignetteStrength;
        }
        return color;
    }

    vec3 accumColor = vec3(0.0);
    float accumWeight = 0.0;
    float caStrength = centerCoC * getChromaticAberration(centerCoC, lensEffectType);
    
    float currentStep = 0.0;
    
    float centerWeight = mix(1.16, 0.08, blurGate);
    accumColor += sourceColor * centerWeight;
    accumWeight += centerWeight;

    int maxSamples = 72;
    if (centerCoC < 12.0) maxSamples = 54;
    if (centerCoC < 5.0)  maxSamples = 36;
    if (centerCoC < 2.0)  maxSamples = 24;

    const int ABS_MAX_SAMPLES = 72;
    for (int i = 0; i < ABS_MAX_SAMPLES; i++) {
        if (i >= maxSamples) break; // Use break for dynamic loop count
        
        currentStep += 1.0;
        if (currentStep < highResScale && i > 0) continue;
        currentStep = 0.0;

        float theta = float(i) * GOLDEN_ANGLE;
        float r_norm = getSampleRadiusNorm(sqrt(float(i) / float(maxSamples)), lensEffectType);
        float r = r_norm * centerCoC;

        vec2 dir = vec2(cos(theta), sin(theta));
        // Anamorphic: oval bokeh via horizontal stretch
        vec2 offset = (lensEffectType == 6)
            ? vec2(dir.x * r * 1.65, dir.y * r * 0.58)
            : dir * r;
        
        vec2 rCoord = clamp(coord + offset * (1.0 + caStrength), vec2(0.0), resolution - 1.0);
        vec2 gCoord = clamp(coord + offset, vec2(0.0), resolution - 1.0);
        vec2 bCoord = clamp(coord + offset * (1.0 - caStrength), vec2(0.0), resolution - 1.0);
        
        vec3 sampleColor = vec3(image.eval(rCoord).r, image.eval(gCoord).g, image.eval(bCoord).b);
        float sampleDepth = refinedDepthMap.eval(gCoord).r;
        float sampleMatte = contourMatte.eval(gCoord).r;
        float sampleSeg = segMask.eval(gCoord).r;

        float weight = getLensWeight(r_norm, theta, lensEffectType);
        float depthDiff = sampleDepth - centerDepth;
        float sampleCoC = getCoC(sampleDepth);
        float nearerReject = smoothstep(0.006, 0.060, depthDiff);
        float fartherSupport = smoothstep(0.005, 0.10, -depthDiff);
        weight *= mix(1.0, 0.03, nearerReject * mix(0.55, 1.0, blurGate));
        weight *= mix(0.94, 1.06, fartherSupport * blurGate * 0.30);
        if (sampleCoC + 0.75 < centerCoC) weight *= mix(0.92, 0.76, blurGate);
        float matteDiff = abs(sampleMatte - centerMatte);
        float matteBlock = mix(0.42, 0.98, matteProtection);
        weight *= 1.0 - smoothstep(0.22, 0.82, matteDiff) * matteBlock;
        if (centerMatte < 0.32 && sampleMatte > 0.56) {
            float occlusion = max(nearerReject, smoothstep(0.08, 0.55, sampleMatte - centerMatte));
            weight *= mix(1.0, 0.015, occlusion);
        }
        if (matteProtection > 0.55 && sampleMatte + 0.14 < centerMatte) {
            weight *= 0.34;
        }

        // ── Seg-mask occlusion: hard boundary between person and background ──
        // Reject samples that cross the person/background boundary in BOTH directions
        // to prevent blur leaking across hair/skin edges (halo artifact).
        if (hasSegMask > 0.5) {
            float segDiff = abs(sampleSeg - centerSeg);
            float segCrossing = smoothstep(0.12, 0.50, segDiff);
            // Protect person edges: center=person, sample=background
            float personSide = smoothstep(0.20, 0.55, centerSeg);
            float personReject = segCrossing * personSide * 0.96;
            // Protect background edges: center=background, sample=person
            // (prevents blurred BG from pulling in sharp foreground)
            float bgSide = smoothstep(0.20, 0.55, 1.0 - centerSeg);
            float bgReject = segCrossing * bgSide * 0.90;
            weight *= 1.0 - max(personReject, bgReject);
        }

        accumColor += applyHighlightBoost(sampleColor, highlightBoost, sampleCoC, r_norm, lensEffectType) * weight;
        accumWeight += weight;
    }

    vec3 finalColor = accumColor / max(0.01, accumWeight);
    float finalLuma = getLuma(finalColor);
    float saturationBoost = blurGate * 0.16;
    finalColor = clamp(vec3(finalLuma) + (finalColor - vec3(finalLuma)) * (1.0 + saturationBoost), 0.0, 1.0);
    float fogContrast = blurGate * (1.0 - matteProtection) * 0.10;
    finalColor = clamp((finalColor - vec3(0.5)) * (1.0 + fogContrast) + vec3(0.5), 0.0, 1.0);
    float sourceRestore = 1.0 - smoothstep(1.1, 8.0, centerCoC);
    float contourRestore = smoothstep(0.18, 0.92, matteProtection);
    float restoreWeight = max(sourceRestore * 0.34, contourRestore * mix(0.42, 0.94, 1.0 - smoothstep(1.2, 7.0, centerCoC)));
    finalColor = mix(finalColor, sourceColor, restoreWeight);

    // Anamorphic horizontal streak on bright highlights
    if (lensEffectType == 6 && centerCoC > 3.0) {
        float streakAccum = 0.0;
        vec3 streakColor = vec3(0.0);
        const int STREAK_TAPS = 12;
        for (int s = -STREAK_TAPS; s <= STREAK_TAPS; s++) {
            float sx = float(s) * centerCoC * 0.65;
            vec2 sc = clamp(coord + vec2(sx, 0.0), vec2(0.0), resolution - 1.0);
            vec3 sc_col = image.eval(sc).rgb;
            float sc_luma = getLuma(sc_col);
            float glow = smoothstep(0.62, 0.92, sc_luma);
            float dist_falloff = exp(-float(s * s) / (float(STREAK_TAPS * STREAK_TAPS) * 0.35));
            float w = glow * dist_falloff;
            // Cool cyan-blue tint typical of anamorphic flares
            streakColor += mix(sc_col, vec3(0.7, 0.85, 1.0), 0.45) * w;
            streakAccum += w;
        }
        if (streakAccum > 0.01) {
            vec3 streak = streakColor / streakAccum;
            float streakMix = smoothstep(3.0, 18.0, centerCoC) * highlightBoost * 0.55;
            finalColor = finalColor + streak * streakMix;
        }
    }

    if (vignetteStrength > 0.0) {
        float r = length(uv - 0.5) * 2.0;
        finalColor *= 1.0 - smoothstep(0.5, 1.5, r) * vignetteStrength;
    }

    return vec4(clamp(finalColor, 0.0, 1.0), 1.0);
}
"""
