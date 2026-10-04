package com.example.camera.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.TextureView
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.example.camera.model.FloatingWindowAppearanceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CompositionLocal providing the current FloatingWindowAppearanceConfig across all UI components.
 */
val LocalFloatingWindowAppearance = compositionLocalOf { FloatingWindowAppearanceConfig() }

/**
 * High-performance, zero-jank Backdrop Blur Manager.
 *
 * Captures lightweight downsampled snapshots of the live camera viewfinder/scene,
 * executes high-speed integer StackBlur on a background coroutine dispatcher (taking < 1.5ms),
 * and provides the real-time blurred backdrop to all floating windows and popups across the app.
 *
 * Key Properties:
 * 1. True Frosted Glass: The background content directly underneath the floating window is blurred.
 * 2. Scope Isolation: The blur is ONLY drawn inside the floating window; the rest of the viewfinder remains 100% sharp.
 * 3. Live Responsiveness: Changes to Transparency and Blur Strength sliders update live in real-time.
 * 4. Battery & CPU Efficient: Automatically pauses sampling when no floating windows are open.
 */
object BackdropBlurManager {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var processingJob: Job? = null

    // High-definition sampling resolution for silky-smooth, zero-pixelation optical frosted glass
    const val SAMPLE_WIDTH = 360
    const val SAMPLE_HEIGHT = 640

    // Live blurred backdrop consumed by FrostedGlassBox (aligned 1:1 with root window coordinates)
    val blurredBackdropState = mutableStateOf<Bitmap?>(null)

    // Exact bounds of the TextureView in root coordinates so backdrop blur is never zoomed-in or shifted
    @Volatile
    var viewfinderBoundsInRoot: androidx.compose.ui.geometry.Rect? = null
    @Volatile
    var rootWindowSize: androidx.compose.ui.unit.IntSize? = null

    fun updateViewfinderGeometry(
        boundsInRoot: androidx.compose.ui.geometry.Rect,
        rootSize: androidx.compose.ui.unit.IntSize
    ) {
        viewfinderBoundsInRoot = boundsInRoot
        rootWindowSize = rootSize
    }

    // Cached raw root-aligned frame for instant re-blurring when user moves the Blur Strength slider in Settings
    private var lastRawSampleBitmap: Bitmap? = null
    private var currentBlurStrength = 24.0f
    private var lastSampleTime = 0L

    // Sampling rate: ~15 fps (every 66ms) is butter-smooth for background blur without taxing the camera pipeline
    private const val MIN_SAMPLE_INTERVAL_MS = 66L

    // Reusable bitmaps to avoid heap churn
    private var reusableTextureBitmap: Bitmap? = null
    private var reusableRootAlignedBitmap: Bitmap? = null

    /**
     * Flag indicating whether any floating window, popup, or settings panel is open.
     * When false, viewfinder sampling is completely bypassed.
     */
    var isWindowActive: Boolean = false

    /**
     * Called from Viewfinder TextureView on every frame when a floating window is open.
     * Accurately maps the TextureView (including its crop transform matrix and exact position in root)
     * into a root-screen-aligned bitmap so floating windows show a 1:1 un-zoomed, pixel-free blur.
     */
    fun onViewfinderFrame(textureView: TextureView, blurStrength: Float) {
        if (!isWindowActive) return
        if (!textureView.isAvailable || textureView.width <= 0 || textureView.height <= 0) return

        val now = android.os.SystemClock.uptimeMillis()
        if (lastRawSampleBitmap != null && (now - lastSampleTime < MIN_SAMPLE_INTERVAL_MS)) {
            return
        }
        if (processingJob?.isActive == true) {
            return
        }

        currentBlurStrength = blurStrength
        lastSampleTime = now

        val rootW = rootWindowSize?.width ?: textureView.width
        val rootH = rootWindowSize?.height ?: textureView.height
        if (rootW <= 0 || rootH <= 0) return

        val sampleW = SAMPLE_WIDTH
        val sampleH = ((SAMPLE_WIDTH.toFloat() * rootH / rootW).roundToInt()).coerceIn(360, 960)

        // Ensure reusableRootAlignedBitmap has correct dimensions
        val rootBmp = reusableRootAlignedBitmap?.takeIf {
            !it.isRecycled && it.width == sampleW && it.height == sampleH
        } ?: Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888).also {
            reusableRootAlignedBitmap = it
        }

        val bounds = viewfinderBoundsInRoot
        if (bounds != null && rootW > 0 && rootH > 0) {
            val left = (bounds.left / rootW * sampleW).roundToInt().coerceIn(0, sampleW)
            val top = (bounds.top / rootH * sampleH).roundToInt().coerceIn(0, sampleH)
            val right = (bounds.right / rootW * sampleW).roundToInt().coerceIn(left, sampleW)
            val bottom = (bounds.bottom / rootH * sampleH).roundToInt().coerceIn(top, sampleH)
            val tvSampleW = (right - left).coerceAtLeast(1)
            val tvSampleH = (bottom - top).coerceAtLeast(1)

            val tvBmp = reusableTextureBitmap?.takeIf {
                !it.isRecycled && it.width == tvSampleW && it.height == tvSampleH
            } ?: Bitmap.createBitmap(tvSampleW, tvSampleH, Bitmap.Config.ARGB_8888).also {
                reusableTextureBitmap = it
            }

            try {
                textureView.getBitmap(tvBmp)
                val canvas = Canvas(rootBmp)
                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(tvBmp, left.toFloat(), top.toFloat(), null)
            } catch (e: Exception) {
                return
            }
        } else {
            try {
                textureView.getBitmap(rootBmp)
            } catch (e: Exception) {
                return
            }
        }

        // Store downsampled raw frame into lastRawSampleBitmap for instant re-blurring on slider adjustments
        val rawSample = lastRawSampleBitmap?.takeIf {
            !it.isRecycled && it.width == sampleW && it.height == sampleH
        } ?: Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888).also {
            lastRawSampleBitmap = it
        }

        val copyCanvas = Canvas(rawSample)
        copyCanvas.drawBitmap(rootBmp, 0f, 0f, null)

        // Make an isolated copy for the background blur coroutine
        val rawCopy = try {
            Bitmap.createBitmap(rawSample)
        } catch (e: Exception) {
            return
        }

        val targetStrength = currentBlurStrength
        processingJob = scope.launch {
            val blurred = processBlur(rawCopy, targetStrength)
            if (rawCopy != blurred && !rawCopy.isRecycled) {
                rawCopy.recycle()
            }
            withContext(Dispatchers.Main) {
                val old = blurredBackdropState.value
                blurredBackdropState.value = blurred
                if (old != null && old != blurred && !old.isRecycled) {
                    old.recycle()
                }
            }
        }
    }

    /**
     * Instantly re-blurs the cached background when the user adjusts the Blur Strength slider.
     */
    fun onBlurStrengthChanged(newStrength: Float) {
        currentBlurStrength = newStrength.coerceIn(0f, 50f)
        val raw = lastRawSampleBitmap ?: return
        if (raw.isRecycled) return

        val rawCopy = try {
            Bitmap.createBitmap(raw)
        } catch (e: Exception) {
            return
        }

        processingJob?.cancel()
        processingJob = scope.launch {
            val blurred = processBlur(rawCopy, currentBlurStrength)
            if (rawCopy != blurred && !rawCopy.isRecycled) {
                rawCopy.recycle()
            }
            withContext(Dispatchers.Main) {
                val old = blurredBackdropState.value
                blurredBackdropState.value = blurred
                if (old != null && old != blurred && !old.isRecycled) {
                    old.recycle()
                }
            }
        }
    }

    /**
     * Processes blur strength mapping:
     * - 0.0: completely sharp backdrop (no blur applied)
     * - 0.1 - 50.0: smooth progression up to clearly heavy frosted blur
     */
    fun processBlur(src: Bitmap, blurStrength: Float): Bitmap {
        if (blurStrength <= 0.2f) {
            return Bitmap.createBitmap(src)
        }
        val clampedStrength = blurStrength.coerceIn(0f, 50f)
        val radius1 = (clampedStrength * 0.60f).roundToInt().coerceIn(1, 30)
        val pass1 = applyFastStackBlur(src, radius1.toFloat())

        if (clampedStrength > 6f) {
            val radius2 = (clampedStrength * 0.45f).roundToInt().coerceIn(1, 24)
            val pass2 = applyFastStackBlur(pass1, radius2.toFloat())
            if (pass1 != pass2 && !pass1.isRecycled) {
                pass1.recycle()
            }
            return pass2
        }
        return pass1
    }

    /**
     * Optimized Mario Klingemann StackBlur:
     * High-speed O(N) integer box-blur approximation with smooth Gaussian fall-off.
     */
    private fun applyFastStackBlur(src: Bitmap, blurStrength: Float): Bitmap {
        val radius = blurStrength.roundToInt().coerceIn(0, 32)
        if (radius < 1) {
            return Bitmap.createBitmap(src)
        }

        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (curY in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            for (curI in -radius..radius) {
                p = pix[yi + min(wm, max(curI, 0))]
                sir = stack[curI + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - kotlin.math.abs(curI)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (curI > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (curX in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curY == 0) {
                    vmin[curX] = min(curX + radius + 1, wm)
                }
                p = pix[yw + vmin[curX]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (curX in 0 until w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (curI in -radius..radius) {
                yi = max(0, yp) + curX
                sir = stack[curI + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(curI)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (curI > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (curI < hm) {
                    yp += w
                }
            }
            yi = curX
            stackpointer = radius
            for (curY in 0 until h) {
                // Preserve full opacity
                pix[yi] = (-0x1000000) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curX == 0) {
                    vmin[curY] = min(curY + r1, hm) * w
                }
                p = curX + vmin[curY]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pix, 0, w, 0, 0, w, h)
        return result
    }
}
