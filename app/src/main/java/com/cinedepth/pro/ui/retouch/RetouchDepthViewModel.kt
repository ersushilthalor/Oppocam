package com.cinedepth.pro.ui.retouch

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cinedepth.pro.ui.BlurPreviewParams
import com.cinedepth.pro.ui.LensEffect
import com.cinedepth.pro.ui.blur.DepthBlurEngine
import com.cinedepth.pro.ui.blur.FaceAutoFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RetouchDepthViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RetouchDepthUiState())
    val uiState: StateFlow<RetouchDepthUiState> = _uiState.asStateFlow()

    private val depthEngine = DepthBlurEngine
    private val estimator by lazy { DepthBlurEngine.getEstimator(getApplication()) }

    fun loadImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true, imageUri = uri, statusMessage = "Estimating depth...") }
            try {
                val output = depthEngine.renderPreview(
                    context = getApplication(),
                    uri = uri,
                    params = _uiState.value.params,
                    maxDimension = 960,
                    highQualityDepth = true
                )

                // Attempt face autofocus if no manual focus is set
                val faceFocus = FaceAutoFocus.detectAndSampleDepth(output.sourceBitmap, output.depthMapBitmap)
                val initialParams = if (faceFocus != null) {
                    _uiState.value.params.copy(focusDepth = faceFocus.depthValue.toFloat())
                } else {
                    _uiState.value.params
                }

                val finalBmp = if (faceFocus != null) {
                    depthEngine.rerenderPreviewFromCache(output.sourceBitmap, output.depthMapBitmap, initialParams)
                } else {
                    output.bitmap
                }

                _uiState.update {
                    it.copy(
                        sourceBitmap = output.sourceBitmap,
                        renderedBitmap = finalBmp,
                        depthBitmap = output.depthMapBitmap,
                        params = initialParams,
                        isLoading = false,
                        statusMessage = "Ready",
                        depthInferenceMs = output.depthInferenceMs,
                        shaderPassMs = output.shaderPassMs
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isLoading = false, statusMessage = "Error: ${t.message}") }
            }
        }
    }

    fun onFocusPointTapped(normalizedOffset: Offset) {
        val depthBmp = _uiState.value.depthBitmap ?: return
        val sourceBmp = _uiState.value.sourceBitmap ?: return

        viewModelScope.launch(Dispatchers.Default) {
            val px = (normalizedOffset.x * depthBmp.width).toInt().coerceIn(0, depthBmp.width - 1)
            val py = (normalizedOffset.y * depthBmp.height).toInt().coerceIn(0, depthBmp.height - 1)
            val pixel = depthBmp.getPixel(px, py)
            val sampledDepth = (pixel and 0xFF).toFloat()

            val updatedParams = _uiState.value.params.copy(focusDepth = sampledDepth)
            val rerendered = depthEngine.rerenderPreviewFromCache(sourceBmp, depthBmp, updatedParams)

            _uiState.update {
                it.copy(
                    params = updatedParams,
                    renderedBitmap = rerendered,
                    focusPoint = normalizedOffset
                )
            }
        }
    }

    fun updateParams(newParams: BlurPreviewParams) {
        val depthBmp = _uiState.value.depthBitmap ?: return
        val sourceBmp = _uiState.value.sourceBitmap ?: return

        viewModelScope.launch(Dispatchers.Default) {
            val rerendered = depthEngine.rerenderPreviewFromCache(sourceBmp, depthBmp, newParams)
            _uiState.update {
                it.copy(
                    params = newParams,
                    renderedBitmap = rerendered
                )
            }
        }
    }

    fun setBlurStrength(strength: Float) {
        updateParams(_uiState.value.params.copy(blurStrength = strength.coerceIn(0.01f, 1.0f)))
    }

    fun setLensEffect(effect: LensEffect) {
        updateParams(_uiState.value.params.copy(lensEffect = effect))
    }

    fun toggleDepthMapOverlay() {
        _uiState.update { it.copy(showDepthMapOverlay = !it.showDepthMapOverlay) }
    }

    fun exportPortrait() {
        val uri = _uiState.value.imageUri ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isExporting = true, statusMessage = "Exporting CineDepth Pro portrait...") }
            try {
                val result = depthEngine.exportBlurredImage(
                    context = getApplication(),
                    uri = uri,
                    params = _uiState.value.params,
                    maxDimension = 1920,
                    highQualityDepth = true
                )
                when (result) {
                    is com.cinedepth.pro.ui.blur.DepthBlurExportResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isExporting = false,
                                lastExportedUri = result.uri,
                                statusMessage = "Saved to Gallery"
                            )
                        }
                    }
                    is com.cinedepth.pro.ui.blur.DepthBlurExportResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isExporting = false,
                                statusMessage = "Export failed: ${result.message}"
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isExporting = false, statusMessage = "Export error: ${t.message}") }
            }
        }
    }
}
