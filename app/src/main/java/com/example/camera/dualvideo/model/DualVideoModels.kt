package com.example.camera.dualvideo.model

import android.net.Uri
import android.util.Size
import com.example.camera.model.LensInfo

enum class DualVideoLayout(val title: String, val shortName: String) {
    PIP("Pop-up / PiP", "PiP"),
    SPLIT_TOP_BOTTOM("Split Screen (Top/Bottom)", "Split"),
    SPLIT_LEFT_RIGHT("Equal View (Side by Side)", "Equal")
}

enum class PipPosition {
    TOP_RIGHT,
    TOP_LEFT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT
}

data class DualVideoResolution(
    val width: Int,
    val height: Int,
    val label: String
) {
    val portraitWidth: Int get() = minOf(width, height)
    val portraitHeight: Int get() = maxOf(width, height)
    val size: Size get() = Size(portraitWidth, portraitHeight)
    val aspectRatio: Float get() = portraitWidth.toFloat() / portraitHeight.toFloat()
}

data class DualCameraPair(
    val primaryLens: LensInfo,
    val secondaryLens: LensInfo,
    val isConcurrentHardwareSupported: Boolean = true
) {
    val id: String get() = "${primaryLens.cameraId}_${secondaryLens.cameraId}"
}

data class DualVideoCapability(
    val isHardwareConcurrentSupported: Boolean,
    val supportedPairs: List<DualCameraPair>,
    val supportedResolutions: List<DualVideoResolution>,
    val supportedFps: List<Int>,
    val hardwareDiagnosticMessage: String? = null
)

data class DualVideoConfig(
    val layout: DualVideoLayout = DualVideoLayout.PIP,
    val pipPosition: PipPosition = PipPosition.TOP_RIGHT,
    val resolution: DualVideoResolution = DualVideoResolution(1080, 1920, "1080p Full HD (1080x1920)"),
    val fps: Int = 30,
    val isAudioEnabled: Boolean = true
)

data class DualVideoUiState(
    val isRecording: Boolean = false,
    val recordingDurationSeconds: Int = 0,
    val primaryLens: LensInfo? = null,
    val secondaryLens: LensInfo? = null,
    val availablePrimaryLenses: List<LensInfo> = emptyList(),
    val availableSecondaryLenses: List<LensInfo> = emptyList(),
    val config: DualVideoConfig = DualVideoConfig(),
    val capability: DualVideoCapability = DualVideoCapability(
        isHardwareConcurrentSupported = true,
        supportedPairs = emptyList(),
        supportedResolutions = emptyList(),
        supportedFps = listOf(30)
    ),
    val isStreamingActive: Boolean = false,
    val isSwitchingLens: Boolean = false,
    val lastRecordedVideoUri: Uri? = null,
    val errorMessage: String? = null
)
