package com.example.camera.model

import android.graphics.ImageFormat
import android.graphics.RectF
import android.util.Size

enum class CameraMode(val title: String) {
    PHOTO("Photo"),
    PORTRAIT("Portrait"),
    VIDEO("Video"),
    CINEMA("Cinema"),
    NIGHT("Night"),
    MORE("More"),
    AI_SUBJECT_TRACKING("AI Tracking")
}

data class PortraitConfig(
    val blurStrength: Float = 60f, // 0..100
    val simulatedAperture: String = "f/1.4", // f/0.95, f/1.2, f/1.4, f/1.8, f/2.4, f/2.8
    val bokehStyle: BokehStyle = BokehStyle.NATURAL_ROUND,
    val selectedStyle: PortraitStyle = PortraitStyle.NATURAL,
    val faceEnhancement: Boolean = false,
    val skinToneCorrection: Boolean = false,
    val showDepthPreview: Boolean = false,
    val opticalBlurGuided: Boolean = true
)

enum class PortraitStyle(
    val title: String,
    val subtitle: String,
    val defaultBlur: Float,
    val bokehStyle: BokehStyle,
    val simulatedAperture: String,
    val warmCoolTint: Float, // -1.0 (cool) to +1.0 (warm)
    val contrastBoost: Float,
    val saturationBoost: Float,
    val claritySharpen: Float,
    val skinSmoothing: Boolean,
    val highlightGlow: Float,
    val isZeissOptical: Boolean = false,
    val isLeicaOptical: Boolean = false
) {
    NATURAL(
        title = "Natural",
        subtitle = "Authentic true-to-life tone & organic depth",
        defaultBlur = 55f,
        bokehStyle = BokehStyle.NATURAL_ROUND,
        simulatedAperture = "f/1.8",
        warmCoolTint = 0.0f,
        contrastBoost = 0.0f,
        saturationBoost = 1.0f,
        claritySharpen = 0.0f,
        skinSmoothing = false,
        highlightGlow = 0.0f
    ),

    WEDDING(
        title = "Wedding",
        subtitle = "Soft ethereal glow, creamy bokeh & warm pastel highlights",
        defaultBlur = 75f,
        bokehStyle = BokehStyle.SOFT_ELLIPTICAL,
        simulatedAperture = "f/1.2",
        warmCoolTint = 0.22f,
        contrastBoost = -0.06f,
        saturationBoost = 1.06f,
        claritySharpen = -0.10f,
        skinSmoothing = true,
        highlightGlow = 0.28f
    ),

    FESTIVAL(
        title = "Festival",
        subtitle = "Vibrant colors, rich saturated highlights & dynamic discs",
        defaultBlur = 65f,
        bokehStyle = BokehStyle.LIGHT_SOURCE,
        simulatedAperture = "f/1.4",
        warmCoolTint = 0.14f,
        contrastBoost = 0.16f,
        saturationBoost = 1.28f,
        claritySharpen = 0.12f,
        skinSmoothing = false,
        highlightGlow = 0.0f
    ),

    TEXTURED(
        title = "Textured",
        subtitle = "Cinematic fine film character & structured aperture bokeh",
        defaultBlur = 60f,
        bokehStyle = BokehStyle.POLYGONAL_APERTURE,
        simulatedAperture = "f/2.0",
        warmCoolTint = -0.05f,
        contrastBoost = 0.24f,
        saturationBoost = 0.90f,
        claritySharpen = 0.35f,
        skinSmoothing = false,
        highlightGlow = 0.0f
    ),

    CRISP(
        title = "Crisp",
        subtitle = "High subject separation, micro-contrast & clean blur",
        defaultBlur = 70f,
        bokehStyle = BokehStyle.NATURAL_ROUND,
        simulatedAperture = "f/1.4",
        warmCoolTint = 0.0f,
        contrastBoost = 0.20f,
        saturationBoost = 1.08f,
        claritySharpen = 0.45f,
        skinSmoothing = false,
        highlightGlow = 0.0f
    ),

    LOW_LIGHT(
        title = "Low Light",
        subtitle = "Noise-controlled shadow lift & glowing specular discs",
        defaultBlur = 80f,
        bokehStyle = BokehStyle.LIGHT_SOURCE,
        simulatedAperture = "f/0.95",
        warmCoolTint = 0.20f,
        contrastBoost = 0.08f,
        saturationBoost = 1.12f,
        claritySharpen = 0.05f,
        skinSmoothing = true,
        highlightGlow = 0.18f
    ),

    // Real ZEISS Optical Integrations
    ZEISS_BIOTAR(
        title = "ZEISS Biotar",
        subtitle = "Legendary 75mm f/1.5 swirly cat-eye bokeh & T* high micro-contrast clarity",
        defaultBlur = 78f,
        bokehStyle = BokehStyle.ZEISS_SWIRL,
        simulatedAperture = "f/1.5",
        warmCoolTint = 0.08f,
        contrastBoost = 0.28f,
        saturationBoost = 1.16f,
        claritySharpen = 0.50f,
        skinSmoothing = false,
        highlightGlow = 0.0f,
        isZeissOptical = true
    ),

    ZEISS_SONNAR(
        title = "ZEISS Sonnar",
        subtitle = "Classic 85mm f/1.8 creamy optical defocus & authentic warm skin tones",
        defaultBlur = 72f,
        bokehStyle = BokehStyle.NATURAL_ROUND,
        simulatedAperture = "f/1.8",
        warmCoolTint = 0.15f,
        contrastBoost = 0.14f,
        saturationBoost = 1.08f,
        claritySharpen = 0.38f,
        skinSmoothing = true,
        highlightGlow = 0.05f,
        isZeissOptical = true
    ),

    // Real Leica Optical Integrations
    LEICA_NOCTILUX(
        title = "Leica Noctilux",
        subtitle = "Iconic 50mm f/0.95 3D subject pop, inky black retention & liquid blur",
        defaultBlur = 88f,
        bokehStyle = BokehStyle.LEICA_3D_POP,
        simulatedAperture = "f/0.95",
        warmCoolTint = 0.05f,
        contrastBoost = 0.32f,
        saturationBoost = 1.04f,
        claritySharpen = 0.55f,
        skinSmoothing = false,
        highlightGlow = 0.0f,
        isLeicaOptical = true
    ),

    LEICA_SUMMILUX(
        title = "Leica Summilux",
        subtitle = "Master 35mm f/1.4 extreme micro-contrast, vibrant natural tones & crisp detail",
        defaultBlur = 68f,
        bokehStyle = BokehStyle.NATURAL_ROUND,
        simulatedAperture = "f/1.4",
        warmCoolTint = 0.04f,
        contrastBoost = 0.22f,
        saturationBoost = 1.12f,
        claritySharpen = 0.48f,
        skinSmoothing = false,
        highlightGlow = 0.0f,
        isLeicaOptical = true
    );

    val displayName: String get() = title
    val description: String get() = subtitle
}

enum class BokehStyle(val label: String, val description: String) {
    NATURAL_ROUND("Round", "Classic circular optical bokeh"),
    SOFT_ELLIPTICAL("Elliptical", "Anamorphic cat-eye bokeh"),
    POLYGONAL_APERTURE("Polygonal", "Aperture blade bokeh"),
    LIGHT_SOURCE("Light Source", "Glowing optical highlight discs"),
    ZEISS_SWIRL("Biotar Swirl", "ZEISS Biotar swirly optical lens bokeh"),
    LEICA_3D_POP("Noctilux Cream", "Leica f/0.95 smooth 3D bokeh falloff");

    companion object {
        val NATURAL: BokehStyle get() = NATURAL_ROUND
        val STRONG: BokehStyle get() = LIGHT_SOURCE
    }
}

data class PortraitProcessingState(
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = ""
)

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
    RATIO_FULL("FULL", 0f, true)
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
    NORMAL("Normal", "1080p (Optimized battery & latency)", 1920),
    HIGH("High (~2K)", "1440p Quad-HD clarity", 2560),
    MAX("Max (~4K)", "Full sensor resolution preview", 4096)
}

enum class ColorProfile(val title: String, val isFlat: Boolean) {
    STANDARD("Standard", false),
    VIBRANT("Vibrant", false),
    NATURAL("Natural", false),
    FLAT_LOG("Flat / Log", true),
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
    val maxZoomRatio: Float = 10.0f,
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
    val maxZoom: Float = 8f
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
    val isEisPreferred: Boolean = true,
    val isAdaptiveFpsLens: Boolean = true,
    val isUltraStabilizationEnabled: Boolean = false,
    val isEisOnly: Boolean = false,
    val oisHardwareStatus: String = "Detecting",
    val eisHardwareStatus: String = "Detecting",
    val ultraStabilizationStatus: String = "Ready"
) {
    val stabilizationMode: MainCameraStabilizationMode
        get() = when {
            isUltraStabilizationEnabled -> MainCameraStabilizationMode.ULTRA
            isEisOnly || (!isOisPreferred && isEisPreferred) -> MainCameraStabilizationMode.EIS_ONLY
            isOisPreferred && !isEisPreferred -> MainCameraStabilizationMode.OIS_ONLY
            isHybridEnabled || (isOisPreferred && isEisPreferred) -> MainCameraStabilizationMode.HYBRID_OIS_EIS
            else -> MainCameraStabilizationMode.OFF
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
    FALLBACK_TURBO("Turbo Handover", "Fast normal switching active"),
    UNAVAILABLE("Unavailable", "Camera not present or in use")
}

data class MotorolaInstantSwitchState(
    val isShowUltraWidePreview: Boolean = false,
    val isKeepFrontCameraReady: Boolean = false,
    val isShowFrontCameraPreview: Boolean = false,
    val ultraWideStatus: BackgroundCameraStatus = BackgroundCameraStatus.OFF,
    val frontStatus: BackgroundCameraStatus = BackgroundCameraStatus.OFF,
    val isMotorolaDevice: Boolean = false,
    val isConcurrentHardwareSupported: Boolean = true,
    val activeStandbyLens: LensType? = null,
    val switchLatencyEstimateMs: Int = 15,
    val lastMeasuredLatencyMs: Long = 0L,
    val statusMessage: String = "Motorola Instant Switching Ready"
)

/**
 * Floating Window Appearance Configuration:
 * Controls the live backdrop blur strength and transparency across all floating windows & popups.
 *
 * @param transparency 0.0f (opaque/solid glass) to 1.0f (crystal clear / maximum backdrop visibility)
 * @param blurStrength 0.0f (sharp/no blur) to 50.0f (deep creamy optical frosted diffusion)
 */
data class FloatingWindowAppearanceConfig(
    val transparency: Float = 0.50f, // Default 50% transparency
    val blurStrength: Float = 24.0f   // Default 24 dp blur
) {
    val transparencyPercent: Int get() = kotlin.math.round((transparency * 100f)).toInt().coerceIn(0, 100)
    val blurStrengthDp: Int get() = kotlin.math.round(blurStrength).toInt().coerceIn(0, 50)

    companion object {
        val GLASSMORPHISM = FloatingWindowAppearanceConfig(transparency = 0.50f, blurStrength = 24.0f)
        val SUBTLE_FROST = FloatingWindowAppearanceConfig(transparency = 0.35f, blurStrength = 14.0f)
        val DEEP_FROST = FloatingWindowAppearanceConfig(transparency = 0.70f, blurStrength = 38.0f)
        val SOLID_DARK = FloatingWindowAppearanceConfig(transparency = 0.15f, blurStrength = 8.0f)
    }
}

