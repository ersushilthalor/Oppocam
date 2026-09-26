package com.example.camera.engine.hdrplus

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.util.Log
import com.example.camera.engine.FrameLuminanceStats
import com.example.camera.model.HardwareCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Master coordinator for the advanced 2-frame / 3-frame RAW HDR+ photography system.
 * Connects continuous background predictive exposure calculation, Camera2 RAW burst capture,
 * consistent color/white-balance developing, motion-aware alignment, and highlight-only merging.
 */
class HdrPlusEngine(private val context: Context) {

    companion object {
        private const val TAG = "HdrPlusEngine"
    }

    val predictor = HdrPlusPredictor()
    val developer = HdrPlusRawDeveloper()
    val aligner = HdrPlusAligner()
    val merger = HdrPlusMerger()

    /**
     * Checks if hardware can support the RAW HDR+ capture pipeline.
     */
    fun isHardwareCompatible(caps: HardwareCapabilities): Boolean {
        return caps.supportsRaw && caps.supportedRawResolutions.isNotEmpty()
    }

    /**
     * Updates predictive exposure calculations in the background on every viewfinder frame.
     */
    fun onViewfinderFrame(
        stats: FrameLuminanceStats?,
        lastResult: CaptureResult?,
        caps: HardwareCapabilities,
        frameCount: HdrPlusFrameCount,
        userIso: Int? = null,
        userExpNs: Long? = null,
        userAeComp: Int = 0
    ) {
        predictor.updatePrediction(
            stats = stats,
            lastResult = lastResult,
            caps = caps,
            frameCount = frameCount,
            userSelectedIso = userIso,
            userSelectedExposureTimeNs = userExpNs,
            userAeCompensation = userAeComp
        )
    }

    /**
     * Processes captured RAW frames into a final high-dynamic-range native-resolution JPEG.
     */
    suspend fun processHdrPlusRawCapture(
        rawFrames: List<HdrPlusRawFrame>,
        jpegQuality: Int = 98,
        orientationDegrees: Int = 0
    ): ByteArray = withContext(Dispatchers.Default) {
        if (rawFrames.isEmpty()) {
            throw IllegalArgumentException("No RAW frames provided to HDR+ engine")
        }

        val baseRaw = rawFrames.firstOrNull { it.role == HdrPlusRole.BASE_PRIMARY } ?: rawFrames.first()
        val secondaries = rawFrames.filter { it !== baseRaw }

        // Use base frame white-balance gains and CCM as the reference for all frames to guarantee color consistency
        val refGains = Triple(baseRaw.rGain, baseRaw.gGain, baseRaw.bGain)
        val refCcm = baseRaw.colorCorrectionMatrix

        // Estimate baseline exposure normalization from base RAW green channel so main-frame exposure
        // matches natural ISP brightness while preventing black crush or midtone clipping
        val baseExpGain = estimateBaselineExposureGain(baseRaw)

        // Parallelize RAW frame developing with consistent reference calibration
        val baseJob = async {
            developer.developRawToLinearRgb(
                frame = baseRaw,
                referenceGains = refGains,
                referenceCcm = refCcm,
                baseExposureGain = baseExpGain
            )
        }
        val secondaryJobs = secondaries.map { sec ->
            async {
                developer.developRawToLinearRgb(
                    frame = sec,
                    referenceGains = refGains,
                    referenceCcm = refCcm,
                    baseExposureGain = baseExpGain
                )
            }
        }

        val baseDeveloped = baseJob.await()
        val secondaryDeveloped = secondaryJobs.map { it.await() }

        // Downsampled luminance thumbnail for base frame alignment
        val baseThumb = developer.createLumaThumbnail(baseDeveloped)

        // Parallelize coarse + full-resolution subpixel alignment of secondary frames against Frame 1
        val alignedPairs = secondaryDeveloped.map { sec ->
            async {
                val secThumb = developer.createLumaThumbnail(sec)
                val coarseAlignment = aligner.calculateAlignment(
                    baseThumb = baseThumb,
                    secThumb = secThumb,
                    baseExpProduct = baseDeveloped.exposureProduct,
                    secExpProduct = sec.exposureProduct,
                    fullWidth = baseDeveloped.width,
                    fullHeight = baseDeveloped.height
                )
                val refinedAlignment = aligner.refineAlignmentFullRes(
                    baseImage = baseDeveloped,
                    secImage = sec,
                    coarse = coarseAlignment
                )
                Pair(sec, refinedAlignment)
            }
        }.map { it.await() }

        // Highlight-aware selective merging with subpixel sampling, motion detection, and tone mapping
        merger.mergeFrames(
            baseFrame = baseDeveloped,
            secondaryFrames = alignedPairs,
            jpegQuality = jpegQuality,
            orientationDegrees = orientationDegrees
        )
    }

    /**
     * Estimates an exposure normalization gain for the base RAW frame so that normal AE exposures
     * (which leave 0.5-1.2 EV of sensor headroom below whiteLevel) maintain natural midtone brightness
     * and shadow detail without crushing blacks or clipping unclipped midtones.
     */
    private fun estimateBaselineExposureGain(baseRaw: HdrPlusRawFrame): Float {
        val w = baseRaw.width
        val h = baseRaw.height
        val raw = baseRaw.rawData
        if (w < 8 || h < 8 || raw.isEmpty()) return 1.0f

        val wLevel = baseRaw.whiteLevel.toFloat().coerceAtLeast(64f)
        val bl = baseRaw.blackLevel.toFloat()
        val invRange = 1.0f / (wLevel - bl).coerceAtLeast(1.0f)

        val stepY = (h / 32).coerceAtLeast(1)
        val stepX = (w / 32).coerceAtLeast(1)
        var sumLuma = 0f
        var count = 0
        var highCount = 0

        for (y in 0 until h step stepY) {
            val rowOff = y * w
            for (x in 0 until w step stepX) {
                val idx = rowOff + x
                if (idx >= raw.size) continue
                val v = (((raw[idx].toInt() and 0xFFFF).toFloat() - bl) * invRange).coerceIn(0f, 1f)
                sumLuma += v
                if (v > 0.75f) highCount++
                count++
            }
        }

        if (count == 0) return 1.0f
        val avgLinear = sumLuma / count
        val highRatio = highCount.toFloat() / count

        // If the base frame has dark midtones and headroom before saturation, apply a gentle baseline lift
        return if (avgLinear in 0.015f..0.14f && highRatio < 0.15f) {
            (0.16f / avgLinear.coerceAtLeast(0.06f)).coerceIn(1.0f, 1.85f)
        } else {
            1.0f
        }
    }
}
