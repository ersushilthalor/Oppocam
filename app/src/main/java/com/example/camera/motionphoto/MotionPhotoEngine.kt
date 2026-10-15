package com.example.camera.motionphoto

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MotionPhotoEngine {
    private val _isRecordingPostShutter = MutableStateFlow(false)
    val isRecordingPostShutter: StateFlow<Boolean> = _isRecordingPostShutter.asStateFlow()

    fun beginMotionCapture(duration: MotionPhotoDuration, shutterTimestampNs: Long, onComplete: () -> Unit) { 
        onComplete() 
    }
    fun finalizeMotionPhoto(stillJpegBytes: ByteArray, duration: MotionPhotoDuration, orientationDegrees: Int): ByteArray = stillJpegBytes
}
