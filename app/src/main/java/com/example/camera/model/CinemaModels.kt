package com.example.camera.model

enum class LogBitDepth(val label: String, val bitDepth: Int) {
    OFF("Off", 8),
    BIT_8("8 bit", 8),
    BIT_10("10 bit", 10)
}

enum class CinemaCodec(
    val label: String,
    val description: String,
    val fileExtension: String,
    val supports10Bit: Boolean,
    val isSoftwareEncoder: Boolean
) {
    H265("H.265 / HEVC", "High-efficiency hardware/software encoder", "mp4", true, false),
    H264("H.264 / AVC", "Universal broadcast compatible 8-bit encoder", "mp4", false, false),
    VP9("VP9 (Software / HW)", "Google VP9 Profile 0/2 software 10-bit encoder", "webm", true, true),
    PRORES("Apple ProRes 422", "Genuine intra-frame 10-bit 4:2:2 mastering codec", "mov", true, true)
}

enum class CinemaColorProfile(
    val label: String,
    val description: String,
    val gammaName: String
) {
    PROCESSED_JPEG("Processed Photo", "Smartphone JPEG photo rendering from RAW sensor: deep controlled blacks, punchy midtones, rich shadows & highlight roll-off", "Processed JPEG"),
    NATIVE("Native", "iPhone-style natural video processing with true-to-life colors, balanced sky/ground, and intelligent shadow recovery", "Native"),
    FLAT_LOG("Flat", "Logarithmic dynamic range curve for color grading", "Flat Log"),
    REC_2020("Rec.2020", "ITU-R BT.2020 wide color gamut transfer curve", "BT.2020"),
    HLG("HLG", "ITU-R BT.2100 Hybrid Log-Gamma HDR profile", "HLG"),
    APPLE_LOG_2("Apple Log 2", "Apple Log 2 wide-gamut log transfer curve with extended highlight latitude and parabolic shadow retention", "Apple Log 2"),
    SAMSUNG_APV_LOG("Samsung APV Log", "Samsung Advanced Professional Video (APV) Log profile with high-efficiency mastering curve, wide dynamic range, and clean shadow-to-highlight roll-off", "Samsung APV Log")
}

enum class CinemaColorSpace(val label: String) {
    REC_709("REC.709"),
    REC_2020("REC.2020"),
    DCI_P3("DCI-P3")
}

enum class ZebraThreshold(val label: String, val thresholdIre: Int) {
    OFF("Off", 0),
    IRE_70("70", 70),
    IRE_100("100", 100)
}

enum class CinemaSharpness(val label: String, val edgeMode: Int) {
    OFF("Off (Filmic)", android.hardware.camera2.CaptureRequest.EDGE_MODE_OFF),
    NATURAL("Natural", android.hardware.camera2.CaptureRequest.EDGE_MODE_FAST),
    CRISP("Crisp", android.hardware.camera2.CaptureRequest.EDGE_MODE_HIGH_QUALITY)
}

enum class CinemaNoiseReduction(val label: String, val mode: Int) {
    OFF("Off", android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_OFF),
    LOW("Low", android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL),
    MEDIUM("Medium", android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_FAST),
    HIGH("High", android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
}

data class CinemaConfig(
    val logBitDepth: LogBitDepth = LogBitDepth.BIT_10,
    val codec: CinemaCodec = CinemaCodec.H265,
    val selectedLut: CinematicLut = CinematicLut.REC_709, // Default Rec.709 as requested
    val customLutPath: String? = null,
    val customLutName: String? = null,
    val colorProfile: CinemaColorProfile = CinemaColorProfile.PROCESSED_JPEG,
    val colorSpace: CinemaColorSpace = CinemaColorSpace.REC_709,
    val isRawSensorLogPipeline: Boolean = true, // RAW sensor stream source with full ISP processing into smartphone JPEG photo rendering
    val isFocusPeakingEnabled: Boolean = false,
    val isWaveformEnabled: Boolean = false,
    val zebraThreshold: ZebraThreshold = ZebraThreshold.IRE_70,
    val videoFps: Int = 24, // 24 fps cinematic standard
    val selectedResolution: CameraResolution? = null,
    // Real Cinema Advanced ISP Parameters
    val shadows: Float = 0.0f, // -1.0f (deep/crushed) to +1.0f (lifted shadow toe)
    val highlights: Float = 0.0f, // -1.0f (compressed/protected) to +1.0f (boosted highlight shoulder)
    val contrast: Float = 0.0f, // -1.0f (flat latitude) to +1.0f (punchy cinematic S-curve)
    val exposure: Float = 0.0f, // -1.0f to +1.0f real-time live exposure slider
    val washedOut: Float = 0.0f, // 0.0f (pure LOG/HLG) to 1.0f (progressive reduction of washed-out appearance with contrast/saturation recovery)
    val saturation: Float = 1.0f, // 0.0f (monochrome/desaturated) to 2.0f (vibrant) via 3x3 color gamut matrix
    val sharpness: CinemaSharpness = CinemaSharpness.CRISP, // Smartphone-style crisp detail enhancement by default
    val noiseReduction: CinemaNoiseReduction = CinemaNoiseReduction.OFF, // Default OFF on initial install; persists across restarts
    val exposureCompensation: Int = 0, // Real Camera2 EV steps (e.g. -6..+6)
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val manualIso: Int? = null, // null for Auto, or 50, 100, 200, 400, 800, 1600, 3200
    val manualShutterSpeedNs: Long? = null, // null for Auto, or 1/24s, 1/48s (180°), 1/50s, 1/96s, 1/120s
    val isBakeLutToOutput: Boolean = true, // Default true: LUT is baked to output video automatically
    val isLutPreviewEnabled: Boolean = true, // Default true: LUT preview is always active
    val lutIntensity: Float = 1.0f // 0.0f (0% neutral baseline) to 1.0f (100% full LUT grade)
) {
    val isLogMode: Boolean get() = (colorProfile != CinemaColorProfile.NATIVE && colorProfile != CinemaColorProfile.PROCESSED_JPEG) || logBitDepth != LogBitDepth.OFF
    val activeLut: CinematicLut get() = selectedLut
    val shouldBakeLut: Boolean get() = selectedLut != CinematicLut.NONE && isBakeLutToOutput
    val isLutPreviewOnly: Boolean get() = false
}

data class CinemaHardwareCapabilities(
    val supportsContrastCurve: Boolean = false,
    val supports10BitRecording: Boolean = false,
    val supportsHevc10Bit: Boolean = false,
    val supportsDynamicRangeProfiles: Boolean = false,
    val supportsRawSensorBypass: Boolean = true,
    val supportsSoftwareVp9: Boolean = true,
    val supportsSoftwareProRes: Boolean = true,
    val isSoftware10BitSupported: Boolean = true,
    val supportedFpsList: List<Int> = listOf(24, 30, 60),
    val supportedResolutions: List<CameraResolution> = emptyList(),
    val isHardwareLogSupported: Boolean = false,
    val is10BitAvailableOnHAL: Boolean = false
)
