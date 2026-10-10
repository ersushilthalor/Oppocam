package com.example.camera.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

data class NightConfig(
    val durationSeconds: Int = 3,
    val multiFrameFusionEnabled: Boolean = true,
    val tripodDetectionEnabled: Boolean = true
)

data class NightCaptureProgress(
    val isCapturing: Boolean = false,
    val progress: Float = 0f,
    val remainingSeconds: Int = 0
)

@Composable
fun NightModeOverlay(
    config: NightConfig,
    captureProgress: NightCaptureProgress,
    onDurationChange: (Int) -> Unit = {},
    onConfigChange: (NightConfig) -> Unit = {},
    onToggleNightHdr: () -> Unit = {},
    modifier: Modifier = Modifier
) {}
