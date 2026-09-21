package com.example.camera.tracking.model

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri

/**
 * Normalized bounding box coordinates [0..1].
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0.001f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.001f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }

    fun toRectF(): RectF = RectF(left, top, right, bottom)
}

/**
 * Supported Camera Aspect Ratios for AI Tracking.
 */
enum class TrackingAspectRatio(
    val label: String,
    val ratioValue: Float?, // width / height, or null for sensor match
    val description: String
) {
    RATIO_9_16("9:16", 9f / 16f, "Portrait / Stories / Reels"),
    RATIO_16_9("16:9", 16f / 9f, "Cinematic Widescreen"),
    RATIO_4_3("4:3", 4f / 3f, "Classic Photography"),
    RATIO_1_1("1:1", 1f / 1f, "Square Format"),
    RATIO_FULL("FULL", null, "Full Camera Sensor")
}

/**
 * Viewfinder & Camera Sensor Processing Resolution (2K to 4K support).
 */
enum class ViewfinderResolution(
    val label: String,
    val width: Int,
    val height: Int,
    val description: String
) {
    HD_720P("720p HD", 720, 1280, "720 × 1280 (Lightweight 30 FPS Preview)"),
    FHD_1080P("1080p FHD", 1080, 1920, "1080 × 1920 (High-Speed 60fps)"),
    QHD_2K("2K QHD", 1440, 2560, "1440 × 2560 (Ultra-Sharp 2K)"),
    UHD_4K("4K UHD", 2160, 3840, "2160 × 3840 (Studio Master 4K)")
}

/**
 * AI Tracking Input Resolution.
 * Controls the resolution fed into AI detection and lightweight tracking.
 * Camera preview resolution remains unchanged.
 */
enum class TrackingResolution(
    val label: String,
    val width: Int,
    val height: Int,
    val description: String
) {
    HD_720P("720p HD", 720, 1280, "720p (Maximum 30 FPS Performance)"),
    FHD_1080P("1080p FHD", 1080, 1920, "1080p (High Detail Tracking)")
}

/**
 * Supported Output Video Resolutions.
 */
enum class VideoResolution(
    val label: String,
    val width: Int,
    val height: Int,
    val bitRate: Int
) {
    HD_720P("720p HD", 720, 1280, 4_500_000),
    FHD_1080P("1080p FHD", 1080, 1920, 9_000_000),
    UHD_4K("4K UHD", 2160, 3840, 22_000_000)
}

/**
 * Cinematic Pan state and configuration.
 */
data class CinematicPanConfig(
    val isPanning: Boolean = false,
    val durationSec: Float = 5.0f,
    val progress: Float = 0f,
    val directionLeftToRight: Boolean = true
)

/**
 * Digital Gimbal (Rock-Steady 3x EIS) stabilization state.
 */
data class GimbalState(
    val isEnabled: Boolean = false,
    val sensitivity: Float = 1.0f,
    val pitchAngle: Float = 0f, // in degrees
    val rollAngle: Float = 0f,  // in degrees
    val offsetX: Float = 0f,    // normalized compensation offset
    val offsetY: Float = 0f     // normalized compensation offset
)

/**
 * Selectable Camera Lenses for AI Tracking (Ultra-Wide, Main Wide, Front Selfie).
 */
enum class TrackingCameraLens(
    val label: String,
    val zoomLabel: String,
    val isFront: Boolean = false
) {
    ULTRAWIDE("Ultra-Wide", "0.5×", false),
    WIDE("Wide (Main)", "1.0×", false),
    FRONT("Front Selfie", "Front", true);

    val displayName: String get() = "$zoomLabel $label"
}

/**
 * Selectable Tracking Processing Rates.
 */
enum class TrackingFpsOption(
    val label: String,
    val targetFps: Int,
    val loopDelayMs: Long
) {
    FPS_30("30 FPS", 30, 33L),
    FPS_60("60 FPS", 60, 16L),
    FPS_MAX("MAX / 120 FPS", 120, 8L)
}

/**
 * A tracked subject detected by AI or manual lock.
 */
data class TrackedSubject(
    val trackingId: Int,
    val bounds: NormalizedRect,
    val label: String = "Subject",
    val confidence: Float = 0.95f,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val colorHistogram: FloatArray? = null,
    val lockQuality: Float = 1.0f,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val isConfirmedByAi: Boolean = true,
    val isHuman: Boolean = false,
    val isMoving: Boolean = false,
    val movementSpeed: Float = 0f,
    val learnedAffinity: Float = 0f
)

/**
 * Active tracking state machine.
 */
enum class TrackingStatus {
    IDLE,               // No subject locked; 1x preview
    LOCKING,            // User tapped, locking onto subject
    TRACKING_LOCKED,    // Actively tracking locked subject with 3x crop
    OCCLUDED_PREDICTING,// Subject briefly lost; predicting trajectory
    LOST,               // Subject completely exited or lost
    CINEMATIC_PAN       // Automated constant velocity cinematic pan
}

/**
 * Crop window in normalized coordinates [0..1] of the source frame.
 */
data class CropWindow(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val zoomFactor: Float = 3.0f
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        val FULL_FRAME = CropWindow(0f, 0f, 1f, 1f, 1.0f)

        fun centeredAt(cx: Float, cy: Float, zoom: Float): CropWindow {
            val w = 1f / zoom
            val h = 1f / zoom
            val hw = w / 2f
            val hh = h / 2f
            val clampedCx = cx.coerceIn(hw, 1f - hw)
            val clampedCy = cy.coerceIn(hh, 1f - hh)
            return CropWindow(
                left = clampedCx - hw,
                top = clampedCy - hh,
                right = clampedCx + hw,
                bottom = clampedCy + hh,
                zoomFactor = zoom
            )
        }
    }
}

enum class CameraCaptureMode {
    PHOTO,
    VIDEO
}

data class CapturedMediaItem(
    val uri: Uri,
    val filePath: String,
    val isVideo: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val cropWindow: CropWindow = CropWindow.FULL_FRAME,
    val subjectLabel: String? = null,
    val resolutionLabel: String = "1080p",
    val aspectRatioLabel: String = "9:16",
    val thumbnailBitmap: Bitmap? = null,
    val galleryUri: Uri? = null,
    val isSavedToGallery: Boolean = true
)

data class CameraTrackingUiState(
    val currentFrame: Bitmap? = null,
    val cropWindow: CropWindow = CropWindow.FULL_FRAME,
    val trackingStatus: TrackingStatus = TrackingStatus.IDLE,
    val activeSubject: TrackedSubject? = null,
    val allDetections: List<TrackedSubject> = emptyList(),
    val flashFeedback: Boolean = false,
    val aspectRatio: TrackingAspectRatio = TrackingAspectRatio.RATIO_9_16,
    val isGimbalEnabled: Boolean = true,
    val gimbalState: GimbalState = GimbalState(),
    val gimbalSensitivity: Float = 1.0f,
    val isCinematicPanActive: Boolean = false,
    val cinematicPanProgress: Float = 0f,
    val cinematicPanDurationSec: Float = 5.0f,
    val isSettingsOpen: Boolean = false,
    val reviewingMediaItem: CapturedMediaItem? = null,
    val fps: Int = 30,
    val trackingIntensity: Float = 1.0f,
    val isRecording: Boolean = false,
    val recordingDurationSec: Int = 0,
    val captureMode: CameraCaptureMode = CameraCaptureMode.VIDEO,
    val lastCapturedMedia: CapturedMediaItem? = null,
    val currentZoom: Float = 1.0f,
    val targetZoom: Float = 3.0f,
    val videoResolution: VideoResolution = VideoResolution.FHD_1080P,
    val viewfinderResolution: ViewfinderResolution = ViewfinderResolution.HD_720P,
    val isFrontCamera: Boolean = false,
    val isTorchOn: Boolean = false,
    val selectedLens: TrackingCameraLens = TrackingCameraLens.WIDE,
    val availableLenses: List<TrackingCameraLens> = listOf(
        TrackingCameraLens.ULTRAWIDE,
        TrackingCameraLens.WIDE,
        TrackingCameraLens.FRONT
    ),
    val selectedFpsOption: TrackingFpsOption = TrackingFpsOption.FPS_30,
    val trackingResolution: TrackingResolution = TrackingResolution.HD_720P,
    val trackingResolutionNotice: String? = null,
    val learnedSubjectsCount: Int = 0
)
