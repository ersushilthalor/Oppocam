package com.example.camera.engine

import android.view.Surface
import com.example.camera.model.PreviewStreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

enum class ViewfinderResolution(val width: Int, val height: Int) {
    HD_720P(1280, 720),
    RES_2K(2560, 1440),
    FHD_1080P(1920, 1080),
    UHD_4K(3840, 2160);

    val maxDimension: Int
        get() = maxOf(width, height)
}

class DualCameraRecordingRelay(
    val encoderTargetSurface: Surface? = null,
    val bufferWidth: Int = 0,
    val bufferHeight: Int = 0,
    val fps: Int = 30,
    val is10Bit: Boolean = false,
    val initialSource: Any? = null,
    val cinemaRecorder: Any? = null,
    val sensorOrientation: Int = 0,
    val deviceRotation: Int = 0,
    val isFront: Boolean = false,
    val previewTargetSurface: Surface? = null,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val initialAdjustments: Any? = null
) {
    val relayedFrameCount = AtomicLong(0L)
    val isRelayReady: Boolean = true
    val isRelayPreviewActive: Boolean = true

    val activeSource: PreviewStreamSource = when (initialSource) {
        is PreviewStreamSource -> initialSource
        else -> PreviewStreamSource.MAIN
    }

    fun startRecording() {}
    fun pause() {}
    fun resume() {}
    fun stopRecording() {}
    fun flush() {}
    fun flush(arg: Any?) {}
    fun release() {}
    fun setActiveSource(source: Any) {}
    fun updateCameraFacing(vararg args: Any?) {}
    fun getRecorderSurfaceForLens(lens: Any): Surface? = encoderTargetSurface
    fun hasDistinctUltraWideSurface(): Boolean = false
    fun updateInputBufferSize(width: Int, height: Int) {}
    fun updateInputBufferSize(isUltraWide: Boolean, width: Int, height: Int) {}
    fun updateVideoAdjustments(adjustments: Any) {}

    val mainRecorderSurface: Surface? get() = encoderTargetSurface
    val ultraWideRecorderSurface: Surface? get() = encoderTargetSurface

    operator fun get(index: Int): Any? = null
}

fun Any?.isValid(): Boolean = this != null
