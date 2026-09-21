package com.example.camera.engine.night

import android.graphics.Bitmap

/**
 * Scene Illumination Levels for Computational Night Photography.
 */
enum class SceneIlluminationLevel(val label: String, val minFrames: Int, val maxFrames: Int) {
    BRIGHT_NIGHT("Bright Night (Street / Neon)", 4, 6),
    NORMAL_NIGHT("Normal Night (City / Dim)", 6, 8),
    VERY_DARK("Very Dark (Ultra Low Light)", 8, 12)
}

/**
 * Exposure Role for Each Bracketed Frame.
 */
enum class BracketExposureType {
    SHORT,   // Protects specular highlights, neon signs, and light bulbs from clipping
    MEDIUM,  // Natural mid-tones and color reference
    LONG     // Maximum exposure and sensor sensitivity for deep shadow photon gathering
}

/**
 * Plan for an Individual Frame within the Multi-Frame Adaptive Stack.
 */
data class NightBracketFrame(
    val index: Int,
    val type: BracketExposureType,
    val exposureTimeNs: Long,
    val iso: Int,
    val evOffset: Float,
    val isAnchorFrame: Boolean = false
)

/**
 * Complete Capture Plan dynamically produced by [NightSceneAnalyzer].
 */
data class NightBracketPlan(
    val sceneLevel: SceneIlluminationLevel,
    val estimatedLux: Float,
    val isTripod: Boolean,
    val bracketFrames: List<NightBracketFrame>,
    val totalEstimatedDurationMs: Long,
    val sensorMinExposureNs: Long,
    val sensorMaxExposureNs: Long,
    val sensorMinIso: Int,
    val sensorMaxIso: Int,
    val maxSupportedShutterSec: Float,
    val maxSupportedIso: Int,
    val telemetrySummary: String
)

/**
 * Acquired Frame Data container used during registration and fusion.
 */
data class CapturedNightFrame(
    val index: Int,
    val bitmap: Bitmap,
    val exposureTimeNs: Long,
    val iso: Int,
    val timestampNanos: Long,
    val type: BracketExposureType,
    val isReference: Boolean = false,
    val gyroPitchVelocity: Float = 0f,
    val gyroYawVelocity: Float = 0f
)
