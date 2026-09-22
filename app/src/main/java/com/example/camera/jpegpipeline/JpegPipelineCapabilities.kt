package com.example.camera.jpegpipeline

/**
 * Diagnostics and capability detector for the Camera2 Image Signal Processor (ISP).
 *
 * Details the hardware features exposed by the device's HAL for single-frame photo-style
 * processing prior to hardware video encoding.
 */
data class JpegPipelineCapabilities(
    val isAvailable: Boolean = true,
    val isHardwareNoiseReductionSupported: Boolean = true,
    val supportedNoiseReductionModes: List<Int> = emptyList(),
    val isHardwareEdgeModeSupported: Boolean = true,
    val supportedEdgeModes: List<Int> = emptyList(),
    val isTonemapCurveSupported: Boolean = false,
    val supportedTonemapModes: List<Int> = emptyList(),
    val isColorCorrectionTransformSupported: Boolean = false,
    val isShadingModeSupported: Boolean = true,
    val supportedShadingModes: List<Int> = emptyList(),
    val isHotPixelModeSupported: Boolean = true,
    val maxTonemapCurvePoints: Int = 0,
    val architectureSummary: String = "Sensor → Camera ISP (Photo Rendering) → YUV Frame → Video Encoder → MP4",
    val unexposedParametersNotice: String = ""
) {
    val tonemapStatusLabel: String
        get() = if (isTonemapCurveSupported) {
            "Hardware Contrast S-Curve ($maxTonemapCurvePoints pts)"
        } else {
            "ISP Automatic High-Quality Tonemap"
        }

    val edgeStatusLabel: String
        get() = if (isHardwareEdgeModeSupported) {
            "Hardware ISP Edge Detail & Sharpening"
        } else {
            "Default HAL Sharpening"
        }

    val colorTransformStatusLabel: String
        get() = if (isColorCorrectionTransformSupported) {
            "Hardware 3x3 Color Matrix & Channel Gains"
        } else {
            "Standard ISP Auto Color Matrix"
        }

    val shadingStatusLabel: String
        get() = if (isShadingModeSupported) {
            "Lens Vignette & Shading Correction Active"
        } else {
            "Fixed Lens Profile"
        }
}
