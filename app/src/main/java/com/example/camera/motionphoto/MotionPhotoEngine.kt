package com.example.camera.engine

import android.net.Uri

class MotionPhotoEngine {
    var isRecordingPostShutter: Boolean = false
    fun beginMotionCapture(duration: Any, shutterTimestampNs: Long, onComplete: () -> Unit) { onComplete() }
    fun finalizeMotionPhoto(stillJpegBytes: ByteArray, duration: Any, orientationDegrees: Int): ByteArray = stillJpegBytes
}

enum class MotionPhotoDuration { SEC_1, SEC_2 }
