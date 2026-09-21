package com.example.camera.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.camera.engine.night.*
import com.example.camera.model.NightConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.pow

/**
 * Computational Multi-Frame Night Processing Engine.
 *
 * Sits on top of the flagship [UltraNightFusionEngine] and [NightAlignmentEngine]
 * to deliver ultra-bright, crystal-clear, zero-ghosting night photographs.
 */
class NightFusionProcessor {

    private val alignmentEngine = NightAlignmentEngine()
    private val ultraEngine = UltraNightFusionEngine()

    suspend fun processNightFrames(
        frames: List<Bitmap>,
        noiseSuppression: Float = 0.85f,
        shadowLift: Float = 1.35f,
        isAntiGhostingEnabled: Boolean = true,
        onProgress: (Float) -> Unit = {}
    ): Bitmap = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) {
            throw IllegalArgumentException("Night fusion requires at least 1 frame")
        }
        if (frames.size == 1) {
            onProgress(0.5f)
            val result = enhanceSingleNightFrame(frames[0], shadowLift)
            onProgress(1.0f)
            return@withContext result
        }

        val config = NightConfig(
            antiGhostingEnabled = isAntiGhostingEnabled,
            noiseSuppression = noiseSuppression,
            shadowLift = shadowLift
        )

        val capturedFrames = frames.mapIndexed { idx, bmp ->
            CapturedNightFrame(
                index = idx,
                bitmap = bmp,
                exposureTimeNs = 33_333_333L,
                iso = 400,
                timestampNanos = System.nanoTime() + (idx * 60_000_000L),
                type = when {
                    idx == 0 || idx == frames.size - 1 -> BracketExposureType.SHORT
                    idx == 1 || idx == frames.size - 2 -> BracketExposureType.MEDIUM
                    else -> BracketExposureType.LONG
                }
            )
        }

        val refIdx = alignmentEngine.selectOptimalReferenceFrame(capturedFrames)
        onProgress(0.15f)

        val aligned = alignmentEngine.alignFrames(capturedFrames, refIdx) { p ->
            onProgress(0.15f + p * 0.35f)
        }

        onProgress(0.50f)

        return@withContext ultraEngine.processUltraNightFrames(
            frames = capturedFrames,
            alignedData = aligned,
            config = config,
            onProgress = { p ->
                onProgress(0.50f + p * 0.50f)
            }
        )
    }

    private fun enhanceSingleNightFrame(frame: Bitmap, shadowLift: Float): Bitmap {
        val width = frame.width
        val height = frame.height
        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)

        val lift = shadowLift.coerceIn(1.0f, 2.5f)
        val mu = 16.0f * lift

        for (idx in 0 until width * height) {
            val p = pixels[idx]
            val r = Color.red(p) / 255f
            val g = Color.green(p) / 255f
            val b = Color.blue(p) / 255f
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceAtLeast(0.0001f)

            // Logarithmic tone curve with true black anchoring (at 0 -> 0)
            val toneLuma = (ln(1.0f + mu * luma) / ln(1.0f + mu)).coerceIn(0.0f, 1.5f)
            val gain = (toneLuma / luma).pow(0.85f)

            val nr = ((r * gain).pow(1f / 1.1f) * 255f).toInt().coerceIn(0, 255)
            val ng = ((g * gain).pow(1f / 1.1f) * 255f).toInt().coerceIn(0, 255)
            val nb = ((b * gain).pow(1f / 1.1f) * 255f).toInt().coerceIn(0, 255)
            pixels[idx] = Color.rgb(nr, ng, nb)
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }
}
