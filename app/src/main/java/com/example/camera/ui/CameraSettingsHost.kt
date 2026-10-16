package com.example.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.camera.viewmodel.CameraViewModel

/**
 * Dedicated host for camera settings. The shared settings screen owns state collection
 * so settings cannot drift out of sync with the ViewModel.
 */
@Composable
fun CameraSettingsHost(
    viewModel: CameraViewModel,
    onOpenCustomUiStudio: () -> Unit,
    onOpenPipelineStudio: () -> Unit,
    onOpenBeforeAfter: () -> Unit,
    onOpenGalleryChooser: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    CameraSettingsScreen(
        viewModel = viewModel,
        onDismiss = onDismiss,
        onOpenCustomUiStudio = onOpenCustomUiStudio
    )
}
