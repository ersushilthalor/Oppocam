package com.example.camera.motionphoto

import android.graphics.Bitmap

/**
 * Duration settings for Motion Photo capture.
 *
 * 1 Second: 0.5s before shutter + 0.5s after shutter
 * 2 Seconds: 1.0s before shutter + 1.0s after shutter (Default)
 */
enum class MotionPhotoDuration(
    val beforeDurationMs: Long,
    val afterDurationMs: Long,
    val totalDurationMs: Long,
    val label: String,
    val title: String,
    val description: String
) {
    ONE_SECOND(
        beforeDurationMs = 500L,
        afterDurationMs = 500L,
        totalDurationMs = 1000L,
        label = "1s",
        title = "1 Second",
        description = "0.5s before + 0.5s after shutter"
    ),
    TWO_SECONDS(
        beforeDurationMs = 1000L,
        afterDurationMs = 1000L,
        totalDurationMs = 2000L,
        label = "2s",
        title = "2 Seconds (Default)",
        description = "1.0s before + 1.0s after shutter"
    );

    companion object {
        val DEFAULT = TWO_SECONDS

        fun fromName(name: String?): MotionPhotoDuration {
            return when (name?.uppercase()) {
                "ONE_SECOND", "1S", "1_SEC", "1" -> ONE_SECOND
                "TWO_SECONDS", "2S", "2_SEC", "2" -> TWO_SECONDS
                else -> DEFAULT
            }
        }
    }
}

/**
 * A single sampled preview frame in the motion buffer.
 */
data class MotionFrame(
    val bitmap: Bitmap,
    val timestampNs: Long,
    val orientationDegrees: Int,
    val isFrontCamera: Boolean = false
)

/**
 * State of Motion Photo recording/packaging.
 */
sealed class MotionPhotoState {
    object Idle : MotionPhotoState()
    object Buffering : MotionPhotoState()
    data class RecordingPostShutter(val progress: Float) : MotionPhotoState()
    data class Processing(val status: String) : MotionPhotoState()
}
