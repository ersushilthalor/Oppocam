package com.example.camera.tracking.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.camera.tracking.viewmodel.CameraTrackingViewModel

/**
 * Full screen for AI Subject Tracking mode.
 * Accessible via More Modes → AI Subject Tracking.
 */
@Composable
fun AiSubjectTrackingScreen(
    onBack: () -> Unit,
    viewModel: CameraTrackingViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(lifecycleOwner) {
        viewModel.initCamera(lifecycleOwner, context)
        onDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("ai_subject_tracking_screen")
    ) {
        // Real-Time 3x Tracked Viewfinder
        TrackingViewfinder(
            currentFrame = uiState.currentFrame,
            cropWindow = uiState.cropWindow,
            cropController = viewModel.cropController,
            trackingStatus = uiState.trackingStatus,
            activeSubject = uiState.activeSubject,
            allDetections = uiState.allDetections,
            flashFeedback = uiState.flashFeedback,
            aspectRatio = uiState.aspectRatio,
            isGimbalEnabled = uiState.isGimbalEnabled,
            gimbalState = uiState.gimbalState,
            isCinematicPanActive = uiState.isCinematicPanActive,
            cinematicPanProgress = uiState.cinematicPanProgress,
            onTapToTrack = { vfX, vfY ->
                viewModel.onTapToTrack(vfX, vfY)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Stationary Controls & HUD Overlay
        CameraControlsOverlay(
            uiState = uiState,
            onBackToMainCamera = onBack,
            onFlipCamera = { viewModel.flipCamera() },
            onCaptureModeChanged = { viewModel.setCaptureMode(it) },
            onShutterClick = { viewModel.onShutterClicked(context) },
            onUnlockTracking = { viewModel.unlockTracking() },
            onOpenMediaReview = { viewModel.openMediaReview(it) },
            onOpenSettings = { viewModel.setSettingsOpen(true) },
            onToggleAspectRatio = { viewModel.cycleAspectRatio() },
            onToggleGimbal = { viewModel.toggleGimbal(it) },
            onStartCinematicPan = { viewModel.startCinematicPan(it) },
            onStopCinematicPan = { viewModel.stopCinematicPan() },
            onCameraLensChanged = { viewModel.setCameraLens(it) },
            onFpsOptionChanged = { viewModel.setTrackingFps(it) },
            onToggleTrackingResolution = { viewModel.toggleTrackingResolution() },
            onDismissResolutionNotice = { viewModel.dismissTrackingResolutionNotice() },
            modifier = Modifier.fillMaxSize()
        )

        // Settings Bottom Sheet
        if (uiState.isSettingsOpen) {
            SettingsBottomSheet(
                uiState = uiState,
                onDismiss = { viewModel.setSettingsOpen(false) },
                onAspectRatioChanged = { viewModel.setAspectRatio(it) },
                onTrackingIntensityChanged = { viewModel.setTrackingIntensity(it) },
                onVideoResolutionChanged = { viewModel.setVideoResolution(it) },
                onViewfinderResolutionChanged = { viewModel.setViewfinderResolution(it) },
                onTrackingResolutionChanged = { viewModel.setTrackingResolution(it) },
                onCameraLensChanged = { viewModel.setCameraLens(it) },
                onFpsOptionChanged = { viewModel.setTrackingFps(it) },
                onClearLearnedProfiles = { viewModel.clearLearnedSubjects() },
                onToggleGimbal = { viewModel.toggleGimbal(it) },
                onGimbalSensitivityChanged = { viewModel.setGimbalSensitivity(it) },
                onStartCinematicPan = { viewModel.startCinematicPan(it) },
                onStopCinematicPan = { viewModel.stopCinematicPan() }
            )
        }

        // Media Review Dialog (Output Validation & Gallery inspection)
        uiState.reviewingMediaItem?.let { mediaItem ->
            MediaReviewDialog(
                item = mediaItem,
                onDismiss = { viewModel.closeMediaReview() }
            )
        }
    }
}
