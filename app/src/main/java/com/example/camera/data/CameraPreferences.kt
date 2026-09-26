package com.example.camera.data

import android.content.Context
import android.content.SharedPreferences
import com.example.camera.model.*

/**
 * Persists user camera and video settings across sessions.
 */
class CameraPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pro_camera_user_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CAMERA_MODE = "pref_camera_mode"
        private const val KEY_FLASH_MODE = "pref_flash_mode"
        private const val KEY_TIMER_MODE = "pref_timer_mode"
        private const val KEY_GRID_TYPE = "pref_grid_type"
        private const val KEY_RAW_ENABLED = "pref_raw_enabled"
        private const val KEY_VIDEO_QUALITY = "pref_video_quality" // e.g. "4K 30", "1080p 30", "1080p 60", "720p 30"
        private const val KEY_VIDEO_WIDTH = "pref_video_width"
        private const val KEY_VIDEO_HEIGHT = "pref_video_height"
        private const val KEY_VIDEO_FPS = "pref_video_fps"
        private const val KEY_VIDEO_BITRATE = "pref_video_bitrate"
        private const val KEY_VIDEO_STABILIZATION = "pref_video_stabilization"
        private const val KEY_AUDIO_ENABLED = "pref_audio_enabled"
        private const val KEY_COLOR_PROFILE = "pref_color_profile"
        private const val KEY_WHITE_BALANCE = "pref_white_balance"
        private const val KEY_FOCUS_MODE = "pref_focus_mode"
        private const val KEY_LAST_FACING = "pref_last_facing"
        private const val KEY_PORTRAIT_BLUR = "pref_portrait_blur"
        private const val KEY_PORTRAIT_APERTURE = "pref_portrait_aperture"
        private const val KEY_PORTRAIT_OPTICAL_BLUR_GUIDED = "pref_portrait_optical_blur_guided"
        private const val KEY_SAVE_SELFIE_AS_PREVIEWED = "pref_save_selfie_as_previewed"
        private const val KEY_VIEWFINDER_RESOLUTION = "pref_viewfinder_resolution"
        private const val KEY_REFOCUS_PHOTO_ENABLED = "pref_refocus_photo_enabled"
        private const val KEY_REFOCUS_FRAME_COUNT = "pref_refocus_frame_count"
        private const val KEY_ULTRA_FAST_SHUTTER_ENABLED = "pref_ultra_fast_shutter_enabled"
        private const val KEY_ULTRA_FAST_SHUTTER_FPS = "pref_ultra_fast_shutter_fps"
        private const val KEY_ULTRA_FAST_SHUTTER_BURST_COUNT = "pref_ultra_fast_shutter_burst_count"
        private const val KEY_HQ_ZOOM_ENABLED = "pref_hq_zoom_enabled"
        private const val KEY_ZOOM_PROCESSING_QUALITY = "pref_zoom_processing_quality"
        private const val KEY_TRACKING_LENS = "pref_tracking_lens"
        private const val KEY_SHOW_ULTRAWIDE_PREVIEW = "pref_show_ultrawide_preview"
        private const val KEY_KEEP_FRONT_READY = "pref_keep_front_ready"
        private const val KEY_SHOW_FRONT_PREVIEW = "pref_show_front_preview"
        private const val KEY_FLOATING_WINDOW_TRANSPARENCY = "pref_floating_window_transparency"
        private const val KEY_FLOATING_WINDOW_BLUR_STRENGTH = "pref_floating_window_blur_strength"
        private const val KEY_PREFERRED_GALLERY_PACKAGE = "pref_preferred_gallery_package"
        private const val KEY_HAS_PROMPTED_GALLERY = "pref_has_prompted_gallery"
        private const val KEY_ZOOM_PRESETS_MODE = "pref_zoom_presets_mode"
        private const val KEY_CUSTOM_ZOOM_PRESETS = "pref_custom_zoom_presets"
    }

    var zoomPresetsMode: String
        get() = prefs.getString(KEY_ZOOM_PRESETS_MODE, "STANDARD") ?: "STANDARD"
        set(value) = prefs.edit().putString(KEY_ZOOM_PRESETS_MODE, value).apply()

    var customZoomPresetsStr: String
        get() = prefs.getString(KEY_CUSTOM_ZOOM_PRESETS, "1x, 2x, 4x, 8x") ?: "1x, 2x, 4x, 8x"
        set(value) = prefs.edit().putString(KEY_CUSTOM_ZOOM_PRESETS, value).apply()

    fun getEffectiveZoomPresets(hasUltraWide: Boolean): List<Float> {
        return when (zoomPresetsMode) {
            "POWERS_OF_TWO" -> if (hasUltraWide) listOf(0.5f, 1.0f, 2.0f, 4.0f, 8.0f) else listOf(1.0f, 2.0f, 4.0f, 8.0f)
            "CINEMATIC" -> if (hasUltraWide) listOf(0.5f, 1.0f, 2.0f, 3.0f, 6.0f, 10.0f) else listOf(1.0f, 2.0f, 3.0f, 6.0f, 10.0f)
            "CUSTOM" -> {
                val cleaned = customZoomPresetsStr.replace("x", "", ignoreCase = true)
                val parsed = cleaned.split(",")
                    .mapNotNull { it.trim().toFloatOrNull() }
                    .filter { it in 0.35f..100f }
                    .distinct()
                    .sorted()
                if (parsed.isNotEmpty()) parsed else if (hasUltraWide) listOf(0.5f, 1.0f, 2.0f, 4.0f, 8.0f) else listOf(1.0f, 2.0f, 4.0f, 8.0f)
            }
            else -> if (hasUltraWide) listOf(0.5f, 1.0f, 2.0f, 3.0f, 5.0f, 10.0f) else listOf(1.0f, 2.0f, 3.0f, 5.0f, 10.0f)
        }
    }

    var preferredGalleryPackage: String?
        get() = prefs.getString(KEY_PREFERRED_GALLERY_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_PREFERRED_GALLERY_PACKAGE, value).apply()

    var hasPromptedGallerySelection: Boolean
        get() = prefs.getBoolean(KEY_HAS_PROMPTED_GALLERY, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_PROMPTED_GALLERY, value).apply()

    var floatingWindowTransparency: Float
        get() = prefs.getFloat(KEY_FLOATING_WINDOW_TRANSPARENCY, 0.50f)
        set(value) = prefs.edit().putFloat(KEY_FLOATING_WINDOW_TRANSPARENCY, value).apply()

    var floatingWindowBlurStrength: Float
        get() = prefs.getFloat(KEY_FLOATING_WINDOW_BLUR_STRENGTH, 24.0f)
        set(value) = prefs.edit().putFloat(KEY_FLOATING_WINDOW_BLUR_STRENGTH, value).apply()

    fun getFloatingWindowAppearance(): com.example.camera.model.FloatingWindowAppearanceConfig {
        return com.example.camera.model.FloatingWindowAppearanceConfig(
            transparency = floatingWindowTransparency,
            blurStrength = floatingWindowBlurStrength
        )
    }

    fun saveFloatingWindowAppearance(config: com.example.camera.model.FloatingWindowAppearanceConfig) {
        prefs.edit()
            .putFloat(KEY_FLOATING_WINDOW_TRANSPARENCY, config.transparency)
            .putFloat(KEY_FLOATING_WINDOW_BLUR_STRENGTH, config.blurStrength)
            .apply()
    }

    var isShowUltraWidePreview: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ULTRAWIDE_PREVIEW, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ULTRAWIDE_PREVIEW, value).apply()

    var isKeepFrontCameraReady: Boolean
        get() = prefs.getBoolean(KEY_KEEP_FRONT_READY, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_FRONT_READY, value).apply()

    var isShowFrontCameraPreview: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FRONT_PREVIEW, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_FRONT_PREVIEW, value).apply()

    var trackingLens: com.example.camera.tracking.model.TrackingCameraLens
        get() {
            val name = prefs.getString(KEY_TRACKING_LENS, com.example.camera.tracking.model.TrackingCameraLens.WIDE.name)
                ?: com.example.camera.tracking.model.TrackingCameraLens.WIDE.name
            return try {
                com.example.camera.tracking.model.TrackingCameraLens.valueOf(name)
            } catch (e: Exception) {
                com.example.camera.tracking.model.TrackingCameraLens.WIDE
            }
        }
        set(value) = prefs.edit().putString(KEY_TRACKING_LENS, value.name).apply()

    var isHighQualityZoomEnabled: Boolean
        get() = prefs.getBoolean(KEY_HQ_ZOOM_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HQ_ZOOM_ENABLED, value).apply()

    var zoomProcessingQuality: com.example.camera.zoom.ZoomProcessingQuality
        get() {
            val name = prefs.getString(KEY_ZOOM_PROCESSING_QUALITY, com.example.camera.zoom.ZoomProcessingQuality.BALANCED.name)
                ?: com.example.camera.zoom.ZoomProcessingQuality.BALANCED.name
            return try { com.example.camera.zoom.ZoomProcessingQuality.valueOf(name) } catch (e: Exception) { com.example.camera.zoom.ZoomProcessingQuality.BALANCED }
        }
        set(value) = prefs.edit().putString(KEY_ZOOM_PROCESSING_QUALITY, value.name).apply()

    var isRefocusPhotoEnabled: Boolean
        get() = prefs.getBoolean(KEY_REFOCUS_PHOTO_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_REFOCUS_PHOTO_ENABLED, value).apply()

    var refocusFrameCount: Int
        get() = prefs.getInt(KEY_REFOCUS_FRAME_COUNT, 5).coerceIn(5, 20)
        set(value) = prefs.edit().putInt(KEY_REFOCUS_FRAME_COUNT, value.coerceIn(5, 20)).apply()

    var isUltraFastShutterEnabled: Boolean
        get() = prefs.getBoolean(KEY_ULTRA_FAST_SHUTTER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ULTRA_FAST_SHUTTER_ENABLED, value).apply()

    var ultraFastShutterFps: Int
        get() = prefs.getInt(KEY_ULTRA_FAST_SHUTTER_FPS, 15).coerceIn(5, 20)
        set(value) = prefs.edit().putInt(KEY_ULTRA_FAST_SHUTTER_FPS, value.coerceIn(5, 20)).apply()

    var ultraFastShutterBurstCount: Int
        get() = prefs.getInt(KEY_ULTRA_FAST_SHUTTER_BURST_COUNT, 15).coerceIn(5, 30)
        set(value) = prefs.edit().putInt(KEY_ULTRA_FAST_SHUTTER_BURST_COUNT, value.coerceIn(5, 30)).apply()

    var cameraMode: CameraMode
        get() {
            val name = prefs.getString(KEY_CAMERA_MODE, CameraMode.PHOTO.name) ?: CameraMode.PHOTO.name
            return try { CameraMode.valueOf(name) } catch (e: Exception) { CameraMode.PHOTO }
        }
        set(value) = prefs.edit().putString(KEY_CAMERA_MODE, value.name).apply()

    var flashMode: FlashMode
        get() {
            val name = prefs.getString(KEY_FLASH_MODE, FlashMode.OFF.name) ?: FlashMode.OFF.name
            return try { FlashMode.valueOf(name) } catch (e: Exception) { FlashMode.OFF }
        }
        set(value) = prefs.edit().putString(KEY_FLASH_MODE, value.name).apply()

    var timerMode: TimerMode
        get() {
            val name = prefs.getString(KEY_TIMER_MODE, TimerMode.OFF.name) ?: TimerMode.OFF.name
            return try { TimerMode.valueOf(name) } catch (e: Exception) { TimerMode.OFF }
        }
        set(value) = prefs.edit().putString(KEY_TIMER_MODE, value.name).apply()

    var gridType: GridType
        get() {
            val name = prefs.getString(KEY_GRID_TYPE, GridType.NONE.name) ?: GridType.NONE.name
            return try { GridType.valueOf(name) } catch (e: Exception) { GridType.NONE }
        }
        set(value) = prefs.edit().putString(KEY_GRID_TYPE, value.name).apply()

    var isRawEnabled: Boolean
        get() = prefs.getBoolean(KEY_RAW_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_RAW_ENABLED, value).apply()

    var videoWidth: Int
        get() = prefs.getInt(KEY_VIDEO_WIDTH, 3840) // Default 4K if supported, will gracefully adapt
        set(value) = prefs.edit().putInt(KEY_VIDEO_WIDTH, value).apply()

    var videoHeight: Int
        get() = prefs.getInt(KEY_VIDEO_HEIGHT, 2160)
        set(value) = prefs.edit().putInt(KEY_VIDEO_HEIGHT, value).apply()

    var videoFps: Int
        get() = prefs.getInt(KEY_VIDEO_FPS, 30)
        set(value) = prefs.edit().putInt(KEY_VIDEO_FPS, value).apply()

    var videoBitrate: VideoBitrateOption
        get() {
            val name = prefs.getString(KEY_VIDEO_BITRATE, VideoBitrateOption.AUTO.name) ?: VideoBitrateOption.AUTO.name
            return try { VideoBitrateOption.valueOf(name) } catch (e: Exception) { VideoBitrateOption.AUTO }
        }
        set(value) = prefs.edit().putString(KEY_VIDEO_BITRATE, value.name).apply()

    var isVideoStabilizationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_STABILIZATION, true)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO_STABILIZATION, value).apply()

    var isAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_ENABLED, value).apply()

    var proSaturation: Float
        get() = prefs.getFloat("pref_pro_saturation", 0f)
        set(value) = prefs.edit().putFloat("pref_pro_saturation", value).apply()

    var proContrast: Float
        get() = prefs.getFloat("pref_pro_contrast", 1.0f)
        set(value) = prefs.edit().putFloat("pref_pro_contrast", value).apply()

    var proHighlights: Float
        get() = prefs.getFloat("pref_pro_highlights", 0f)
        set(value) = prefs.edit().putFloat("pref_pro_highlights", value).apply()

    var proShadows: Float
        get() = prefs.getFloat("pref_pro_shadows", 0f)
        set(value) = prefs.edit().putFloat("pref_pro_shadows", value).apply()

    var proSharpness: Float
        get() = prefs.getFloat("pref_pro_sharpness", 15f)
        set(value) = prefs.edit().putFloat("pref_pro_sharpness", value).apply()

    var proNoiseReduction: Float
        get() = prefs.getFloat("pref_pro_noise_reduction", 12f)
        set(value) = prefs.edit().putFloat("pref_pro_noise_reduction", value).apply()

    var colorProfile: ColorProfile
        get() {
            val name = prefs.getString(KEY_COLOR_PROFILE, ColorProfile.STANDARD.name) ?: ColorProfile.STANDARD.name
            return try { ColorProfile.valueOf(name) } catch (e: Exception) { ColorProfile.STANDARD }
        }
        set(value) = prefs.edit().putString(KEY_COLOR_PROFILE, value.name).apply()

    var whiteBalance: WhiteBalanceMode
        get() {
            val name = prefs.getString(KEY_WHITE_BALANCE, WhiteBalanceMode.AUTO.name) ?: WhiteBalanceMode.AUTO.name
            return try { WhiteBalanceMode.valueOf(name) } catch (e: Exception) { WhiteBalanceMode.AUTO }
        }
        set(value) = prefs.edit().putString(KEY_WHITE_BALANCE, value.name).apply()

    var focusMode: FocusMode
        get() {
            val name = prefs.getString(KEY_FOCUS_MODE, FocusMode.CONTINUOUS.name) ?: FocusMode.CONTINUOUS.name
            return try { FocusMode.valueOf(name) } catch (e: Exception) { FocusMode.CONTINUOUS }
        }
        set(value) = prefs.edit().putString(KEY_FOCUS_MODE, value.name).apply()

    var lastFacing: Int
        get() = prefs.getInt(KEY_LAST_FACING, android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK)
        set(value) = prefs.edit().putInt(KEY_LAST_FACING, value).apply()

    var portraitBlurStrength: Float
        get() = prefs.getFloat(KEY_PORTRAIT_BLUR, 60f)
        set(value) = prefs.edit().putFloat(KEY_PORTRAIT_BLUR, value).apply()

    var portraitAperture: String
        get() = prefs.getString(KEY_PORTRAIT_APERTURE, "f/1.4") ?: "f/1.4"
        set(value) = prefs.edit().putString(KEY_PORTRAIT_APERTURE, value).apply()

    var portraitOpticalBlurGuided: Boolean
        get() = prefs.getBoolean(KEY_PORTRAIT_OPTICAL_BLUR_GUIDED, true)
        set(value) = prefs.edit().putBoolean(KEY_PORTRAIT_OPTICAL_BLUR_GUIDED, value).apply()

    var saveSelfieAsPreviewed: Boolean
        get() = prefs.getBoolean(KEY_SAVE_SELFIE_AS_PREVIEWED, true)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_SELFIE_AS_PREVIEWED, value).apply()

    var viewfinderResolution: com.example.camera.model.ViewfinderResolution
        get() {
            val name = prefs.getString(KEY_VIEWFINDER_RESOLUTION, com.example.camera.model.ViewfinderResolution.NORMAL.name)
                ?: com.example.camera.model.ViewfinderResolution.NORMAL.name
            return try { com.example.camera.model.ViewfinderResolution.valueOf(name) } catch (e: Exception) { com.example.camera.model.ViewfinderResolution.NORMAL }
        }
        set(value) = prefs.edit().putString(KEY_VIEWFINDER_RESOLUTION, value.name).apply()

    // Photo Megapixel Mode (12M vs 50M)
    var photoMegapixelMode: com.example.camera.model.PhotoMegapixelMode
        get() {
            val name = prefs.getString("pref_photo_mp_mode", com.example.camera.model.PhotoMegapixelMode.M12.name)
                ?: com.example.camera.model.PhotoMegapixelMode.M12.name
            return try { com.example.camera.model.PhotoMegapixelMode.valueOf(name) } catch (e: Exception) { com.example.camera.model.PhotoMegapixelMode.M12 }
        }
        set(value) = prefs.edit().putString("pref_photo_mp_mode", value.name).apply()

    // Cinema Mode Preferences - Full Persistence across sessions
    var cinemaFps: Int
        get() = prefs.getInt("pref_cinema_fps", 24)
        set(value) = prefs.edit().putInt("pref_cinema_fps", value).apply()

    var cinemaWidth: Int
        get() = prefs.getInt("pref_cinema_width", 3840)
        set(value) = prefs.edit().putInt("pref_cinema_width", value).apply()

    var cinemaHeight: Int
        get() = prefs.getInt("pref_cinema_height", 2160)
        set(value) = prefs.edit().putInt("pref_cinema_height", value).apply()

    var cinemaLogBitDepth: com.example.camera.model.LogBitDepth
        get() {
            val name = prefs.getString("pref_cinema_bit_depth", com.example.camera.model.LogBitDepth.BIT_10.name)
                ?: com.example.camera.model.LogBitDepth.BIT_10.name
            return try { com.example.camera.model.LogBitDepth.valueOf(name) } catch (e: Exception) { com.example.camera.model.LogBitDepth.BIT_10 }
        }
        set(value) = prefs.edit().putString("pref_cinema_bit_depth", value.name).apply()

    var cinemaColorProfile: com.example.camera.model.CinemaColorProfile
        get() {
            val name = prefs.getString("pref_cinema_color_profile", com.example.camera.model.CinemaColorProfile.PROCESSED_JPEG.name)
                ?: com.example.camera.model.CinemaColorProfile.PROCESSED_JPEG.name
            return try { com.example.camera.model.CinemaColorProfile.valueOf(name) } catch (e: Exception) { com.example.camera.model.CinemaColorProfile.PROCESSED_JPEG }
        }
        set(value) = prefs.edit().putString("pref_cinema_color_profile", value.name).apply()

    var cinemaColorSpace: com.example.camera.model.CinemaColorSpace
        get() {
            val name = prefs.getString("pref_cinema_color_space", com.example.camera.model.CinemaColorSpace.REC_709.name)
                ?: com.example.camera.model.CinemaColorSpace.REC_709.name
            return try { com.example.camera.model.CinemaColorSpace.valueOf(name) } catch (e: Exception) { com.example.camera.model.CinemaColorSpace.REC_709 }
        }
        set(value) = prefs.edit().putString("pref_cinema_color_space", value.name).apply()

    var cinemaIsRawSensorLogPipeline: Boolean
        get() = prefs.getBoolean("pref_cinema_raw_pipeline", true)
        set(value) = prefs.edit().putBoolean("pref_cinema_raw_pipeline", value).apply()

    var cinemaIsFocusPeakingEnabled: Boolean
        get() = prefs.getBoolean("pref_cinema_focus_peaking", false)
        set(value) = prefs.edit().putBoolean("pref_cinema_focus_peaking", value).apply()

    var cinemaIsWaveformEnabled: Boolean
        get() = prefs.getBoolean("pref_cinema_waveform", false)
        set(value) = prefs.edit().putBoolean("pref_cinema_waveform", value).apply()

    var cinemaZebraThreshold: com.example.camera.model.ZebraThreshold
        get() {
            val name = prefs.getString("pref_cinema_zebra_threshold", com.example.camera.model.ZebraThreshold.IRE_70.name)
                ?: com.example.camera.model.ZebraThreshold.IRE_70.name
            return try { com.example.camera.model.ZebraThreshold.valueOf(name) } catch (e: Exception) { com.example.camera.model.ZebraThreshold.IRE_70 }
        }
        set(value) = prefs.edit().putString("pref_cinema_zebra_threshold", value.name).apply()

    var cinemaShadows: Float
        get() = prefs.getFloat("pref_cinema_shadows", 0.0f)
        set(value) = prefs.edit().putFloat("pref_cinema_shadows", value).apply()

    var cinemaHighlights: Float
        get() = prefs.getFloat("pref_cinema_highlights", 0.0f)
        set(value) = prefs.edit().putFloat("pref_cinema_highlights", value).apply()

    var cinemaContrast: Float
        get() = prefs.getFloat("pref_cinema_contrast", 0.0f)
        set(value) = prefs.edit().putFloat("pref_cinema_contrast", value).apply()

    var cinemaSaturation: Float
        get() = prefs.getFloat("pref_cinema_saturation", 1.0f)
        set(value) = prefs.edit().putFloat("pref_cinema_saturation", value).apply()

    var cinemaSharpness: com.example.camera.model.CinemaSharpness
        get() {
            val name = prefs.getString("pref_cinema_sharpness", com.example.camera.model.CinemaSharpness.NATURAL.name)
                ?: com.example.camera.model.CinemaSharpness.NATURAL.name
            return try { com.example.camera.model.CinemaSharpness.valueOf(name) } catch (e: Exception) { com.example.camera.model.CinemaSharpness.NATURAL }
        }
        set(value) = prefs.edit().putString("pref_cinema_sharpness", value.name).apply()

    var cinemaExposureCompensation: Int
        get() = prefs.getInt("pref_cinema_ev", 0)
        set(value) = prefs.edit().putInt("pref_cinema_ev", value).apply()

    var cinemaWhiteBalance: com.example.camera.model.WhiteBalanceMode
        get() {
            val name = prefs.getString("pref_cinema_wb", com.example.camera.model.WhiteBalanceMode.AUTO.name)
                ?: com.example.camera.model.WhiteBalanceMode.AUTO.name
            return try { com.example.camera.model.WhiteBalanceMode.valueOf(name) } catch (e: Exception) { com.example.camera.model.WhiteBalanceMode.AUTO }
        }
        set(value) = prefs.edit().putString("pref_cinema_wb", value.name).apply()

    var cinemaManualIso: Int?
        get() {
            val iso = prefs.getInt("pref_cinema_iso", -1)
            return if (iso > 0) iso else null
        }
        set(value) = prefs.edit().putInt("pref_cinema_iso", value ?: -1).apply()

    var cinemaManualShutterSpeedNs: Long?
        get() {
            val ns = prefs.getLong("pref_cinema_shutter", -1L)
            return if (ns > 0) ns else null
        }
        set(value) = prefs.edit().putLong("pref_cinema_shutter", value ?: -1L).apply()

    var cinemaNoiseReduction: com.example.camera.model.CinemaNoiseReduction
        get() {
            val name = prefs.getString("pref_cinema_noise_reduction", com.example.camera.model.CinemaNoiseReduction.OFF.name)
                ?: com.example.camera.model.CinemaNoiseReduction.OFF.name
            return try { com.example.camera.model.CinemaNoiseReduction.valueOf(name) } catch (e: Exception) { com.example.camera.model.CinemaNoiseReduction.OFF }
        }
        set(value) = prefs.edit().putString("pref_cinema_noise_reduction", value.name).apply()

    var cinemaSelectedLut: com.example.camera.model.CinematicLut
        get() {
            val name = prefs.getString("pref_cinema_selected_lut", com.example.camera.model.CinematicLut.REC_709.name)
                ?: com.example.camera.model.CinematicLut.REC_709.name
            return try {
                when (name) {
                    "FILMIC_NEUTRAL" -> com.example.camera.model.CinematicLut.REC_709
                    "WARM_CINEMA" -> com.example.camera.model.CinematicLut.WARM_SUNSET
                    "COOL_DRAMATIC" -> com.example.camera.model.CinematicLut.COOL_THRILLER
                    "HIGH_CONTRAST_CINEMA" -> com.example.camera.model.CinematicLut.BLEACH_BYPASS
                    "SOFT_FILM" -> com.example.camera.model.CinematicLut.FUJI_ETERNA
                    else -> com.example.camera.model.CinematicLut.valueOf(name)
                }
            } catch (e: Exception) {
                com.example.camera.model.CinematicLut.REC_709
            }
        }
        set(value) = prefs.edit().putString("pref_cinema_selected_lut", value.name).apply()

    var cinemaCustomLutPath: String?
        get() = prefs.getString("pref_cinema_custom_lut_path", null)
        set(value) = prefs.edit().putString("pref_cinema_custom_lut_path", value).apply()

    var cinemaCustomLutName: String?
        get() = prefs.getString("pref_cinema_custom_lut_name", null)
        set(value) = prefs.edit().putString("pref_cinema_custom_lut_name", value).apply()

    var cinemaCodec: com.example.camera.model.CinemaCodec
        get() {
            val name = prefs.getString("pref_cinema_codec", com.example.camera.model.CinemaCodec.H265.name)
                ?: com.example.camera.model.CinemaCodec.H265.name
            return try { com.example.camera.model.CinemaCodec.valueOf(name) } catch (e: Exception) { com.example.camera.model.CinemaCodec.H265 }
        }
        set(value) = prefs.edit().putString("pref_cinema_codec", value.name).apply()

    var cinemaExposure: Float
        get() = prefs.getFloat("pref_cinema_exposure_slider", 0.0f)
        set(value) = prefs.edit().putFloat("pref_cinema_exposure_slider", value).apply()

    var cinemaWashedOut: Float
        get() = prefs.getFloat("pref_cinema_washed_out", 0.0f)
        set(value) = prefs.edit().putFloat("pref_cinema_washed_out", value).apply()

    var cinemaLutIntensity: Float
        get() = prefs.getFloat("pref_cinema_lut_intensity", 1.0f).coerceIn(0.0f, 1.0f)
        set(value) = prefs.edit().putFloat("pref_cinema_lut_intensity", value.coerceIn(0.0f, 1.0f)).apply()

    var cinemaIsBakeLutToOutput: Boolean
        get() = prefs.getBoolean("pref_cinema_bake_lut", true)
        set(value) = prefs.edit().putBoolean("pref_cinema_bake_lut", value).apply()

    fun getCinemaConfig(): com.example.camera.model.CinemaConfig {
        return com.example.camera.model.CinemaConfig(
            videoFps = cinemaFps,
            selectedResolution = com.example.camera.model.CameraResolution(cinemaWidth, cinemaHeight),
            logBitDepth = cinemaLogBitDepth,
            codec = cinemaCodec,
            selectedLut = cinemaSelectedLut,
            customLutPath = cinemaCustomLutPath,
            customLutName = cinemaCustomLutName,
            colorProfile = cinemaColorProfile,
            colorSpace = cinemaColorSpace,
            isRawSensorLogPipeline = cinemaIsRawSensorLogPipeline,
            isFocusPeakingEnabled = cinemaIsFocusPeakingEnabled,
            isWaveformEnabled = cinemaIsWaveformEnabled,
            zebraThreshold = cinemaZebraThreshold,
            shadows = cinemaShadows,
            highlights = cinemaHighlights,
            contrast = cinemaContrast,
            exposure = cinemaExposure,
            washedOut = cinemaWashedOut,
            saturation = cinemaSaturation,
            sharpness = cinemaSharpness,
            noiseReduction = cinemaNoiseReduction,
            exposureCompensation = cinemaExposureCompensation,
            whiteBalance = cinemaWhiteBalance,
            manualIso = cinemaManualIso,
            manualShutterSpeedNs = cinemaManualShutterSpeedNs,
            lutIntensity = cinemaLutIntensity,
            isBakeLutToOutput = cinemaIsBakeLutToOutput
        )
    }

    fun saveCinemaConfig(config: com.example.camera.model.CinemaConfig) {
        cinemaFps = config.videoFps
        config.selectedResolution?.let {
            cinemaWidth = it.width
            cinemaHeight = it.height
        }
        cinemaLogBitDepth = config.logBitDepth
        cinemaCodec = config.codec
        cinemaSelectedLut = config.selectedLut
        cinemaCustomLutPath = config.customLutPath
        cinemaCustomLutName = config.customLutName
        cinemaColorProfile = config.colorProfile
        cinemaColorSpace = config.colorSpace
        cinemaIsRawSensorLogPipeline = config.isRawSensorLogPipeline
        cinemaIsFocusPeakingEnabled = config.isFocusPeakingEnabled
        cinemaIsWaveformEnabled = config.isWaveformEnabled
        cinemaZebraThreshold = config.zebraThreshold
        cinemaShadows = config.shadows
        cinemaHighlights = config.highlights
        cinemaContrast = config.contrast
        cinemaExposure = config.exposure
        cinemaWashedOut = config.washedOut
        cinemaSaturation = config.saturation
        cinemaSharpness = config.sharpness
        cinemaNoiseReduction = config.noiseReduction
        cinemaExposureCompensation = config.exposureCompensation
        cinemaWhiteBalance = config.whiteBalance
        cinemaManualIso = config.manualIso
        cinemaManualShutterSpeedNs = config.manualShutterSpeedNs
        cinemaLutIntensity = config.lutIntensity
        cinemaIsBakeLutToOutput = config.isBakeLutToOutput
    }

    // Upgraded Night Mode Preferences
    var nightDurationSeconds: Int
        get() = prefs.getInt("pref_night_duration", 2).coerceIn(1, 5)
        set(value) = prefs.edit().putInt("pref_night_duration", value.coerceIn(1, 5)).apply()

    var nightIsMultiFrameFusion: Boolean
        get() = prefs.getBoolean("pref_night_fusion", true)
        set(value) = prefs.edit().putBoolean("pref_night_fusion", value).apply()

    var nightIsAntiGhostingEnabled: Boolean
        get() = prefs.getBoolean("pref_night_anti_ghosting", true)
        set(value) = prefs.edit().putBoolean("pref_night_anti_ghosting", value).apply()

    var nightNoiseSuppression: Float
        get() = prefs.getFloat("pref_night_noise_suppression", 0.85f)
        set(value) = prefs.edit().putFloat("pref_night_noise_suppression", value).apply()

    var nightShadowLift: Float
        get() = prefs.getFloat("pref_night_shadow_lift", 1.25f)
        set(value) = prefs.edit().putFloat("pref_night_shadow_lift", value).apply()

    // Hybrid Stabilization Preferences
    var isHybridStabilizationEnabled: Boolean
        get() = prefs.getBoolean("pref_hybrid_stabilization", true)
        set(value) = prefs.edit().putBoolean("pref_hybrid_stabilization", value).apply()

    var isOisPreferred: Boolean
        get() = prefs.getBoolean("pref_ois_preferred", true)
        set(value) = prefs.edit().putBoolean("pref_ois_preferred", value).apply()

    var isEisPreferred: Boolean
        get() = prefs.getBoolean("pref_eis_preferred", true)
        set(value) = prefs.edit().putBoolean("pref_eis_preferred", value).apply()

    var isAdaptiveFpsLensStabilization: Boolean
        get() = prefs.getBoolean("pref_adaptive_stabilization", true)
        set(value) = prefs.edit().putBoolean("pref_adaptive_stabilization", value).apply()

    var isUltraStabilizationEnabled: Boolean
        get() = prefs.getBoolean("pref_ultra_stabilization", false)
        set(value) = prefs.edit().putBoolean("pref_ultra_stabilization", value).apply()

    var isEisOnly: Boolean
        get() = prefs.getBoolean("pref_eis_only", false)
        set(value) = prefs.edit().putBoolean("pref_eis_only", value).apply()

    // Tap to Focus & Exposure Preferences
    var isTapToFocusExposureEnabled: Boolean
        get() = prefs.getBoolean("pref_tap_focus_exposure", true)
        set(value) = prefs.edit().putBoolean("pref_tap_focus_exposure", value).apply()

    var isAeAfLockEnabled: Boolean
        get() = prefs.getBoolean("pref_ae_af_lock", true)
        set(value) = prefs.edit().putBoolean("pref_ae_af_lock", value).apply()

    var isSunExposureSliderEnabled: Boolean
        get() = prefs.getBoolean("pref_sun_slider", true)
        set(value) = prefs.edit().putBoolean("pref_sun_slider", value).apply()

    var nightConfig: NightConfig
        get() = NightConfig(
            durationSeconds = nightDurationSeconds,
            multiFrameFusionEnabled = nightIsMultiFrameFusion,
            antiGhostingEnabled = nightIsAntiGhostingEnabled,
            noiseSuppression = nightNoiseSuppression,
            shadowLift = nightShadowLift,
            isMultiFrameFusion = nightIsMultiFrameFusion,
            isAntiGhostingEnabled = nightIsAntiGhostingEnabled,
            noiseSuppressionStrength = nightNoiseSuppression,
            shadowLiftFactor = nightShadowLift
        )
        set(value) {
            nightDurationSeconds = value.durationSeconds
            nightIsMultiFrameFusion = value.multiFrameFusionEnabled
            nightIsAntiGhostingEnabled = value.antiGhostingEnabled
            nightNoiseSuppression = value.noiseSuppression
            nightShadowLift = value.shadowLift
        }

    var tapFocusConfig: TapFocusConfig
        get() = TapFocusConfig(
            isTapToFocusExposureEnabled = isTapToFocusExposureEnabled,
            isTapToFocusEnabled = isTapToFocusExposureEnabled,
            isAeAfLockEnabled = isAeAfLockEnabled,
            isSunExposureSliderEnabled = isSunExposureSliderEnabled,
            autoDismissReticle = true
        )
        set(value) {
            isTapToFocusExposureEnabled = value.isTapToFocusEnabled
            isAeAfLockEnabled = value.isAeAfLockEnabled
            isSunExposureSliderEnabled = value.isSunExposureSliderEnabled
        }

    var hybridStabilizationConfig: HybridStabilizationConfig
        get() = HybridStabilizationConfig(
            isHybridEnabled = isHybridStabilizationEnabled,
            isOisPreferred = isOisPreferred,
            isEisPreferred = isEisPreferred,
            isAdaptiveFpsLens = isAdaptiveFpsLensStabilization,
            isUltraStabilizationEnabled = isUltraStabilizationEnabled,
            isEisOnly = isEisOnly
        )
        set(value) {
            isHybridStabilizationEnabled = value.isHybridEnabled
            isOisPreferred = value.isOisPreferred
            isEisPreferred = value.isEisPreferred
            isAdaptiveFpsLensStabilization = value.isAdaptiveFpsLens
            isUltraStabilizationEnabled = value.isUltraStabilizationEnabled
            isEisOnly = value.isEisOnly
        }

    var uiCustomizationState: UiCustomizationState
        get() {
            val jsonStr = prefs.getString("pref_ui_customization_state", "") ?: ""
            return if (jsonStr.isNotBlank()) {
                UiCustomizationState.fromJson(jsonStr)
            } else {
                UiCustomizationState()
            }
        }
        set(value) {
            prefs.edit().putString("pref_ui_customization_state", value.toJson()).apply()
        }

    // Extended Camera Controls & System Preferences
    var videoCodec: String
        get() = prefs.getString("pref_video_codec", "HEVC") ?: "HEVC"
        set(value) = prefs.edit().putString("pref_video_codec", value).apply()

    var jpegQuality: Int
        get() = prefs.getInt("pref_jpeg_quality", 100)
        set(value) = prefs.edit().putInt("pref_jpeg_quality", value).apply()

    var volumeKeyAction: String
        get() = prefs.getString("pref_volume_key_action", "SHUTTER") ?: "SHUTTER"
        set(value) = prefs.edit().putString("pref_volume_key_action", value).apply()

    var doubleTapAction: String
        get() = prefs.getString("pref_double_tap_action", "FLIP_CAMERA") ?: "FLIP_CAMERA"
        set(value) = prefs.edit().putString("pref_double_tap_action", value).apply()

    var showHorizonLevel: Boolean
        get() = prefs.getBoolean("pref_show_horizon_level", true)
        set(value) = prefs.edit().putBoolean("pref_show_horizon_level", value).apply()

    var antiBanding: String
        get() = prefs.getString("pref_anti_banding", "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString("pref_anti_banding", value).apply()

    var zoomSpeed: String
        get() = prefs.getString("pref_zoom_speed", "SMOOTH") ?: "SMOOTH"
        set(value) = prefs.edit().putString("pref_zoom_speed", value).apply()

    var audioSource: String
        get() = prefs.getString("pref_audio_source", "STEREO") ?: "STEREO"
        set(value) = prefs.edit().putString("pref_audio_source", value).apply()

    var previewQuality: String
        get() = prefs.getString("pref_preview_quality", "HIGH_60FPS") ?: "HIGH_60FPS"
        set(value) = prefs.edit().putString("pref_preview_quality", value).apply()

    var shutterFeedback: String
        get() = prefs.getString("pref_shutter_feedback", "SOUND_HAPTIC") ?: "SOUND_HAPTIC"
        set(value) = prefs.edit().putString("pref_shutter_feedback", value).apply()

    var autoHdrEnabled: Boolean
        get() = prefs.getBoolean("pref_auto_hdr_enabled", true)
        set(value) = prefs.edit().putBoolean("pref_auto_hdr_enabled", value).apply()

    var isHdrPlusEnabled: Boolean
        get() = prefs.getBoolean("pref_hdr_plus_enabled", false)
        set(value) = prefs.edit().putBoolean("pref_hdr_plus_enabled", value).apply()

    var hdrPlusFrameCount: com.example.camera.engine.hdrplus.HdrPlusFrameCount
        get() {
            val count = prefs.getInt("pref_hdr_plus_frame_count", 2)
            return com.example.camera.engine.hdrplus.HdrPlusFrameCount.fromInt(count)
        }
        set(value) = prefs.edit().putInt("pref_hdr_plus_frame_count", value.count).apply()

    var autoFramingEnabled: Boolean
        get() = prefs.getBoolean("pref_auto_framing_enabled", true)
        set(value) = prefs.edit().putBoolean("pref_auto_framing_enabled", value).apply()

    var windNoiseReduction: Boolean
        get() = prefs.getBoolean("pref_wind_noise_reduction", true)
        set(value) = prefs.edit().putBoolean("pref_wind_noise_reduction", value).apply()

    var thermalProtection: Boolean
        get() = prefs.getBoolean("pref_thermal_protection", true)
        set(value) = prefs.edit().putBoolean("pref_thermal_protection", value).apply()

    var viewfinderFps: Int
        get() = prefs.getInt("pref_viewfinder_fps", 60)
        set(value) = prefs.edit().putInt("pref_viewfinder_fps", value).apply()

    var currentZoom: Float
        get() = prefs.getFloat("pref_current_zoom", 1.0f)
        set(value) = prefs.edit().putFloat("pref_current_zoom", value).apply()

    var exposureCompensation: Int
        get() = prefs.getInt("pref_exposure_compensation", 0)
        set(value) = prefs.edit().putInt("pref_exposure_compensation", value).apply()

    var manualIso: Int?
        get() {
            val iso = prefs.getInt("pref_manual_iso", -1)
            return if (iso > 0) iso else null
        }
        set(value) = prefs.edit().putInt("pref_manual_iso", value ?: -1).apply()

    var manualShutterSpeedNs: Long?
        get() {
            val ns = prefs.getLong("pref_manual_shutter_speed_ns", -1L)
            return if (ns > 0) ns else null
        }
        set(value) = prefs.edit().putLong("pref_manual_shutter_speed_ns", value ?: -1L).apply()

    var manualFocusDistance: Float
        get() = prefs.getFloat("pref_manual_focus_distance", 0f)
        set(value) = prefs.edit().putFloat("pref_manual_focus_distance", value).apply()

    var selectedPhotoFilter: PhotoFilter
        get() {
            val name = prefs.getString("pref_selected_photo_filter", PhotoFilter.ORIGINAL.name)
                ?: PhotoFilter.ORIGINAL.name
            return try { PhotoFilter.valueOf(name) } catch (e: Exception) { PhotoFilter.ORIGINAL }
        }
        set(value) = prefs.edit().putString("pref_selected_photo_filter", value.name).apply()

    // Last selected lens across sessions
    var lastSelectedLensId: String
        get() = prefs.getString("pref_last_selected_lens_id", "") ?: ""
        set(value) = prefs.edit().putString("pref_last_selected_lens_id", value).apply()

    var lastSelectedLensCameraId: String
        get() = prefs.getString("pref_last_selected_lens_camera_id", "") ?: ""
        set(value) = prefs.edit().putString("pref_last_selected_lens_camera_id", value).apply()

    var lastSelectedLensFacing: Int
        get() = prefs.getInt("pref_last_selected_lens_facing", android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK)
        set(value) = prefs.edit().putInt("pref_last_selected_lens_facing", value).apply()

    var lastSelectedLensType: String
        get() = prefs.getString("pref_last_selected_lens_type", LensType.WIDE.name) ?: LensType.WIDE.name
        set(value) = prefs.edit().putString("pref_last_selected_lens_type", value).apply()

    var lastSelectedLensIsPreset: Boolean
        get() = prefs.getBoolean("pref_last_selected_lens_is_preset", false)
        set(value) = prefs.edit().putBoolean("pref_last_selected_lens_is_preset", value).apply()

    var lastSelectedLensBaseZoom: Float
        get() = prefs.getFloat("pref_last_selected_lens_base_zoom", 1.0f)
        set(value) = prefs.edit().putFloat("pref_last_selected_lens_base_zoom", value).apply()

    fun saveLastLens(lens: LensInfo) {
        lastSelectedLensId = lens.id
        lastSelectedLensCameraId = lens.cameraId
        lastSelectedLensFacing = lens.facing
        lastSelectedLensType = lens.lensType.name
        lastSelectedLensIsPreset = lens.isZoomPreset
        lastSelectedLensBaseZoom = lens.baseZoomRatio
        lastFacing = lens.facing
    }

    fun getLastLens(availableLenses: List<LensInfo>): LensInfo? {
        val targetId = lastSelectedLensId
        val targetCameraId = lastSelectedLensCameraId
        val targetFacing = lastSelectedLensFacing
        val targetType = lastSelectedLensType
        val targetIsPreset = lastSelectedLensIsPreset

        return availableLenses.firstOrNull { it.id == targetId }
            ?: availableLenses.firstOrNull {
                it.cameraId == targetCameraId &&
                it.facing == targetFacing &&
                it.lensType.name == targetType &&
                it.isZoomPreset == targetIsPreset
            }
            ?: availableLenses.firstOrNull {
                it.cameraId == targetCameraId && it.facing == targetFacing
            }
            ?: availableLenses.firstOrNull {
                it.facing == targetFacing && it.lensType.name == targetType && !it.isZoomPreset
            }
            ?: availableLenses.firstOrNull { it.facing == targetFacing }
    }

    // Mode-Specific Settings Helpers
    private fun modeKey(mode: CameraMode, key: String) = "mode_${mode.name}_$key"

    fun setModeLens(mode: CameraMode, lens: LensInfo) {
        saveLastLens(lens)
        prefs.edit()
            .putString(modeKey(mode, "lens_id"), lens.id)
            .putString(modeKey(mode, "lens_camera_id"), lens.cameraId)
            .putInt(modeKey(mode, "lens_facing"), lens.facing)
            .putString(modeKey(mode, "lens_type"), lens.lensType.name)
            .putBoolean(modeKey(mode, "lens_is_preset"), lens.isZoomPreset)
            .putFloat(modeKey(mode, "lens_base_zoom"), lens.baseZoomRatio)
            .apply()
    }

    fun getModeLens(mode: CameraMode, availableLenses: List<LensInfo>): LensInfo? {
        val modeId = prefs.getString(modeKey(mode, "lens_id"), "") ?: ""
        val modeCamId = prefs.getString(modeKey(mode, "lens_camera_id"), "") ?: ""
        val modeFacing = prefs.getInt(modeKey(mode, "lens_facing"), -1)
        val modeType = prefs.getString(modeKey(mode, "lens_type"), "") ?: ""
        val modeIsPreset = prefs.getBoolean(modeKey(mode, "lens_is_preset"), false)

        if (modeId.isNotEmpty()) {
            val found = availableLenses.firstOrNull { it.id == modeId }
            if (found != null) return found
        }
        if (modeCamId.isNotEmpty() && modeFacing != -1) {
            val found = availableLenses.firstOrNull {
                it.cameraId == modeCamId && it.facing == modeFacing && it.lensType.name == modeType && it.isZoomPreset == modeIsPreset
            } ?: availableLenses.firstOrNull {
                it.cameraId == modeCamId && it.facing == modeFacing
            }
            if (found != null) return found
        }
        return getLastLens(availableLenses)
    }

    fun setModeZoom(mode: CameraMode, zoom: Float) {
        currentZoom = zoom
        prefs.edit().putFloat(modeKey(mode, "zoom"), zoom).apply()
    }

    fun getModeZoom(mode: CameraMode): Float {
        val modeZ = prefs.getFloat(modeKey(mode, "zoom"), -1f)
        return if (modeZ > 0f) modeZ else currentZoom
    }

    fun setModeFlashMode(mode: CameraMode, flash: FlashMode) {
        flashMode = flash
        prefs.edit().putString(modeKey(mode, "flash"), flash.name).apply()
    }

    fun getModeFlashMode(mode: CameraMode): FlashMode {
        val name = prefs.getString(modeKey(mode, "flash"), null) ?: return flashMode
        return try { FlashMode.valueOf(name) } catch (e: Exception) { flashMode }
    }

    fun setModeTimerMode(mode: CameraMode, timer: TimerMode) {
        timerMode = timer
        prefs.edit().putString(modeKey(mode, "timer"), timer.name).apply()
    }

    fun getModeTimerMode(mode: CameraMode): TimerMode {
        val name = prefs.getString(modeKey(mode, "timer"), null) ?: return timerMode
        return try { TimerMode.valueOf(name) } catch (e: Exception) { timerMode }
    }

    fun setModeGridType(mode: CameraMode, grid: GridType) {
        gridType = grid
        prefs.edit().putString(modeKey(mode, "grid"), grid.name).apply()
    }

    fun getModeGridType(mode: CameraMode): GridType {
        val name = prefs.getString(modeKey(mode, "grid"), null) ?: return gridType
        return try { GridType.valueOf(name) } catch (e: Exception) { gridType }
    }

    fun setModeRaw(mode: CameraMode, enabled: Boolean) {
        isRawEnabled = enabled
        prefs.edit().putBoolean(modeKey(mode, "raw"), enabled).apply()
    }

    fun getModeRaw(mode: CameraMode): Boolean {
        return if (prefs.contains(modeKey(mode, "raw"))) prefs.getBoolean(modeKey(mode, "raw"), false) else isRawEnabled
    }

    fun setModePhotoMegapixelMode(mode: CameraMode, mpMode: PhotoMegapixelMode) {
        photoMegapixelMode = mpMode
        prefs.edit().putString(modeKey(mode, "photo_mp"), mpMode.name).apply()
    }

    fun getModePhotoMegapixelMode(mode: CameraMode): PhotoMegapixelMode {
        val name = prefs.getString(modeKey(mode, "photo_mp"), null) ?: return photoMegapixelMode
        return try { PhotoMegapixelMode.valueOf(name) } catch (e: Exception) { photoMegapixelMode }
    }

    fun setModeRefocusEnabled(mode: CameraMode, enabled: Boolean) {
        isRefocusPhotoEnabled = enabled
        prefs.edit().putBoolean(modeKey(mode, "refocus_enabled"), enabled).apply()
    }

    fun getModeRefocusEnabled(mode: CameraMode): Boolean {
        return if (prefs.contains(modeKey(mode, "refocus_enabled"))) {
            prefs.getBoolean(modeKey(mode, "refocus_enabled"), false)
        } else isRefocusPhotoEnabled
    }

    fun setModeRefocusFrameCount(mode: CameraMode, count: Int) {
        refocusFrameCount = count
        prefs.edit().putInt(modeKey(mode, "refocus_count"), count).apply()
    }

    fun getModeRefocusFrameCount(mode: CameraMode): Int {
        return prefs.getInt(modeKey(mode, "refocus_count"), refocusFrameCount)
    }

    fun setModeUltraFastShutterEnabled(mode: CameraMode, enabled: Boolean) {
        isUltraFastShutterEnabled = enabled
        prefs.edit().putBoolean(modeKey(mode, "ultra_fast_shutter_enabled"), enabled).apply()
    }

    fun getModeUltraFastShutterEnabled(mode: CameraMode): Boolean {
        return if (prefs.contains(modeKey(mode, "ultra_fast_shutter_enabled"))) {
            prefs.getBoolean(modeKey(mode, "ultra_fast_shutter_enabled"), false)
        } else isUltraFastShutterEnabled
    }

    fun setModeUltraFastShutterFps(mode: CameraMode, fps: Int) {
        ultraFastShutterFps = fps
        prefs.edit().putInt(modeKey(mode, "ultra_fast_shutter_fps"), fps).apply()
    }

    fun getModeUltraFastShutterFps(mode: CameraMode): Int {
        return prefs.getInt(modeKey(mode, "ultra_fast_shutter_fps"), ultraFastShutterFps)
    }

    fun setModeUltraFastShutterBurstCount(mode: CameraMode, count: Int) {
        ultraFastShutterBurstCount = count
        prefs.edit().putInt(modeKey(mode, "ultra_fast_shutter_burst_count"), count).apply()
    }

    fun getModeUltraFastShutterBurstCount(mode: CameraMode): Int {
        return prefs.getInt(modeKey(mode, "ultra_fast_shutter_burst_count"), ultraFastShutterBurstCount)
    }

    fun setModeHqZoomEnabled(mode: CameraMode, enabled: Boolean) {
        isHighQualityZoomEnabled = enabled
        prefs.edit().putBoolean(modeKey(mode, "hq_zoom"), enabled).apply()
    }

    fun getModeHqZoomEnabled(mode: CameraMode): Boolean {
        return if (prefs.contains(modeKey(mode, "hq_zoom"))) {
            prefs.getBoolean(modeKey(mode, "hq_zoom"), true)
        } else isHighQualityZoomEnabled
    }

    fun setModeZoomQuality(mode: CameraMode, quality: com.example.camera.zoom.ZoomProcessingQuality) {
        zoomProcessingQuality = quality
        prefs.edit().putString(modeKey(mode, "zoom_quality"), quality.name).apply()
    }

    fun getModeZoomQuality(mode: CameraMode): com.example.camera.zoom.ZoomProcessingQuality {
        val name = prefs.getString(modeKey(mode, "zoom_quality"), null) ?: return zoomProcessingQuality
        return try { com.example.camera.zoom.ZoomProcessingQuality.valueOf(name) } catch (e: Exception) { zoomProcessingQuality }
    }

    fun setModeFocusMode(mode: CameraMode, focus: FocusMode) {
        focusMode = focus
        prefs.edit().putString(modeKey(mode, "focus_mode"), focus.name).apply()
    }

    fun getModeFocusMode(mode: CameraMode): FocusMode {
        val name = prefs.getString(modeKey(mode, "focus_mode"), null) ?: return focusMode
        return try { FocusMode.valueOf(name) } catch (e: Exception) { focusMode }
    }

    fun setModeWhiteBalance(mode: CameraMode, wb: WhiteBalanceMode) {
        whiteBalance = wb
        prefs.edit().putString(modeKey(mode, "wb"), wb.name).apply()
    }

    fun getModeWhiteBalance(mode: CameraMode): WhiteBalanceMode {
        val name = prefs.getString(modeKey(mode, "wb"), null) ?: return whiteBalance
        return try { WhiteBalanceMode.valueOf(name) } catch (e: Exception) { whiteBalance }
    }

    fun setModeColorProfile(mode: CameraMode, profile: ColorProfile) {
        colorProfile = profile
        prefs.edit().putString(modeKey(mode, "color_profile"), profile.name).apply()
    }

    fun getModeColorProfile(mode: CameraMode): ColorProfile {
        val name = prefs.getString(modeKey(mode, "color_profile"), null) ?: return colorProfile
        return try { ColorProfile.valueOf(name) } catch (e: Exception) { colorProfile }
    }

    fun setModeEv(mode: CameraMode, ev: Int) {
        exposureCompensation = ev
        prefs.edit().putInt(modeKey(mode, "ev"), ev).apply()
    }

    fun getModeEv(mode: CameraMode): Int {
        return prefs.getInt(modeKey(mode, "ev"), exposureCompensation)
    }

    fun setModeIso(mode: CameraMode, iso: Int?) {
        manualIso = iso
        prefs.edit().putInt(modeKey(mode, "iso"), iso ?: -1).apply()
    }

    fun getModeIso(mode: CameraMode): Int? {
        val iso = prefs.getInt(modeKey(mode, "iso"), -1)
        return if (iso > 0) iso else manualIso
    }

    fun setModeShutter(mode: CameraMode, ns: Long?) {
        manualShutterSpeedNs = ns
        prefs.edit().putLong(modeKey(mode, "shutter"), ns ?: -1L).apply()
    }

    fun getModeShutter(mode: CameraMode): Long? {
        val ns = prefs.getLong(modeKey(mode, "shutter"), -1L)
        return if (ns > 0) ns else manualShutterSpeedNs
    }

    fun setModeFocusDistance(mode: CameraMode, distance: Float) {
        manualFocusDistance = distance
        prefs.edit().putFloat(modeKey(mode, "focus_dist"), distance).apply()
    }

    fun getModeFocusDistance(mode: CameraMode): Float {
        return prefs.getFloat(modeKey(mode, "focus_dist"), manualFocusDistance)
    }

    fun setModeVideoStabilization(mode: CameraMode, enabled: Boolean) {
        isVideoStabilizationEnabled = enabled
        prefs.edit().putBoolean(modeKey(mode, "video_stab"), enabled).apply()
    }

    fun getModeVideoStabilization(mode: CameraMode): Boolean {
        return if (prefs.contains(modeKey(mode, "video_stab"))) {
            prefs.getBoolean(modeKey(mode, "video_stab"), true)
        } else isVideoStabilizationEnabled
    }

    fun setModeVideoFps(mode: CameraMode, fps: Int) {
        videoFps = fps
        prefs.edit().putInt(modeKey(mode, "video_fps"), fps).apply()
    }

    fun getModeVideoFps(mode: CameraMode): Int {
        return prefs.getInt(modeKey(mode, "video_fps"), videoFps)
    }

    fun setModeVideoBitrate(mode: CameraMode, bitrate: VideoBitrateOption) {
        videoBitrate = bitrate
        prefs.edit().putString(modeKey(mode, "video_bitrate"), bitrate.name).apply()
    }

    fun getModeVideoBitrate(mode: CameraMode): VideoBitrateOption {
        val name = prefs.getString(modeKey(mode, "video_bitrate"), null) ?: return videoBitrate
        return try { VideoBitrateOption.valueOf(name) } catch (e: Exception) { videoBitrate }
    }

    fun setModeVideoResolution(mode: CameraMode, width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        prefs.edit()
            .putInt(modeKey(mode, "video_w"), width)
            .putInt(modeKey(mode, "video_h"), height)
            .apply()
    }

    fun getModeVideoWidth(mode: CameraMode): Int {
        return prefs.getInt(modeKey(mode, "video_w"), videoWidth)
    }

    fun getModeVideoHeight(mode: CameraMode): Int {
        return prefs.getInt(modeKey(mode, "video_h"), videoHeight)
    }

    fun setModeAudioEnabled(mode: CameraMode, enabled: Boolean) {
        isAudioEnabled = enabled
        prefs.edit().putBoolean(modeKey(mode, "audio_enabled"), enabled).apply()
    }

    fun getModeAudioEnabled(mode: CameraMode): Boolean {
        return if (prefs.contains(modeKey(mode, "audio_enabled"))) {
            prefs.getBoolean(modeKey(mode, "audio_enabled"), true)
        } else isAudioEnabled
    }

    fun setModeAutoHdr(mode: CameraMode, enabled: Boolean) {
        autoHdrEnabled = enabled
        prefs.edit().putBoolean(modeKey(mode, "auto_hdr"), enabled).apply()
    }

    fun getModeAutoHdr(mode: CameraMode): Boolean {
        return if (prefs.contains(modeKey(mode, "auto_hdr"))) {
            prefs.getBoolean(modeKey(mode, "auto_hdr"), true)
        } else autoHdrEnabled
    }

    fun setModeHdrPlusEnabled(mode: CameraMode, enabled: Boolean) {
        isHdrPlusEnabled = enabled
        prefs.edit().putBoolean(modeKey(mode, "hdr_plus_enabled"), enabled).apply()
    }

    fun getModeHdrPlusEnabled(mode: CameraMode): Boolean {
        return if (prefs.contains(modeKey(mode, "hdr_plus_enabled"))) {
            prefs.getBoolean(modeKey(mode, "hdr_plus_enabled"), false)
        } else isHdrPlusEnabled
    }

    fun setModeHdrPlusFrameCount(mode: CameraMode, frameCount: com.example.camera.engine.hdrplus.HdrPlusFrameCount) {
        hdrPlusFrameCount = frameCount
        prefs.edit().putInt(modeKey(mode, "hdr_plus_frame_count"), frameCount.count).apply()
    }

    fun getModeHdrPlusFrameCount(mode: CameraMode): com.example.camera.engine.hdrplus.HdrPlusFrameCount {
        return if (prefs.contains(modeKey(mode, "hdr_plus_frame_count"))) {
            val c = prefs.getInt(modeKey(mode, "hdr_plus_frame_count"), 2)
            com.example.camera.engine.hdrplus.HdrPlusFrameCount.fromInt(c)
        } else hdrPlusFrameCount
    }

    fun setModeAutoFraming(mode: CameraMode, enabled: Boolean) {
        autoFramingEnabled = enabled
        prefs.edit().putBoolean(modeKey(mode, "auto_framing"), enabled).apply()
    }

    fun getModeAutoFraming(mode: CameraMode): Boolean {
        return if (prefs.contains(modeKey(mode, "auto_framing"))) {
            prefs.getBoolean(modeKey(mode, "auto_framing"), true)
        } else autoFramingEnabled
    }

    fun setModePhotoFilter(mode: CameraMode, filter: PhotoFilter) {
        selectedPhotoFilter = filter
        prefs.edit().putString(modeKey(mode, "filter"), filter.name).apply()
    }

    fun getModePhotoFilter(mode: CameraMode): PhotoFilter {
        val name = prefs.getString(modeKey(mode, "filter"), null) ?: return selectedPhotoFilter
        return try { PhotoFilter.valueOf(name) } catch (e: Exception) { selectedPhotoFilter }
    }

    fun setModePortraitConfig(mode: CameraMode, config: PortraitConfig) {
        portraitBlurStrength = config.blurStrength
        portraitAperture = config.simulatedAperture
        portraitOpticalBlurGuided = config.opticalBlurGuided
        prefs.edit()
            .putFloat(modeKey(mode, "portrait_blur"), config.blurStrength)
            .putString(modeKey(mode, "portrait_aperture"), config.simulatedAperture)
            .putBoolean(modeKey(mode, "portrait_optical_blur_guided"), config.opticalBlurGuided)
            .apply()
    }

    fun getModePortraitConfig(mode: CameraMode): PortraitConfig {
        val blur = prefs.getFloat(modeKey(mode, "portrait_blur"), portraitBlurStrength)
        val ap = prefs.getString(modeKey(mode, "portrait_aperture"), portraitAperture) ?: portraitAperture
        val opticalGuided = prefs.getBoolean(modeKey(mode, "portrait_optical_blur_guided"), portraitOpticalBlurGuided)
        return PortraitConfig(blurStrength = blur, simulatedAperture = ap, opticalBlurGuided = opticalGuided)
    }

    fun setModeNightConfig(mode: CameraMode, config: NightConfig) {
        nightConfig = config
        prefs.edit()
            .putInt(modeKey(mode, "night_duration"), config.durationSeconds)
            .putBoolean(modeKey(mode, "night_fusion"), config.multiFrameFusionEnabled)
            .putBoolean(modeKey(mode, "night_anti_ghost"), config.antiGhostingEnabled)
            .putFloat(modeKey(mode, "night_noise"), config.noiseSuppression)
            .putFloat(modeKey(mode, "night_shadow"), config.shadowLift)
            .apply()
    }

    fun getModeNightConfig(mode: CameraMode): NightConfig {
        if (!prefs.contains(modeKey(mode, "night_duration"))) return nightConfig
        val dur = prefs.getInt(modeKey(mode, "night_duration"), nightDurationSeconds)
        val fus = prefs.getBoolean(modeKey(mode, "night_fusion"), nightIsMultiFrameFusion)
        val ag = prefs.getBoolean(modeKey(mode, "night_anti_ghost"), nightIsAntiGhostingEnabled)
        val ns = prefs.getFloat(modeKey(mode, "night_noise"), nightNoiseSuppression)
        val sl = prefs.getFloat(modeKey(mode, "night_shadow"), nightShadowLift)
        return NightConfig(
            durationSeconds = dur,
            multiFrameFusionEnabled = fus,
            antiGhostingEnabled = ag,
            noiseSuppression = ns,
            shadowLift = sl
        )
    }

    fun setModeCinemaConfig(mode: CameraMode, config: CinemaConfig) {
        saveCinemaConfig(config)
    }

    fun getModeCinemaConfig(mode: CameraMode): CinemaConfig {
        return getCinemaConfig()
    }

    fun setModeTapFocusConfig(mode: CameraMode, config: TapFocusConfig) {
        tapFocusConfig = config
    }

    fun getModeTapFocusConfig(mode: CameraMode): TapFocusConfig {
        return tapFocusConfig
    }

    fun setModeHybridStabilizationConfig(mode: CameraMode, config: HybridStabilizationConfig) {
        hybridStabilizationConfig = config
    }

    fun getModeHybridStabilizationConfig(mode: CameraMode): HybridStabilizationConfig {
        return hybridStabilizationConfig
    }

    fun setModeWindNoiseReduction(mode: CameraMode, enabled: Boolean) {
        prefs.edit().putBoolean(modeKey(mode, "wind_noise_reduction"), enabled).apply()
    }

    fun getModeWindNoiseReduction(mode: CameraMode): Boolean {
        return prefs.getBoolean(modeKey(mode, "wind_noise_reduction"), windNoiseReduction)
    }

    fun resetAllSettingsToDefaults() {
        prefs.edit().clear().apply()
    }

    // --- Custom Image Processing Pipeline Persistence ---

    var isCustomPipelineEnabled: Boolean
        get() = prefs.getBoolean("pref_custom_pipeline_enabled", false)
        set(value) = prefs.edit().putBoolean("pref_custom_pipeline_enabled", value).apply()

    var activePipelinePresetId: String
        get() = prefs.getString("pref_active_pipeline_preset_id", com.example.camera.pipeline.model.PipelinePreset.HASSELBLAD.id)
            ?: com.example.camera.pipeline.model.PipelinePreset.HASSELBLAD.id
        set(value) = prefs.edit().putString("pref_active_pipeline_preset_id", value).apply()

    private val moshi by lazy {
        com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
    }

    fun getActivePipelinePreset(): com.example.camera.pipeline.model.PipelinePreset {
        val presetId = activePipelinePresetId
        val customPresets = getCustomPresets()
        val custom = customPresets.firstOrNull { it.id == presetId }
        if (custom != null) return custom
        return com.example.camera.pipeline.model.PipelinePreset.BUILT_IN_PRESETS.firstOrNull { it.id == presetId }
            ?: com.example.camera.pipeline.model.PipelinePreset.HASSELBLAD
    }

    fun saveActivePipelinePreset(preset: com.example.camera.pipeline.model.PipelinePreset) {
        activePipelinePresetId = preset.id
        savePipelineParams(preset.id, preset.params)
    }

    fun getPipelineParams(presetId: String): com.example.camera.pipeline.model.CustomPipelineParams {
        val json = prefs.getString("pref_pipeline_params_$presetId", null)
        if (json != null) {
            try {
                val adapter = moshi.adapter(com.example.camera.pipeline.model.CustomPipelineParams::class.java)
                val params = adapter.fromJson(json)
                if (params != null) return params
            } catch (e: Exception) {
                // fallback
            }
        }
        val defaultPreset = com.example.camera.pipeline.model.PipelinePreset.BUILT_IN_PRESETS.firstOrNull { it.id == presetId }
            ?: com.example.camera.pipeline.model.PipelinePreset.HASSELBLAD
        return defaultPreset.params
    }

    fun savePipelineParams(presetId: String, params: com.example.camera.pipeline.model.CustomPipelineParams) {
        try {
            val adapter = moshi.adapter(com.example.camera.pipeline.model.CustomPipelineParams::class.java)
            val json = adapter.toJson(params)
            prefs.edit().putString("pref_pipeline_params_$presetId", json).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun getCustomPresets(): List<com.example.camera.pipeline.model.PipelinePreset> {
        val json = prefs.getString("pref_custom_pipeline_presets_list", null) ?: return emptyList()
        return try {
            val type = com.squareup.moshi.Types.newParameterizedType(
                List::class.java,
                com.example.camera.pipeline.model.PipelinePreset::class.java
            )
            val adapter = moshi.adapter<List<com.example.camera.pipeline.model.PipelinePreset>>(type)
            adapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCustomPreset(preset: com.example.camera.pipeline.model.PipelinePreset) {
        val current = getCustomPresets().toMutableList()
        val index = current.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            current[index] = preset
        } else {
            current.add(preset)
        }
        try {
            val type = com.squareup.moshi.Types.newParameterizedType(
                List::class.java,
                com.example.camera.pipeline.model.PipelinePreset::class.java
            )
            val adapter = moshi.adapter<List<com.example.camera.pipeline.model.PipelinePreset>>(type)
            val json = adapter.toJson(current)
            prefs.edit().putString("pref_custom_pipeline_presets_list", json).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun deleteCustomPreset(presetId: String) {
        val current = getCustomPresets().filterNot { it.id == presetId }
        try {
            val type = com.squareup.moshi.Types.newParameterizedType(
                List::class.java,
                com.example.camera.pipeline.model.PipelinePreset::class.java
            )
            val adapter = moshi.adapter<List<com.example.camera.pipeline.model.PipelinePreset>>(type)
            val json = adapter.toJson(current)
            prefs.edit().putString("pref_custom_pipeline_presets_list", json).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun getVideoAdjustments(): com.example.camera.model.VideoAdjustments {
        return com.example.camera.model.VideoAdjustments(
            tonality = prefs.getFloat("pref_va_tonality", 0f),
            saturation = prefs.getFloat("pref_va_saturation", 0f),
            contrast = prefs.getFloat("pref_va_contrast", 0f),
            exposure = prefs.getFloat("pref_va_exposure", 0.0f),
            temperature = prefs.getFloat("pref_va_temperature", 0f),
            tint = prefs.getFloat("pref_va_tint", 0f),
            highlights = prefs.getFloat("pref_va_highlights", 0f),
            shadows = prefs.getFloat("pref_va_shadows", 0f),
            vignette = prefs.getFloat("pref_va_vignette", 0f),
            grain = prefs.getFloat("pref_va_grain", 0f),
            clarity = prefs.getFloat("pref_va_clarity", 0f),
            sharpness = prefs.getFloat("pref_va_sharpness", 0f),
            curveBlacks = prefs.getFloat("pref_va_curve_blacks", 0f),
            curveShadows = prefs.getFloat("pref_va_curve_shadows", 0f),
            curveMidtones = prefs.getFloat("pref_va_curve_midtones", 0f),
            curveHighlights = prefs.getFloat("pref_va_curve_highlights", 0f),
            curveWhites = prefs.getFloat("pref_va_curve_whites", 0f),
            colorVibrance = prefs.getFloat("pref_va_color_vibrance", 0f),
            colorBalanceR = prefs.getFloat("pref_va_color_bal_r", 0f),
            colorBalanceG = prefs.getFloat("pref_va_color_bal_g", 0f),
            colorBalanceB = prefs.getFloat("pref_va_color_bal_b", 0f),
            lightFxFlash = prefs.getFloat("pref_va_lfx_flash", 0f),
            lightFxBloom = prefs.getFloat("pref_va_lfx_bloom", 0f),
            lightFxSoftLight = prefs.getFloat("pref_va_lfx_soft_light", 0f),
            textureFilmGrain = prefs.getFloat("pref_va_tex_grain", 0f),
            textureHalation = prefs.getFloat("pref_va_tex_halation", 0f),
            textureMicroContrast = prefs.getFloat("pref_va_tex_micro_contrast", 0f)
        )
    }

    fun saveVideoAdjustments(va: com.example.camera.model.VideoAdjustments) {
        prefs.edit()
            .putFloat("pref_va_tonality", va.tonality)
            .putFloat("pref_va_saturation", va.saturation)
            .putFloat("pref_va_contrast", va.contrast)
            .putFloat("pref_va_exposure", va.exposure)
            .putFloat("pref_va_temperature", va.temperature)
            .putFloat("pref_va_tint", va.tint)
            .putFloat("pref_va_highlights", va.highlights)
            .putFloat("pref_va_shadows", va.shadows)
            .putFloat("pref_va_vignette", va.vignette)
            .putFloat("pref_va_grain", va.grain)
            .putFloat("pref_va_clarity", va.clarity)
            .putFloat("pref_va_sharpness", va.sharpness)
            .putFloat("pref_va_curve_blacks", va.curveBlacks)
            .putFloat("pref_va_curve_shadows", va.curveShadows)
            .putFloat("pref_va_curve_midtones", va.curveMidtones)
            .putFloat("pref_va_curve_highlights", va.curveHighlights)
            .putFloat("pref_va_curve_whites", va.curveWhites)
            .putFloat("pref_va_color_vibrance", va.colorVibrance)
            .putFloat("pref_va_color_bal_r", va.colorBalanceR)
            .putFloat("pref_va_color_bal_g", va.colorBalanceG)
            .putFloat("pref_va_color_bal_b", va.colorBalanceB)
            .putFloat("pref_va_lfx_flash", va.lightFxFlash)
            .putFloat("pref_va_lfx_bloom", va.lightFxBloom)
            .putFloat("pref_va_lfx_soft_light", va.lightFxSoftLight)
            .putFloat("pref_va_tex_grain", va.textureFilmGrain)
            .putFloat("pref_va_tex_halation", va.textureHalation)
            .putFloat("pref_va_tex_micro_contrast", va.textureMicroContrast)
            .apply()
    }
}
