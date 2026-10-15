package com.example.camera.dollyzoom

data class DollyCropState(
    val scaleFactor: Float = 1.0f,
    val focusNormX: Float = 0.5f,
    val focusNormY: Float = 0.5f,
    val postFilterBoxNorm: android.graphics.RectF = android.graphics.RectF(0.2f, 0.2f, 0.8f, 0.8f)
)

data class DollyTrajectoryPoint(val timestamp: Long, val scale: Float)

class DollyZoomEngine {
    val cropStateFlow = kotlinx.coroutines.flow.MutableStateFlow(DollyCropState())
    fun start() {}
    fun stop() {}
    fun onFrameFaces(faces: Array<android.hardware.camera2.params.Face>, activeArray: android.graphics.Rect?, sensorOrientation: Int, isFront: Boolean) {}
    fun lockSubjectAt(normX: Float, normY: Float) {}
    fun startRecordingTrajectory() {}
    fun pauseRecordingTrajectory() {}
    fun resumeRecordingTrajectory() {}
    fun stopRecordingTrajectory(): List<DollyTrajectoryPoint> = emptyList()
}

object DollyZoomVideoProcessor {
    fun processDollyZoomVideo(
        inputFile: java.io.File,
        outputFile: java.io.File,
        trajectory: List<DollyTrajectoryPoint>,
        aspectRatio: Float
    ): java.io.File = inputFile
}
