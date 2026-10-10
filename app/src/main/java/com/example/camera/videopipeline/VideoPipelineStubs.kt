package com.example.camera.videopipeline

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.io.File

enum class VideoPipelineType(val id: String, val title: String, val subtitle: String = title) {
    NORMAL("normal", "Normal", "Normal Pipeline"),
    CUSTOM("custom", "Custom", "Custom Pipeline");

    val displayName: String get() = title
}

data class CustomVideoPipelineConfig(
    val showPipelineMasterToggle: Boolean = true,
    val showPipelinePresetList: Boolean = true,
    val isRawSensorLogPipeline: Boolean = false
) {
    fun updateConfig(newConfig: CustomVideoPipelineConfig) {}
}

class CustomVideoPipelineRecorder(
    val outputFile: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val orientationHint: Int,
    val pipeline: Any?,
    val isFront: Boolean,
    val sensorOrientation: Int,
    val deviceRotation: Int,
    val sourceBufferWidth: Int,
    val sourceBufferHeight: Int
) {
    val framesProcessedCount: Long = 0L
    val displayName: String = "Custom"
    fun prepare(): android.view.Surface? = null
    fun start() {}
    fun pause() {}
    fun resume() {}
    fun stopAndRelease(): File? = outputFile
    fun setActiveStreamSource(source: Any?) {}
    fun updateUltraWideBufferSize(w: Int, h: Int) {}
}

object VideoPipelineManager {
    val displayName: String = "Custom"
    fun isCustomPipeline(type: Any?): Boolean = false
    fun getPipeline(type: Any?): Any = object {
        val displayName: String = "Custom"
    }
    fun applyPipelineToView(view: Any?, type: Any?) {}
    fun clearPipelineFromView(view: Any?) {}
    fun getCustomPipeline(): VideoPipelineManager = this
    fun updateConfig(config: Any?) {}
    fun processRecordedVideo(inputFile: File, outputFile: File, type: Any?, orientationDegrees: Int): File = inputFile
}

@Composable
fun CustomVideoPipelineSettingsPanel(
    config: CustomVideoPipelineConfig = CustomVideoPipelineConfig(),
    onConfigChange: (CustomVideoPipelineConfig) -> Unit = {},
    onResetDefaults: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {}
