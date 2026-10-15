package com.example.camera.stableaction

class StableActionHorizonEngine {
    val rollDegreesFlow = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val motionOffsetFlow = kotlinx.coroutines.flow.MutableStateFlow(Pair(0f, 0f))
    var smoothedRoll: Float = 0f
    var smoothedNormX: Float = 0f
    var smoothedNormY: Float = 0f

    fun start() {}
    fun stop() {}
    fun startRecordingTrajectory() {}
    fun pauseRecordingTrajectory() {}
    fun resumeRecordingTrajectory() {}
    fun isTrajectoryRecording(): Boolean = false
    fun stopRecordingTrajectory(): List<TrajectoryPoint> = emptyList()

    data class TrajectoryPoint(val timestamp: Long, val roll: Float, val normX: Float, val normY: Float)
}

object StableActionVideoProcessor {
    fun processHorizonLockVideo(
        inputFile: java.io.File,
        outputFile: java.io.File,
        trajectory: List<StableActionHorizonEngine.TrajectoryPoint>,
        aspectRatio: Float,
        orientationDegrees: Int
    ): java.io.File = inputFile
}
