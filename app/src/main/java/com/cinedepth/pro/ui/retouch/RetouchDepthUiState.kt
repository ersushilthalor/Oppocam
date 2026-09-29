package com.cinedepth.pro.ui.retouch

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.cinedepth.pro.ui.BlurPreviewParams

data class RetouchDepthUiState(
    val imageUri: Uri? = null,
    val sourceBitmap: Bitmap? = null,
    val renderedBitmap: Bitmap? = null,
    val depthBitmap: Bitmap? = null,
    val params: BlurPreviewParams = BlurPreviewParams(),
    val focusPoint: Offset? = null,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val statusMessage: String? = null,
    val lastExportedUri: Uri? = null,
    val depthInferenceMs: Long = 0L,
    val shaderPassMs: Long = 0L,
    val showDepthMapOverlay: Boolean = false
)
