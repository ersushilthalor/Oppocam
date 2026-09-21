package com.example.camera.engine.humanvision

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Depth classifications for Human Vision computational photography.
 *
 * Near: Foreground elements (ground, flowers, immediate path, nearby subjects).
 * Mid: Main subject matter and mid-distance architecture / trees.
 * Far: Distant scenery, buildings, hills, skyline landmarks.
 * Very Far: Distant horizon, mountains, sky, infinity background.
 */
enum class SceneDepthZone(val label: String, val minDepth: Float, val maxDepth: Float) {
    NEAR("Near (Foreground)", 0.0f, 0.25f),
    MID("Mid (Main Scene)", 0.25f, 0.55f),
    FAR("Far (Distant Scenery)", 0.55f, 0.80f),
    VERY_FAR("Very Far (Horizon/Sky)", 0.80f, 1.0f);

    companion object {
        fun fromDepth(depth: Float): SceneDepthZone {
            val d = depth.coerceIn(0.0f, 1.0f)
            return when {
                d < 0.25f -> NEAR
                d < 0.55f -> MID
                d < 0.80f -> FAR
                else -> VERY_FAR
            }
        }
    }
}

/**
 * Human Vision processing configuration parameters.
 */
data class HumanVisionConfig(
    val perspectiveAcuity: Float = 0.22f, // Subtle human foveal perspective enlargement (0.10 to 0.35)
    val distantDetailBoost: Float = 0.80f, // Sharpness & micro-contrast restoration for distant tiles
    val movingObjectProtection: Boolean = true, // Strict single-source motion locking to prevent ghosting
    val hdrDenoiseBurst: Boolean = true, // Temporal multi-frame denoise & dynamic range fusion on 1x layer
    val tileOverlapRatio: Float = 0.35f, // Overlap ratio between adjacent 3x zoom tiles (30% - 40%)
    val maxDistantTiles: Int = 3 // Number of 3x distant tiles to capture (1 to 3)
)

/**
 * Real-time progress and status reporting during capture and processing.
 */
data class HumanVisionProgress(
    val isCapturing: Boolean = false,
    val isProcessing: Boolean = false,
    val progress: Float = 0.0f, // 0.0 to 1.0
    val statusText: String = "",
    val currentZone: SceneDepthZone? = null
)

/**
 * Metadata and pixel data for an overlapping 3x zoom tile.
 */
data class TileRegion(
    val index: Int,
    val rectNorm: RectF, // Normalized bounds [left, top, right, bottom] within 1x frame (0.0 to 1.0)
    val bitmap: Bitmap,
    val confidence: Float = 1.0f, // Confidence score based on feature density and alignment
    val offsetX: Float = 0.0f, // Sub-pixel translation offset in 1x space
    val offsetY: Float = 0.0f
)

/**
 * Complete set of multi-camera and burst frames captured for Human Vision fusion.
 */
data class CapturedFrameSet(
    val ultraWideReference: Bitmap?, // 0.5x Master wide reference (foreground & full composition)
    val mainFrames: List<Bitmap>, // 1x 2-3 Rapid consecutive burst frames (AE/AWB locked)
    val zoomTiles: List<TileRegion> // 3x Multi-tile mosaic covering distant regions
)
