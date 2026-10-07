package com.example.camera.model

import android.media.MediaFormat
import android.media.MediaMuxer

enum class LogBitDepth(val label: String, val bitDepth: Int) {
    OFF("Off", 8),
    BIT_8("8 bit", 8),
    BIT_10("10 bit", 10)
}

enum class CinemaCodec(
    val label: String,
    val description: String,
    val mimeType: String,
    val fileExtension: String,
    val containerFormat: Int,
    val containerMime: String,
    val supports10Bit: Boolean,
    val isSoftwareEncoder: Boolean
) {
    H265(
        "H.265 (HEVC)",
        "High-efficiency video encoder",
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        "mp4",
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        "video/mp4",
        true,
        false
    ),
    H264(
        "H.264 (AVC)",
        "Universal broadcast compatible 8-bit encoder",
        MediaFormat.MIMETYPE_VIDEO_AVC,
        "mp4",
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        "video/mp4",
        false,
        false
    ),
    VP9(
        "VP9",
        "Google VP9 video encoder in WebM container",
        MediaFormat.MIMETYPE_VIDEO_VP9,
        "webm",
        MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
        "video/webm",
        true,
        true
    ),
    PRORES(
        "Apple ProRes 422",
        "Genuine intra-frame 10-bit 4:2:2 mastering codec",
        "video/prores",
        "mov",
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        "video/quicktime",
        true,
        true
    )
}

enum class CinemaColorProfile(
    val label: String,
    val description: String,
    val gammaName: String
) {
    NATIVE("Native", "iPhone-style natural video processing with true-to-life colors, balanced sky/ground, and intelligent shadow recovery", "Native"),
    S_LOG("S-Log", "Original authentic Sony S-Log wide dynamic range logarithmic transfer curve", "S-Log"),
    N_LOG("N-Log", "Original authentic Nikon N-Log 12-stop transfer curve with balanced shadow-to-highlight roll-off", "N-Log"),
    HLG10("HLG10", "ARIB STD-B67 / ITU-R BT.2100 10-bit Hybrid Log-Gamma HDR profile with Rec.2020 wide color gamut", "HLG10"),
    HLG_2("HLG 2", "Enhanced Hybrid Log-Gamma with natural contrast, deep tonal depth, and balanced Rec.709 color rendering", "HLG 2"),
    APPLE_LOG_2("Apple Log 2", "Apple Log 2 wide-gamut log transfer curve with extended highlight latitude and parabolic shadow retention", "Apple Log 2"),
    SAMSUNG_APV_LOG("Samsung APV Log", "Samsung Advanced Professional Video (APV) Log profile with high-efficiency mastering curve, wide dynamic range, and clean shadow-to-highlight roll-off", "Samsung APV Log"),
    PROCESSED_JPEG("Processed JPEG", "Smartphone JPEG photo technical transform with natural contrast and saturation", "Processed JPEG")
}

enum class CinemaColorSpace(val label: String) {
    REC_709("REC.709"),
    REC_2020("REC.2020"),
    DCI_P3("DCI-P3")
}

enum class CinemaAspectRatio(
    val id: String,
    val label: String,
    val ratioValue: Float, // width / height in landscape (or height / width in portrait)
    val displayName: String
) {
    RATIO_16_9("16_9", "16:9", 16f / 9f, "16:9 Widescreen"),
    IMAX("imax", "IMAX", 1.43f, "IMAX (1.43:1)"),
    CINEMATIC("cinematic", "2.39:1", 2.39f, "Cinematic (2.39:1)");

    companion object {
        fun fromId(id: String?): CinemaAspectRatio =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: RATIO_16_9
    }
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
    val aspectRatio: CinemaAspectRatio = CinemaAspectRatio.RATIO_16_9,
    val logBitDepth: LogBitDepth = LogBitDepth.BIT_8,
    val codec: CinemaCodec = CinemaCodec.H265,
    val selectedLut: CinematicLut = CinematicLut.OFF, // Default OFF
    val customLutPath: String? = null,
    val customLutName: String? = null,
    val colorProfile: CinemaColorProfile = CinemaColorProfile.NATIVE,
    val colorSpace: CinemaColorSpace = CinemaColorSpace.REC_709,
    val isRawSensorLogPipeline: Boolean = false, // Clean cinematic video pipeline without raw JPEG sensor simulation
    val isFocusPeakingEnabled: Boolean = false,
    val isWaveformEnabled: Boolean = false,
    val zebraThreshold: ZebraThreshold = ZebraThreshold.OFF,
    val videoFps: Int = 24, // 24 fps cinematic standard
    val selectedResolution: CameraResolution? = null,
    val videoBitrate: VideoBitrateOption = VideoBitrateOption.AUTO,
    // Real Cinema Advanced ISP Parameters
    val shadows: Float = 0.0f, // -1.0f (deep/crushed) to +1.0f (lifted shadow toe)
    val highlights: Float = 0.0f, // -1.0f (compressed/protected) to +1.0f (boosted highlight shoulder)
    val contrast: Float = 0.0f, // -1.0f (flat latitude) to +1.0f (punchy cinematic S-curve)
    val exposure: Float = 0.0f, // -1.0f to +1.0f real-time live exposure slider
    val washedOut: Float = 0.0f, // 0.0f (pure LOG/HLG10) to 1.0f (progressive reduction of washed-out appearance with contrast/saturation recovery)
    val brilliance: Float = 0.0f, // -1.0f to +1.0f intelligent tone-mapping balancing shadows, midtones, and highlights
    val saturation: Float = 1.0f, // 0.0f (monochrome/desaturated) to 2.0f (vibrant) via 3x3 color gamut matrix
    val vibrance: Float = 0.0f, // -1.0f to +1.0f selective vibrance with skin-tone protection
    val sharpness: CinemaSharpness = CinemaSharpness.CRISP, // Smartphone-style crisp detail enhancement by default
    val noiseReduction: CinemaNoiseReduction = CinemaNoiseReduction.OFF, // Default OFF on initial install; persists across restarts
    val exposureCompensation: Int = 0, // Real Camera2 EV steps (e.g. -6..+6)
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val manualIso: Int? = null, // null for Auto, or 50, 100, 200, 400, 800, 1600, 3200
    val manualShutterSpeedNs: Long? = null, // null for Auto, or 1/24s, 1/48s (180°), 1/50s, 1/96s, 1/120s
    val isBakeLutToOutput: Boolean = true, // Default true: LUT is baked to output video automatically
    val isLutPreviewEnabled: Boolean = true, // Default true: LUT preview is always active
    val lutIntensity: Float = 1.0f, // 0.0f (0% neutral baseline) to 1.0f (100% full LUT grade)

    // Cinema Color Fine-Tuning Controls (18 Authentic ISP & Grading parameters)
    // 1. Exposure / White Balance
    val temperature: Float = 0.0f,       // -1.0f (Cool/Blue) to +1.0f (Warm/Amber)
    val tint: Float = 0.0f,              // -1.0f (Magenta) to +1.0f (Green)
    // 2. Tonal Adjustments
    val whites: Float = 0.0f,            // -1.0f to +1.0f (High specular luminance adjustment)
    val blacks: Float = 0.0f,            // -1.0f to +1.0f (Deep shadow luminance adjustment)
    val midtones: Float = 0.0f,          // -1.0f to +1.0f (Middle-gray luminance adjustment)
    val blackLevel: Float = 0.0f,        // -1.0f to +1.0f (Black pedestal baseline offset)
    val highlightRolloff: Float = 0.0f,  // -1.0f to +1.0f (Smooth highlight transition & knee roll-off)
    val shadowRolloff: Float = 0.0f,     // -1.0f to +1.0f (Smooth shadow transition & toe roll-off)
    val localContrast: Float = 0.0f,     // -1.0f to +1.0f (Clarity & local contrast enhancement)
    val lumaCurve: Float = 0.0f,         // -1.0f to +1.0f (Non-linear luminance curve shaping)
    // 3. Color Transform
    val colorTransform: Float = 0.0f,    // -1.0f to +1.0f (RGB color matrix cross-talk & gamut separation)
    val chromaStrength: Float = 1.0f,    // 0.0f (0% mono) to 2.0f (200% chroma), default 1.0f
    // 4. Tone Mapping
    val toneMappingStrength: Float = 0.0f, // 0.0f (Linear/Off) to 1.0f (ACES filmic tone mapping)
    // 5. Noise Reduction
    val lumaNoiseReduction: Float = 0.0f,   // 0.0f (Off) to 1.0f (Max luma spatial bilateral filtering)
    val chromaNoiseReduction: Float = 0.0f, // 0.0f (Off) to 1.0f (Max chroma color despecle filtering)
    // 6. Detail / Sharpening
    val fineSharpening: Float = 0.0f,    // 0.0f (Off) to 1.0f (High-pass unsharp mask convolution)
    val microContrast: Float = 0.0f,     // -1.0f to +1.0f (Fine texture & micro-detail enhancement)
    // 7. Output Gamma
    val outputGamma: Float = 1.0f        // 0.5f to 1.5f (Final transfer power function, default 1.0f neutral)
) {
    val sharpening: Float get() = fineSharpening
    val highlightRollOff: Float get() = highlightRolloff
    val shadowRollOff: Float get() = shadowRolloff
    val hasFineTuning: Boolean get() = hasColorFineTuning

    val hasColorFineTuning: Boolean
        get() = temperature != 0.0f || tint != 0.0f || whites != 0.0f || blacks != 0.0f ||
                midtones != 0.0f || blackLevel != 0.0f || highlightRolloff != 0.0f || shadowRolloff != 0.0f ||
                localContrast != 0.0f || lumaCurve != 0.0f || colorTransform != 0.0f || chromaStrength != 1.0f ||
                lumaNoiseReduction != 0.0f || chromaNoiseReduction != 0.0f || fineSharpening != 0.0f ||
                microContrast != 0.0f || toneMappingStrength != 0.0f || outputGamma != 1.0f || brilliance != 0.0f
    val isHlg10: Boolean get() = colorProfile == CinemaColorProfile.HLG10
    val effectiveColorSpace: CinemaColorSpace get() = if (colorProfile == CinemaColorProfile.HLG10) CinemaColorSpace.REC_2020 else colorSpace
    val effectiveBitDepth: LogBitDepth get() = logBitDepth
    val effectiveCodec: CinemaCodec get() = codec
    val isLogMode: Boolean get() = colorProfile != CinemaColorProfile.NATIVE || logBitDepth != LogBitDepth.OFF
    val activeLut: CinematicLut get() = selectedLut
    val shouldBakeLut: Boolean get() = !selectedLut.isOff && isBakeLutToOutput
    val isLutPreviewOnly: Boolean get() = false
}

data class CinemaHardwareCapabilities(
    val supportsContrastCurve: Boolean = false,
    val supports10BitRecording: Boolean = false,
    val supportsHevc10Bit: Boolean = false,
    val supportsVp910Bit: Boolean = false,
    val supportsDynamicRangeProfiles: Boolean = false,
    val supportsRawSensorBypass: Boolean = true,
    val supportsSoftwareVp9: Boolean = false,
    val supportsSoftwareProRes: Boolean = true,
    val isSoftware10BitSupported: Boolean = true,
    val supportedFpsList: List<Int> = listOf(24, 30, 60),
    val supportedResolutions: List<CameraResolution> = emptyList(),
    val isHardwareLogSupported: Boolean = false,
    val is10BitAvailableOnHAL: Boolean = false,
    val supportsEndToEnd10Bit: Boolean = false,
    val supportedBitDepths: List<LogBitDepth> = listOf(LogBitDepth.OFF, LogBitDepth.BIT_8),
    val supportedCodecs: List<CinemaCodec> = listOf(CinemaCodec.H265, CinemaCodec.H264),
    val supportedColorProfiles: List<CinemaColorProfile> = listOf(
        CinemaColorProfile.NATIVE,
        CinemaColorProfile.S_LOG,
        CinemaColorProfile.N_LOG,
        CinemaColorProfile.HLG10,
        CinemaColorProfile.HLG_2,
        CinemaColorProfile.APPLE_LOG_2,
        CinemaColorProfile.SAMSUNG_APV_LOG,
        CinemaColorProfile.PROCESSED_JPEG
    )
) {
    /**
     * Returns the bit depths that the selected codec can ACTUALLY encode.
     * 10-bit option is only present if the selected encoder can genuinely output valid 10-bit files.
     */
    fun getSupportedBitDepthsForCodec(codec: CinemaCodec): List<LogBitDepth> {
        return when (codec) {
            CinemaCodec.PRORES -> listOf(LogBitDepth.OFF, LogBitDepth.BIT_8, LogBitDepth.BIT_10)
            CinemaCodec.H265 -> {
                if (supportsHevc10Bit && (supports10BitRecording || is10BitAvailableOnHAL)) {
                    listOf(LogBitDepth.OFF, LogBitDepth.BIT_8, LogBitDepth.BIT_10)
                } else {
                    listOf(LogBitDepth.OFF, LogBitDepth.BIT_8)
                }
            }
            CinemaCodec.VP9 -> {
                if (supportsVp910Bit && (supports10BitRecording || is10BitAvailableOnHAL)) {
                    listOf(LogBitDepth.OFF, LogBitDepth.BIT_8, LogBitDepth.BIT_10)
                } else {
                    listOf(LogBitDepth.OFF, LogBitDepth.BIT_8)
                }
            }
            CinemaCodec.H264 -> listOf(LogBitDepth.OFF, LogBitDepth.BIT_8)
        }
    }
}
