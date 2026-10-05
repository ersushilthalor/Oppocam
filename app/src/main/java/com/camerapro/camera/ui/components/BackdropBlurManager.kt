package com.camerapro.camera.ui.components

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.camerapro.camera.model.FloatingWindowAppearanceConfig
import java.lang.ref.WeakReference

private const val TAG = "BackdropBlurManager"

/**
 * CompositionLocal providing the current FloatingWindowAppearanceConfig across all UI components.
 */
val LocalFloatingWindowAppearance = compositionLocalOf { FloatingWindowAppearanceConfig() }

/**
 * High-performance, zero-latency GPU Backdrop Blur Manager.
 *
 * Architecture:
 * Camera → Main Preview (TextureView) → GPU Blur (RenderNode + RenderEffect) → Floating UI Overlay (FrostedGlassBox)
 *
 * Key Properties:
 * 1. Zero Bitmap Allocation: No TextureView.getBitmap(), zero CPU StackBlur, zero 66ms delay.
 * 2. True Hardware Synchronization: Uses the exact live preview frame/source as the main viewfinder.
 * 3. 100% GPU Execution: Renders via hardware-accelerated Skia GPU pipeline on RenderThread.
 * 4. Zero Latency & Ghosting: Updates in the exact same render pass as the camera viewfinder.
 * 5. Controls Preserved: Dynamically updates with blur strength, transparency, Liquid Glass, and Frosted Glass controls.
 */
object BackdropBlurManager {

    const val SAMPLE_WIDTH = 360
    const val SAMPLE_HEIGHT = 640

    // Frame synchronization counter to notify Composables of new camera preview frames
    val frameTickState = mutableLongStateOf(0L)

    // Backward-compatible empty state
    val blurredBackdropState = mutableStateOf<Bitmap?>(null)

    // Exact bounds of the TextureView in root coordinates
    @Volatile
    var viewfinderBoundsInRoot: androidx.compose.ui.geometry.Rect? = null
    @Volatile
    var rootWindowSize: androidx.compose.ui.unit.IntSize? = null

    // Weak reference to the main viewfinder TextureView
    private var previewViewRef: WeakReference<TextureView>? = null

    // Hardware RenderNode for GPU blur
    private var renderNode: RenderNode? = null
    private var currentBlurStrength = 24.0f

    /**
     * Flag indicating whether any floating window, popup, or settings panel is open.
     * When false, viewfinder sampling and redraw ticks are completely bypassed.
     */
    var isWindowActive: Boolean = false

    fun updateViewfinderGeometry(
        boundsInRoot: androidx.compose.ui.geometry.Rect,
        rootSize: androidx.compose.ui.unit.IntSize
    ) {
        viewfinderBoundsInRoot = boundsInRoot
        rootWindowSize = rootSize
    }

    /**
     * Registers the active viewfinder TextureView.
     */
    fun registerViewfinder(textureView: TextureView) {
        previewViewRef = WeakReference(textureView)
    }

    /**
     * Unregisters the viewfinder TextureView when destroyed.
     */
    fun unregisterViewfinder(textureView: TextureView) {
        if (previewViewRef?.get() === textureView) {
            previewViewRef = null
        }
    }

    /**
     * Called whenever a new live frame is rendered onto the main viewfinder.
     * Triggers immediate synchronization with open floating windows.
     */
    fun onViewfinderFrameAvailable() {
        if (!isWindowActive) return
        frameTickState.longValue++
    }

    /**
     * Compatibility bridge for Viewfinder calls.
     */
    fun onViewfinderFrame(textureView: TextureView, blurStrength: Float) {
        registerViewfinder(textureView)
        currentBlurStrength = blurStrength
        onViewfinderFrameAvailable()
    }

    /**
     * Dynamically updates blur strength from slider adjustments.
     */
    fun onBlurStrengthChanged(newStrength: Float) {
        currentBlurStrength = newStrength.coerceIn(0f, 50f)
        frameTickState.longValue++
    }

    /**
     * Real-time GPU Blur rendering onto the floating window's hardware canvas.
     *
     * Directly renders the live TextureView into a hardware RenderNode, applies
     * hardware-accelerated RenderEffect.createBlurEffect on the GPU, and translates
     * the coordinate space so the blurred region perfectly aligns with what is
     * physically beneath the floating window.
     */
    fun drawGpuBlur(
        canvas: android.graphics.Canvas,
        windowBoundsInRoot: androidx.compose.ui.geometry.Rect?,
        width: Float,
        height: Float,
        blurStrength: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        if (width <= 0f || height <= 0f) {
            return false
        }
        val targetView = previewViewRef?.get() ?: return false
        if (!targetView.isAvailable || targetView.width <= 0 || targetView.height <= 0) {
            return false
        }

        return try {
            val node = renderNode ?: RenderNode("GpuViewfinderBackdropBlur").also { renderNode = it }
            val viewW = targetView.width
            val viewH = targetView.height
            node.setPosition(0, 0, viewW, viewH)

            val recordingCanvas = node.beginRecording(viewW, viewH)
            try {
                targetView.draw(recordingCanvas)
            } finally {
                node.endRecording()
            }

            // Map user blur strength (0..50) into GPU Gaussian blur radius safely
            val safeStrength = if (blurStrength.isNaN()) 0.1f else blurStrength
            val clampedStrength = safeStrength.coerceIn(minOf(0.1f, 50.0f), maxOf(0.1f, 50.0f))
            val rawRadius = clampedStrength * 2.2f
            val blurRadius = rawRadius.coerceIn(minOf(1.0f, 160.0f), maxOf(1.0f, 160.0f))
            val blurEffect = RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
            node.setRenderEffect(blurEffect)

            val vfBounds = viewfinderBoundsInRoot
            if (vfBounds != null && windowBoundsInRoot != null) {
                val vfLeft = minOf(vfBounds.left, vfBounds.right)
                val vfRight = maxOf(vfBounds.left, vfBounds.right)
                val vfTop = minOf(vfBounds.top, vfBounds.bottom)
                val vfBottom = maxOf(vfBounds.top, vfBounds.bottom)

                val winLeft = minOf(windowBoundsInRoot.left, windowBoundsInRoot.right)
                val winRight = maxOf(windowBoundsInRoot.left, windowBoundsInRoot.right)
                val winTop = minOf(windowBoundsInRoot.top, windowBoundsInRoot.bottom)
                val winBottom = maxOf(windowBoundsInRoot.top, windowBoundsInRoot.bottom)

                // If completely disjoint (e.g. scrolled offscreen above or below the viewfinder), skip viewfinder draw
                if (winRight <= vfLeft || winLeft >= vfRight || winBottom <= vfTop || winTop >= vfBottom) {
                    return false
                }
            }

            val relX = if (windowBoundsInRoot != null && vfBounds != null) {
                windowBoundsInRoot.left - vfBounds.left
            } else {
                0f
            }
            val relY = if (windowBoundsInRoot != null && vfBounds != null) {
                windowBoundsInRoot.top - vfBounds.top
            } else {
                0f
            }

            canvas.save()
            // Translate so the portion of the viewfinder directly behind this window is rendered
            canvas.translate(-relX, -relY)
            canvas.drawRenderNode(node)
            canvas.restore()
            true
        } catch (e: Throwable) {
            Log.d(TAG, "Hardware RenderNode blur draw skipped: ${e.message}")
            false
        }
    }
}

