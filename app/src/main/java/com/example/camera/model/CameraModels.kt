package com.example.camera.model

import android.graphics.ImageFormat
import android.graphics.RectF
import android.util.Size

enum class CameraMode(val title: String) {
    PHOTO("Photo"),
    VIDEO("Video"),
    CINEMA("Pro Video"),
    NIGHT("Night"),
    MORE("More"),
    AI_SUBJECT_TRACKING("AI Tracking")
}

enum class FlashMode(val title: String) {
    OFF("Off"),
    AUTO("Auto"),
    ON("On"),
    TORCH("Torch")
}

enum class TimerMode(val seconds: Int, val label: String) {
    OFF(0, "Off"),
    SEC_3(3, "3s"),
    SEC_5(5, "5s"),
    SEC_10(10, "10s")
}

enum class GridType(val title: String) {
    NONE("Off"),
    THIRDS("3x3 Rule"),
    GOLDEN("Golden Ratio"),
    SQUARE("1:1 Box"),
    LEVEL("Level Horizon")
}

enum class CameraAspectRatio(
    val label: String,
    val ratioValue: Float, // height / width in portrait
    val isVideoStandard: Boolean = false
) {
    RATIO_9_16("9:16", 16f / 9f, true),
    RATIO_16_9("16:9", 16f / 9f, true),
    RATIO_4_3("4:3", 4f / 3f, false),
    RATIO_1_1("1:1", 1f, false),
    RATIO_FULL("FULL", 0f, true),
    RATIO_IMAX("1.43:1", 1.43f, true),
    RATIO_CINEMATIC("2.39:1", 2.39f, true)
}

enum class WhiteBalanceMode(val title: String, val camera2Mode: Int, val shortLabel: String = title) {
    AUTO("Auto", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO, "AUTO"),
    INCANDESCENT("Incandescent", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, "INC"),
    FLUORESCENT("Fluorescent", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, "FLR"),
    WARM_FLUORESCENT("Warm Fluor", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT, "WARM"),
    DAYLIGHT("Daylight", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, "SUN"),
    CLOUDY("Cloudy", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "CLD"),
    TWILIGHT("Twilight", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_TWILIGHT, "TWIL"),
    SHADE("Shade", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE, "SHD")
}

enum class FocusMode(val title: String, val camera2Mode: Int) {
    CONTINUOUS("AF-C", android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
    AUTO("AF-S", android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO),
    MACRO("Macro", android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_MACRO),
    MANUAL("Manual", android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF)
}

enum class VideoProfileQuality(val title: String, val width: Int, val height: Int) {
    UHD_4K("4K UHD", 3840, 2160),
    FHD_1080P("1080p FHD", 1920, 1080),
    HD_720P("720p HD", 1280, 720),
    SD_480P("480p SD", 720, 480)
}

enum class VideoBitrateOption(val title: String, val bps: Int) {
    AUTO("Auto (Default)", 0),
    STANDARD("Standard (16 Mbps)", 16_000_000),
    HIGH("High (30 Mbps)", 30_000_000),
    MAX("Max (50 Mbps)", 50_000_000)
}

enum class VideoQualityOption(
    val shortLabel: String,
    val fullLabel: String,
    val width: Int,
    val height: Int,
    val fps: Int
) {
    UHD_4K_30("4K 30", "4K UHD · 30 FPS", 3840, 2160, 30),
    UHD_4K_60("4K 60", "4K UHD · 60 FPS", 3840, 2160, 60),
    FHD_1080_30("1080p 30", "1080p FHD · 30 FPS", 1920, 1080, 30),
    FHD_1080_60("1080p 60", "1080p FHD · 60 FPS", 1920, 1080, 60),
    HD_720_30("720p 30", "720p HD · 30 FPS", 1280, 720, 30);

    val badgeLabel: String
        get() = shortLabel

    val resolution: CameraResolution
        get() = CameraResolution(width, height)
}

enum class ViewfinderResolution(
    val label: String,
    val description: String,
    val maxDimension: Int
) {
    NORMAL("Normal", "1080p live preview (Optimized battery & latency)", 1920),
    RES_2K("2K", "1440p / 2K live preview clarity", 2560),
    RES_4K("4K", "2160p / 4K ultra high-definition live preview", 4096);

    companion object {
        fun fromName(name: String): ViewfinderResolution {
            return when (name.uppercase()) {
                "HIGH", "RES_2K", "TWO_K", "2K" -> RES_2K
                "MAX", "RES_4K", "FOUR_K", "4K" -> RES_4K
                else -> NORMAL
            }
        }
    }
}

enum class ColorProfile(val title: String, val isFlat: Boolean) {
    STANDARD("Standard", false),
    VIBRANT("Vibrant", false),
    NATURAL("Natural", false),
    MONOCHROME("B&W Monochrome", false)
}

data class CameraResolution(
    val width: Int,
    val height: Int,
    val format: Int = ImageFormat.JPEG,
    val isRaw: Boolean = false
) {
    val megapixels: Float
        get() = (width * height) / 1_000_000f

    val aspectRatioLabel: String
        get() {
            val gcd = gcd(width, height)
            val w = width / gcd
            val h = height / gcd
            return when {
                (w == 4 && h == 3) || (w == 3 && h == 4) -> "4:3"
                (w == 16 && h == 9) || (w == 9 && h == 16) -> "16:9"
                (w == 1 && h == 1) -> "1:1"
                (w == 20 && h == 9) || (w == 9 && h == 20) -> "20:9"
                else -> {
                    val ratio = width.toFloat() / height.toFloat()
                    when {
                        kotlin.math.abs(ratio - (4f / 3f)) < 0.05f || kotlin.math.abs(ratio - (3f / 4f)) < 0.05f -> "4:3"
                        kotlin.math.abs(ratio - (16f / 9f)) < 0.05f || kotlin.math.abs(ratio - (9f / 16f)) < 0.05f -> "16:9"
                        kotlin.math.abs(ratio - (20f / 9f)) < 0.05f || kotlin.math.abs(ratio - (9f / 20f)) < 0.05f -> "20:9"
                        else -> "$width x $height"
                    }
                }
            }
        }

    val displayLabel: String
        get() = if (isRaw) {
            "RAW %.1f MP (%dx%d)".format(megapixels, width, height)
        } else {
            "%.1f MP (%s · %dx%d)".format(megapixels, aspectRatioLabel, width, height)
        }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

data class LensInfo(
    val id: String = java.util.UUID.randomUUID().toString(),
    val cameraId: String,
    val facing: Int, // CameraCharacteristics.LENS_FACING_BACK, etc.
    val lensType: LensType,
    val displayName: String,
    val focalLengthMm: Float,
    val maxAperture: Float,
    val isPhysical: Boolean = false,
    val isHiddenAux: Boolean = false,
    val isZoomPreset: Boolean = false,
    val baseZoomRatio: Float = 1.0f,
    val physicalCameraId: String? = null,
    val fovDegrees: Float = 0f,
    val equivalent35mmFocalMm: Float = 0f,
    val idTypeDescription: String = "Logical",
    val isLogicalMultiCamera: Boolean = false,
    val minZoomRatio: Float = 0.5f,
    val maxZoomRatio: Float = 20.0f,
    val isPrimaryMain: Boolean = false,
    val isIndependentCamera: Boolean = false,
    val supportsPhysicalStream: Boolean = false,
    val intrinsicZoomRatio: Float = baseZoomRatio
)

enum class LensType(val shortLabel: String, val fullLabel: String) {
    ULTRAWIDE("0.5x", "Ultra Wide"),
    WIDE("1x", "Main Wide"),
    TELEPHOTO("2x", "2x Telephoto"),
    TELEPHOTO_3X("3x", "3x Telephoto"),
    MACRO("Macro", "Macro Lens"),
    FRONT("1x", "Front Selfie")
}

data class HardwareCapabilities(
    val supportsManualSensor: Boolean = false,
    val supportsRaw: Boolean = false,
    val supportsOis: Boolean = false,
    val supportsEis: Boolean = false,
    val supportsFlash: Boolean = false,
    val minIso: Int = 100,
    val maxIso: Int = 3200,
    val minExposureTimeNs: Long = 100_000L, // 0.1ms
    val maxExposureTimeNs: Long = 1_000_000_000L, // 1s
    val minExposureCompensation: Int = -4,
    val maxExposureCompensation: Int = 4,
    val exposureCompensationStep: Float = 0.333f,
    val minFocusDistance: Float = 10f, // diopters
    val supportedAwbModes: List<WhiteBalanceMode> = emptyList(),
    val supportedAfModes: List<FocusMode> = emptyList(),
    val supportedPhotoResolutions: List<CameraResolution> = emptyList(),
    val supportedRawResolutions: List<CameraResolution> = emptyList(),
    val supportedVideoResolutions: List<CameraResolution> = emptyList(),
    val supportedFpsRanges: List<Int> = listOf(30, 60),
    val supportsTonemapCurve: Boolean = false,
    val supportsColorTransform: Boolean = true,
    val supportsEdgeMode: Boolean = true,
    val supportsNoiseReduction: Boolean = true,
    val supportsAeLock: Boolean = true,
    val supportsAwbLock: Boolean = true,
    val hardwareLevel: Int = 0,
    val minZoom: Float = 1.0f,
    val maxZoom: Float = 20.0f
)

data class StorageStats(
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val freeGb: Float = 0f,
    val estimatedPhotos: Int = 0,
    val estimatedVideoMinutes: Int = 0
)

data class CapturedMedia(
    val uri: android.net.Uri,
    val isVideo: Boolean,
    val timestamp: Long,
    val displayName: String,
    val isFrontCamera: Boolean = false
)

data class NightConfig(
    val durationSeconds: Int = 0, // 0 = AUTO (intelligent scene & gyro sensing), 1 to 5 seconds
    val multiFrameFusionEnabled: Boolean = true,
    val antiGhostingEnabled: Boolean = true,
    val noiseSuppression: Float = 0.85f,
    val shadowLift: Float = 1.35f,
    val isMultiFrameFusion: Boolean = true,
    val isAntiGhostingEnabled: Boolean = true,
    val noiseSuppressionStrength: Float = 0.85f,
    val shadowLiftFactor: Float = 1.35f,
    val exposureBias: Float = 1.0f,
    val highlightProtection: Boolean = true,
    val localToneMapping: Boolean = true,
    val tripodDetectionEnabled: Boolean = true,
    val rawSensorPreferred: Boolean = true
)

data class NightCaptureProgress(
    val isCapturing: Boolean = false,
    val remainingSeconds: Float = 0f,
    val progress: Float = 0f,
    val statusText: String = "Hold device steady...",
    val detectedScene: String = "Auto Night",
    val activeFrameCount: Int = 0,
    val targetFrameCount: Int = 0,
    val isTripodDetected: Boolean = false,
    val exposureTimeMs: Float = 0f,
    val iso: Int = 0
)

enum class VideoStabilizationMode(
    val id: String,
    val title: String,
    val badgeLabel: String,
    val subtitle: String,
    val description: String
) {
    OFF(
        id = "off",
        title = "OFF",
        badgeLabel = "OFF",
        subtitle = "Stabilization Disabled",
        description = "Full uncropped sensor readout without digital dampening"
    ),
    EIS(
        id = "eis",
        title = "EIS",
        badgeLabel = "EIS",
        subtitle = "Standard Electronic Stabilization",
        description = "Camera2 HAL electronic image stabilization with standard smoothing"
    );

    val isEnabled: Boolean get() = this != OFF
}

enum class MainCameraStabilizationMode(val title: String, val subtitle: String) {
    OFF("Off", "Stabilization disabled"),
    OIS_ONLY("OIS Only", "Physical optical voice-coil stabilization only"),
    EIS_ONLY("EIS Only", "Electronic image stabilization only (OIS off)"),
    HYBRID_OIS_EIS("OIS + EIS", "Synchronized optical + electronic stabilization"),
    ULTRA("Ultra Action", "Maximum gyro-compensated action stabilization")
}

data class HybridStabilizationConfig(
    val isHybridEnabled: Boolean = true,
    val isOisPreferred: Boolean = true,
    val isOisEnabled: Boolean = true,
    val isEisPreferred: Boolean = true,
    val isAdaptiveFpsLens: Boolean = true,
    val isUltraStabilizationEnabled: Boolean = false,
    val isEisOnly: Boolean = false,
    val oisHardwareStatus: String = "Detecting",
    val eisHardwareStatus: String = "Detecting",
    val ultraStabilizationStatus: String = "Ready"
) {
    val stabilizationMode: MainCameraStabilizationMode
        get() {
            val oisEffective = isOisPreferred && isOisEnabled
            return when {
                isUltraStabilizationEnabled -> MainCameraStabilizationMode.ULTRA
                isEisOnly || (!oisEffective && isEisPreferred) -> MainCameraStabilizationMode.EIS_ONLY
                oisEffective && !isEisPreferred -> MainCameraStabilizationMode.OIS_ONLY
                isHybridEnabled || (oisEffective && isEisPreferred) -> MainCameraStabilizationMode.HYBRID_OIS_EIS
                else -> MainCameraStabilizationMode.OFF
            }
        }
}

data class TapFocusConfig(
    val isTapToFocusExposureEnabled: Boolean = true,
    val isTapToFocusEnabled: Boolean = true,
    val isAeAfLockEnabled: Boolean = true,
    val isSunExposureSliderEnabled: Boolean = true,
    val autoDismissReticle: Boolean = true
)

enum class BackgroundCameraStatus(val label: String, val shortDesc: String) {
    OFF("Off", "Standby Disabled"),
    PREPARING("Preparing", "Starting background stream..."),
    READY_QUIET("Ready (Quiet)", "Ready in background (invisible)"),
    READY_PREVIEW("Live Preview", "Streaming to little preview"),
    UNAVAILABLE("Unavailable", "Camera not present or in use")
}

enum class PreviewStreamSource {
    MAIN,
    ULTRAWIDE
}

data class MotorolaInstantSwitchState(
    val isKeepUltraWideReady: Boolean = false,
    val isAutoSwitchToUltraWide: Boolean = false,
    val isAutoMacroActive: Boolean = false,
    val isShowUltraWidePreview: Boolean = false,
    val isKeepFrontCameraReady: Boolean = false,
    val isShowFrontCameraPreview: Boolean = false,
    val ultraWideStatus: BackgroundCameraStatus = BackgroundCameraStatus.OFF,
    val frontStatus: BackgroundCameraStatus = BackgroundCameraStatus.OFF,
    val isMotorolaDevice: Boolean = false,
    val isConcurrentHardwareSupported: Boolean = true,
    val activeStandbyLens: LensType? = null,
    val switchLatencyEstimateMs: Int = 0,
    val lastMeasuredLatencyMs: Long = 0L,
    val statusMessage: String = "Motorola Instant Switching Ready",
    val switchPointMm: Float = 23.0f,
    val displayedPreviewSource: PreviewStreamSource = PreviewStreamSource.MAIN,
    val lensSwitchOverlapDurationSec: Float = 0.3f,
    val lensSwitchHoldDurationSec: Float = 0.10f
)

/**
 * Styles for floating windows selectable from Settings:
 * Liquid Glass, Frosted Glass, Transparent Glass, Subtle Frost, Deep Frost, Solid Dark
 */
enum class FloatingWindowGlassStyle(val id: String, val displayName: String) {
    LIQUID_GLASS("liquid_glass", "Liquid Glass"),
    FROSTED_GLASS("frosted_glass", "Frosted Glass"),
    TRANSPARENT_GLASS("transparent_glass", "Transparent Glass"),
    SUBTLE_FROST("subtle_frost", "Subtle Frost"),
    DEEP_FROST("deep_frost", "Deep Frost"),
    SOLID_DARK("solid_dark", "Solid Dark");

    companion object {
        fun fromId(id: String?): FloatingWindowGlassStyle =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: LIQUID_GLASS
    }
}

/**
 * Floating Window Appearance & Content Configuration:
 * Controls the floating window glass style, transparency, blur strength, window scale (size),
 * and which settings/controls are displayed inside floating windows across the app.
 *
 * @param glassStyle Liquid Glass, Frosted Glass, Transparent Glass, etc.
 * @param transparency 0.0f (opaque/solid glass) to 1.0f (crystal clear / maximum backdrop visibility)
 * @param blurStrength 0.0f (sharp/no blur) to 50.0f (deep creamy optical frosted diffusion)
 * @param windowScale 0.75f (75% compact) to 1.25f (125% large) size multiplier for all floating windows
 */
data class FloatingWindowAppearanceConfig(
    val glassStyle: FloatingWindowGlassStyle = FloatingWindowGlassStyle.LIQUID_GLASS,
    val transparency: Float = 0.55f, // Default 55% transparency
    val blurStrength: Float = 22.0f,  // Default 22 dp blur
    val windowScale: Float = 0.88f,   // Content-based compact scale (default 88%)
    // Configurable settings inside Floating Windows:
    // 1. Video Settings Floating Window
    val showVideoResolution: Boolean = true,
    val showVideoFps: Boolean = true,
    val showVideoStabilization: Boolean = true,
    // 2. Cinema Settings Floating Window
    val showCinemaColorProfile: Boolean = true,
    val showCinemaLutControls: Boolean = true,
    val showCinemaResolutionFps: Boolean = true,
    val showCinemaStabilization: Boolean = true,
    val showCinemaAssistTools: Boolean = true,
    // 4. Pro Manual & Video Adjustments & Pipeline Floating Windows
    val showProExposureControls: Boolean = true,
    val showProToneAdjustments: Boolean = true,
    val showVideoAdjustmentsColorTone: Boolean = true,
    val showVideoAdjustmentsEffects: Boolean = true,
    val showPipelineMasterToggle: Boolean = true,
    val showPipelinePresetList: Boolean = true
) {
    val transparencyPercent: Int get() = kotlin.math.round((transparency * 100f)).toInt().coerceIn(0, 100)
    val blurStrengthDp: Int get() = kotlin.math.round(blurStrength).toInt().coerceIn(0, 50)
    val windowScalePercent: Int get() = kotlin.math.round((windowScale * 100f)).toInt().coerceIn(75, 125)

    companion object {
        val LIQUID_GLASS = FloatingWindowAppearanceConfig(
            glassStyle = FloatingWindowGlassStyle.LIQUID_GLASS,
            transparency = 0.55f,
            blurStrength = 22.0f,
            windowScale = 0.88f
        )
        val FROSTED_GLASS = FloatingWindowAppearanceConfig(
            glassStyle = FloatingWindowGlassStyle.FROSTED_GLASS,
            transparency = 0.40f,
            blurStrength = 32.0f,
            windowScale = 0.88f
        )
        val TRANSPARENT_GLASS = FloatingWindowAppearanceConfig(
            glassStyle = FloatingWindowGlassStyle.TRANSPARENT_GLASS,
            transparency = 0.82f,
            blurStrength = 8.0f,
            windowScale = 0.88f
        )
        val SUBTLE_FROST = FloatingWindowAppearanceConfig(
            glassStyle = FloatingWindowGlassStyle.SUBTLE_FROST,
            transparency = 0.45f,
            blurStrength = 16.0f,
            windowScale = 0.88f
        )
        val DEEP_FROST = FloatingWindowAppearanceConfig(
            glassStyle = FloatingWindowGlassStyle.DEEP_FROST,
            transparency = 0.65f,
            blurStrength = 40.0f,
            windowScale = 0.88f
        )
        val SOLID_DARK = FloatingWindowAppearanceConfig(
            glassStyle = FloatingWindowGlassStyle.SOLID_DARK,
            transparency = 0.15f,
            blurStrength = 4.0f,
            windowScale = 0.88f
        )

        // Backward compatibility alias
        val GLASSMORPHISM = LIQUID_GLASS
    }
}

