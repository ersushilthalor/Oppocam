package com.example.camera.engine.night

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Log
import android.util.Range
import com.example.camera.engine.GyroStabilizationEngine
import com.example.camera.model.NightConfig
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Intelligent Scene & Sensor Capability Analyzer for Flagship Night Photography.
 *
 * Responsibilities:
 * 1. Queries physical CameraCharacteristics for true HAL/driver capability boundaries:
 *    [SENSOR_INFO_EXPOSURE_TIME_RANGE], [SENSOR_INFO_SENSITIVITY_RANGE], [SENSOR_MAX_ANALOG_SENSITIVITY].
 * 2. Unlocks the maximum legally accepted exposure time and analog/digital sensitivity supported by
 *    the device hardware, avoiding arbitrary conservative software limits.
 * 3. Estimates real scene light value (LV) and lux from preview sensor exposure telemetry.
 * 4. Categorizes lighting into Bright Night (4-6 frames), Normal Night (6-8 frames), or Very Dark (8-12 frames).
 * 5. Integrates physical Gyroscope samples to detect handheld vibration vs. stable tripod mounting.
 * 6. Generates a balanced HDR bracket pattern (short -> medium -> long -> long -> medium -> short)
 *    to protect specular highlights while extracting deep shadow detail.
 * 7. Clamps all values strictly to legal Camera2 limits and logs full diagnostic telemetry.
 */
class NightSceneAnalyzer {

    companion object {
        private const val TAG = "NightSceneAnalyzer"

        // Maximum handheld shutter ceilings dynamically adapted to gyro jitter
        private const val BASE_HANDHELD_SHUTTER_NS = 500_000_000L // 500ms base handheld
        private const val MAX_STEADY_HANDHELD_SHUTTER_NS = 1_500_000_000L // 1.5s when steady handheld
        private const val MAX_TRIPOD_SHUTTER_NS = 10_000_000_000L // 10s maximum hardware limit on tripod

        // Gyro stability threshold (radians per second RMS): below this implies steady tripod/surface
        private const val GYRO_STABILITY_THRESHOLD_RAD = 0.018f
        private const val GYRO_STEADY_HANDHELD_THRESHOLD_RAD = 0.045f
    }

    /**
     * Generates an optimal computational night capture plan.
     */
    fun createPlan(
        characteristics: CameraCharacteristics?,
        previewResult: CaptureResult?,
        gyroEngine: GyroStabilizationEngine?,
        config: NightConfig
    ): NightBracketPlan {
        // 1. Query Hardware Sensor Capabilities
        val expRange: Range<Long> = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(100_000L, 1_000_000_000L) // Default 0.1ms to 1s
        val isoRange: Range<Int> = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: Range(100, 3200)
        val maxAnalogSensitivity: Int = characteristics?.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
            ?: isoRange.upper

        val sensorMinExpNs = expRange.lower
        val sensorMaxExpNs = expRange.upper
        val sensorMinIso = isoRange.lower
        val sensorMaxIso = isoRange.upper

        // 2. Query Live Preview Exposure Telemetry
        val previewExposureNs = previewResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_333_333L // ~1/30s
        val previewIso = previewResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 400
        val aperture = previewResult?.get(CaptureResult.LENS_APERTURE) ?: 1.8f

        // 3. Compute Scene Light Value (LV) and Estimated Lux
        val exposureSec = previewExposureNs / 1_000_000_000.0
        val isoNormalized = previewIso / 100.0
        // LV = log2(N^2 / (t * (ISO / 100)))
        val lightValue = log2((aperture * aperture) / (exposureSec * isoNormalized))
        val estimatedLux = (2.5 * 2.0.pow(lightValue)).toFloat().coerceAtLeast(0.01f)

        // 4. Evaluate Gyroscope Motion Stability (Handheld vs. Steady vs. Tripod)
        val motionRms = gyroEngine?.getRecentRmsMotion() ?: 0.05f
        val isTripod = config.tripodDetectionEnabled && motionRms > 0f && motionRms < GYRO_STABILITY_THRESHOLD_RAD
        val isSteadyHandheld = motionRms > 0f && motionRms < GYRO_STEADY_HANDHELD_THRESHOLD_RAD

        // 5. Classify Scene Illumination Level
        val sceneLevel = when {
            lightValue >= 1.0 -> SceneIlluminationLevel.BRIGHT_NIGHT
            lightValue >= -2.0 -> SceneIlluminationLevel.NORMAL_NIGHT
            else -> SceneIlluminationLevel.VERY_DARK
        }

        // 6. Determine Target Frame Count dynamically: avoid unnecessarily long capture when already sufficiently exposed
        val targetFrameCount = if (config.durationSeconds > 0) {
            // User explicitly requested fixed duration (1s..5s)
            val baseFrames = when (config.durationSeconds) {
                1 -> 3
                2 -> 5
                3 -> 7
                4 -> 8
                else -> 10
            }
            baseFrames.coerceIn(sceneLevel.minFrames, sceneLevel.maxFrames)
        } else {
            // Intelligent AUTO Mode: fast capture for sufficiently exposed scenes, deeper burst only when needed
            when {
                lightValue >= 2.0 -> 3 // Well-illuminated scene: fast 3-frame burst (under 250ms)
                lightValue >= 0.5 -> 4 // Bright night / street: 4 frames
                lightValue >= -1.5 -> 5 // Standard night scene: 5 frames
                lightValue >= -3.0 -> if (isTripod) 8 else 6 // Dim night: 6 frames handheld, 8 tripod
                else -> if (isTripod) 10 else 7 // Extreme low-light
            }
        }

        // 7. Determine Maximum Practical Shutter Speed supported by device HAL:
        // Use maximum practical shutter exposure supported by device instead of unnecessarily limiting shutter speed.
        val maxSafeShutterNs = when {
            isTripod -> min(sensorMaxExpNs, MAX_TRIPOD_SHUTTER_NS)
            isSteadyHandheld -> min(sensorMaxExpNs, MAX_STEADY_HANDHELD_SHUTTER_NS)
            else -> min(sensorMaxExpNs, BASE_HANDHELD_SHUTTER_NS)
        }

        // Determine Base Reference Exposure (Medium frame)
        val baseMultiplier = when {
            lightValue >= 1.5 -> 1.0 // Scene already sufficiently exposed: don't over-boost shutter
            lightValue >= 0.0 -> 1.3
            else -> 1.6
        }
        val baseExposureNs = (previewExposureNs * baseMultiplier).toLong().coerceIn(sensorMinExpNs, maxSafeShutterNs)
        val baseIso = previewIso.coerceIn(sensorMinIso, sensorMaxIso)

        // 8. Generate Balanced Exposure Bracket Stack:
        // Pattern: SHORT -> MEDIUM -> LONG -> ... -> LONG -> MEDIUM -> SHORT
        val bracketFrames = mutableListOf<NightBracketFrame>()

        // Short frame: EV -2.0 to protect specular highlights and neon signs
        val shortExpNs = (baseExposureNs / 4).coerceAtLeast(sensorMinExpNs)
        val shortIso = (baseIso * 0.75f).toInt().coerceIn(sensorMinIso, sensorMaxIso)

        // Medium frame: EV 0 (balanced mid-tone base)
        val medExpNs = baseExposureNs
        val medIso = baseIso

        // Long frame: EV +1.5 to +2.5 for shadow recovery, expanding shutter to sensor limits
        val longMultiplier = when {
            lightValue >= 1.5 -> 1.8f
            lightValue >= 0.0 -> 2.5f
            else -> 3.5f
        }
        val targetLongExpNs = min(sensorMaxExpNs, (baseExposureNs * longMultiplier).toLong()).coerceIn(sensorMinExpNs, maxSafeShutterNs)
        val remainingGain = (baseExposureNs * longMultiplier) / targetLongExpNs.toFloat()
        val targetLongIso = (baseIso * remainingGain).toInt().coerceIn(sensorMinIso, sensorMaxIso)

        for (i in 0 until targetFrameCount) {
            val type: BracketExposureType
            val expNs: Long
            val isoVal: Int
            val ev: Float

            val isStartOrEnd = (i == 0 || i == targetFrameCount - 1)
            val isSecondOrPenultimate = (i == 1 || i == targetFrameCount - 2)

            when {
                // Outer edges are short frames for highlights and edge stabilization
                isStartOrEnd && targetFrameCount >= 5 -> {
                    type = BracketExposureType.SHORT
                    expNs = shortExpNs
                    isoVal = shortIso
                    ev = -2.0f
                }
                // Transition frames are medium frames for mid-tone continuity
                isSecondOrPenultimate && targetFrameCount >= 6 -> {
                    type = BracketExposureType.MEDIUM
                    expNs = medExpNs
                    isoVal = medIso
                    ev = 0.0f
                }
                // Core burst is long frames for ultra-bright shadow accumulation
                else -> {
                    type = BracketExposureType.LONG
                    expNs = targetLongExpNs
                    isoVal = targetLongIso
                    ev = +2.0f
                }
            }

            // Strictly clamp to real Camera2 hardware range
            val legalExpNs = expNs.coerceIn(sensorMinExpNs, sensorMaxExpNs)
            val legalIso = isoVal.coerceIn(sensorMinIso, sensorMaxIso)

            bracketFrames.add(
                NightBracketFrame(
                    index = i,
                    type = type,
                    exposureTimeNs = legalExpNs,
                    iso = legalIso,
                    evOffset = ev,
                    isAnchorFrame = (i == targetFrameCount / 2) // Middle frame is central spatial anchor
                )
            )
        }

        // Calculate total capture duration in milliseconds
        val totalDurationMs = bracketFrames.sumOf { it.exposureTimeNs / 1_000_000L } + (bracketFrames.size * 60L)

        val maxShutterSec = sensorMaxExpNs / 1_000_000_000f

        val telemetry = "Scene=${sceneLevel.label}, Lux=${"%.2f".format(estimatedLux)}, Tripod=$isTripod, " +
                "Frames=${bracketFrames.size}, SensorLimits: Shutter=[${sensorMinExpNs / 1000}µs..${"%.2f".format(maxShutterSec)}s], " +
                "ISO=[$sensorMinIso..$sensorMaxIso], RequestedBrackets: " +
                bracketFrames.joinToString { "[F${it.index}:${it.type} ${(it.exposureTimeNs / 1_000_000L)}ms/ISO${it.iso}]" }

        Log.i(TAG, "[NIGHT_TELEMETRY] $telemetry")

        return NightBracketPlan(
            sceneLevel = sceneLevel,
            estimatedLux = estimatedLux,
            isTripod = isTripod,
            bracketFrames = bracketFrames,
            totalEstimatedDurationMs = totalDurationMs,
            sensorMinExposureNs = sensorMinExpNs,
            sensorMaxExposureNs = sensorMaxExpNs,
            sensorMinIso = sensorMinIso,
            sensorMaxIso = sensorMaxIso,
            maxSupportedShutterSec = maxShutterSec,
            maxSupportedIso = sensorMaxIso,
            telemetrySummary = telemetry
        )
    }

    /**
     * Evaluates gyroscope angular velocity jitter to determine if the camera is
     * firmly supported on a tripod or table versus held by hand.
     */
    private fun checkGyroscopeStability(gyroEngine: GyroStabilizationEngine): Boolean {
        val motionRms = gyroEngine.getRecentRmsMotion()
        return motionRms > 0f && motionRms < GYRO_STABILITY_THRESHOLD_RAD
    }
}
