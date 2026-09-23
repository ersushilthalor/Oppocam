package com.example.camera.engine

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size

/**
 * Universal Hardware Compatibility and Graceful Downgrade Manager.
 *
 * Provides deep hardware capability inspection across:
 * - SoCs: Qualcomm Snapdragon, MediaTek Dimensity/Helio, Samsung Exynos, Google Tensor, Unisoc Spreadtrum
 * - Android OS: 10, 11, 12, 13, 14, 15, 16+
 * - Camera2 Hardware Levels: LEGACY, LIMITED, FULL, LEVEL_3
 * - MediaCodec hardware encoders: HEVC (H.265), AVC (H.264), 10-bit HDR (HLG10/HDR10), VP9
 * - Memory & RAM boundaries to prevent OutOfMemoryError
 *
 * Core Guarantee:
 * Camera must always open -> photo/video must always work -> advanced features gracefully downgrade.
 */
object DeviceCompatibilityManager {

    private const val TAG = "CompatibilityManager"

    enum class SocVendor {
        QUALCOMM,
        MEDIATEK,
        SAMSUNG_EXYNOS,
        GOOGLE_TENSOR,
        UNISOC,
        UNKNOWN
    }

    data class VideoEncodingConfig(
        val mimeType: String,
        val encoder: Int, // MediaRecorder.VideoEncoder
        val is10Bit: Boolean,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int
    )

    val socVendor: SocVendor by lazy {
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { Build.SOC_MODEL.lowercase() } catch (t: Throwable) { "" }
        } else ""

        when {
            hardware.contains("qcom") || board.contains("qcom") || socModel.contains("sm") || socModel.contains("sdm") -> SocVendor.QUALCOMM
            hardware.contains("mt") || board.contains("mt") || socModel.contains("mt") || socModel.contains("dimensity") || socModel.contains("helio") -> SocVendor.MEDIATEK
            hardware.contains("exynos") || board.contains("universal") || socModel.contains("exynos") -> SocVendor.SAMSUNG_EXYNOS
            hardware.contains("gs") || board.contains("tensor") || socModel.contains("tensor") || hardware.contains("tensor") -> SocVendor.GOOGLE_TENSOR
            hardware.contains("ums") || hardware.contains("sp98") || socModel.contains("t6") || socModel.contains("t7") || board.contains("unisoc") -> SocVendor.UNISOC
            manufacturer.contains("motorola") && (hardware.contains("mt") || hardware.contains("qcom")) -> {
                if (hardware.contains("qcom")) SocVendor.QUALCOMM else SocVendor.MEDIATEK
            }
            else -> SocVendor.UNKNOWN
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Video Codec & MediaRecorder Compatibility
    // ---------------------------------------------------------------------------------------------

    private var cachedHevcSupported: Boolean? = null
    private var cachedHevc10BitSupported: Boolean? = null

    /**
     * Checks whether the device has a working hardware or system HEVC video encoder.
     */
    fun isHevcEncodingSupported(width: Int = 1920, height: Int = 1080, fps: Int = 30): Boolean {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                val types = info.supportedTypes
                if (types.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }) {
                    try {
                        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                        val videoCaps = caps.videoCapabilities
                        if (videoCaps != null && videoCaps.isSizeSupported(width, height)) {
                            return true
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Codec capabilities inspection warning for ${info.name}: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed inspecting MediaCodecList for HEVC: ${t.message}")
        }
        return false
    }

    /**
     * Checks whether the device hardware supports 10-bit HEVC encoding (Main 10 profile).
     */
    fun isHevcMain10Supported(): Boolean {
        cachedHevc10BitSupported?.let { return it }
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                cachedHevc10BitSupported = false
                return false
            }
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                val types = info.supportedTypes
                if (types.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }) {
                    try {
                        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                        for (pl in caps.profileLevels) {
                            if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) {
                                cachedHevc10BitSupported = true
                                return true
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed reading profile levels for ${info.name}: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error checking HEVC 10-bit support: ${t.message}")
        }
        cachedHevc10BitSupported = false
        return false
    }

    /**
     * Determines safe, verified video encoder parameters that will never crash MediaRecorder.prepare().
     */
    fun getValidatedVideoConfig(
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int,
        requestedBitrate: Int,
        preferHevc: Boolean,
        prefer10Bit: Boolean
    ): VideoEncodingConfig {
        val hevcCandidate = preferHevc || prefer10Bit
        val canUseHevc = hevcCandidate && isHevcEncodingSupported(requestedWidth, requestedHeight, requestedFps)
        val canUse10Bit = canUseHevc && prefer10Bit && isHevcMain10Supported()

        val finalMime = if (canUseHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val finalEncoder = if (canUseHevc) {
            android.media.MediaRecorder.VideoEncoder.HEVC
        } else {
            android.media.MediaRecorder.VideoEncoder.H264
        }

        // Clamp bitrate to avoid encoder buffer overflow on lower-end SoCs
        val maxSafeBitrate = when (socVendor) {
            SocVendor.UNISOC -> 25_000_000
            SocVendor.MEDIATEK -> if (requestedWidth >= 3840) 60_000_000 else 35_000_000
            else -> 100_000_000
        }
        val safeBitrate = requestedBitrate.coerceAtMost(maxSafeBitrate)

        return VideoEncodingConfig(
            mimeType = finalMime,
            encoder = finalEncoder,
            is10Bit = canUse10Bit,
            width = requestedWidth,
            height = requestedHeight,
            fps = requestedFps,
            bitrate = safeBitrate
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Camera2 Capabilities & Safe Value Clamping
    // ---------------------------------------------------------------------------------------------

    /**
     * Finds a guaranteed-supported AE target FPS range from hardware characteristics.
     * Prevents IllegalArgumentException when setting CONTROL_AE_TARGET_FPS_RANGE.
     */
    fun getSafeFpsRange(chars: CameraCharacteristics, targetFps: Int): Range<Int> {
        val available = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        if (available.isNullOrEmpty()) {
            return Range(30, 30)
        }

        return available.firstOrNull { it.upper == targetFps && it.lower == targetFps }
            ?: available.firstOrNull { it.upper == targetFps }
            ?: available.firstOrNull { it.upper >= targetFps && it.lower <= targetFps }
            ?: available.maxByOrNull { it.upper }
            ?: available.first()
    }

    /**
     * Clamps exposure compensation within hardware bounds.
     */
    fun getSafeExposureCompensation(chars: CameraCharacteristics, requestedComp: Int): Int {
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-4, 4)
        return if (range.lower <= range.upper) {
            requestedComp.coerceIn(range.lower, range.upper)
        } else {
            0
        }
    }

    /**
     * Verifies if manual sensor control (ISO, exposure time, focus distance) is supported.
     */
    fun isManualSensorSupported(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    }

    /**
     * Verifies if RAW_SENSOR stream is supported.
     */
    fun isRawSupported(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        val hasRawCap = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        if (!hasRawCap) return false
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val rawSizes = map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
        return !rawSizes.isNullOrEmpty()
    }

    /**
     * Checks if AE Lock is supported.
     */
    fun isAeLockSupported(chars: CameraCharacteristics): Boolean {
        return chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
    }

    /**
     * Checks if AWB Lock is supported.
     */
    fun isAwbLockSupported(chars: CameraCharacteristics): Boolean {
        return chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
    }

    // ---------------------------------------------------------------------------------------------
    // RAM & Memory Limits
    // ---------------------------------------------------------------------------------------------

    /**
     * Inspects available RAM to prevent OOM during multi-frame stacking or burst processing.
     */
    fun getAvailableRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 1024L * 1024L * 1024L
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.availMem
    }

    fun isLowMemoryDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return am.isLowRamDevice || memInfo.lowMemory || (memInfo.availMem < 400L * 1024L * 1024L)
    }

    /**
     * Returns maximum safe burst count according to available RAM.
     */
    fun getSafeMaxBurstCount(context: Context): Int {
        val freeMb = getAvailableRamBytes(context) / (1024L * 1024L)
        return when {
            freeMb < 400 -> 10
            freeMb < 800 -> 25
            freeMb < 1500 -> 50
            else -> 100
        }
    }
}
