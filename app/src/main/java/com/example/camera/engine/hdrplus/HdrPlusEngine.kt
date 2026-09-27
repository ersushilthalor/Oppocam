package com.example.camera.engine.hdrplus

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.util.Log
import com.example.camera.engine.FrameLuminanceStats
import com.example.camera.model.HardwareCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Master coordinator for the 3-Frame Exposure Fusion RAW photography system.
 * Connects continuous background predictive exposure calculation, Camera2 RAW bracket capture
 * (Underexposed, Normal, Overexposed), color-consistent RAW development, motion-aware alignment,
 * and multi-scale Exposure Fusion blending.
 */
class HdrPlusEngine(private val context: Context) {

    companion object {
        private const val TAG = "HdrPlusEngine"
    }

    val predictor = HdrPlusPredictor()
    val developer = HdrPlusRawDeveloper()
    val aligner = HdrPlusAligner()
    val merger = HdrPlusMerger(context)

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
        frameCount: HdrPlusFrameCount = HdrPlusFrameCount.THREE_FRAMES,
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
     * Processes captured RAW frames into a final high-dynamic-range native-resolution JPEG
     * using the 3-Frame Exposure Fusion algorithm.
     */
    suspend fun processHdrPlusRawCapture(
        rawFrames: List<HdrPlusRawFrame>,
        jpegQuality: Int = 98,
        orientationDegrees: Int = 0
    ): ByteArray = withContext(Dispatchers.Default) {
        if (rawFrames.isEmpty()) {
            throw IllegalArgumentException("No RAW frames provided to HDR+ engine")
        }

        // 1. Identify the 3 bracket roles: Normal, Underexposed, Overexposed
        val normalRaw = rawFrames.firstOrNull { it.role == HdrPlusRole.NORMAL_EXPOSURE }
            ?: rawFrames.first()

        val underRaw = rawFrames.firstOrNull { it.role == HdrPlusRole.UNDER_EXPOSED }
            ?: rawFrames.firstOrNull { it !== normalRaw && it.evDelta < 0f }
            ?: rawFrames.minByOrNull { it.exposureProduct }
            ?: normalRaw

        val overRaw = rawFrames.firstOrNull { it.role == HdrPlusRole.OVER_EXPOSED }
            ?: rawFrames.firstOrNull { it !== normalRaw && it !== underRaw }
            ?: rawFrames.maxByOrNull { it.exposureProduct }
            ?: normalRaw

        // 2. Use Normal reference frame white-balance gains and CCM to guarantee color consistency
        val refGains = Triple(normalRaw.rGain, normalRaw.gGain, normalRaw.bGain)
        val refCcm = normalRaw.colorCorrectionMatrix

        // Estimate baseline exposure gain from normal RAW green channel
        val baseExpGain = estimateBaselineExposureGain(normalRaw)

        // 3. Develop all 3 RAW frames to Linear RGB with consistent reference calibration
        val normalDeveloped = developer.developRawToLinearRgb(
            frame = normalRaw,
            referenceGains = refGains,
            referenceCcm = refCcm,
            baseExposureGain = baseExpGain
        )

        val underDeveloped = if (underRaw === normalRaw) {
            normalDeveloped
        } else {
            developer.developRawToLinearRgb(
                frame = underRaw,
                referenceGains = refGains,
                referenceCcm = refCcm,
                baseExposureGain = baseExpGain
            )
        }

        val overDeveloped = if (overRaw === normalRaw) {
            normalDeveloped
        } else {
            developer.developRawToLinearRgb(
                frame = overRaw,
                referenceGains = refGains,
                referenceCcm = refCcm,
                baseExposureGain = baseExpGain
            )
        }

        // 4. Downsampled luminance thumbnail for normal frame alignment
        val normalThumb = developer.createLumaThumbnail(normalDeveloped)

        // 5. Parallel alignment of Underexposed and Overexposed frames against Normal reference
        val underAlignDeferred = async {
            if (underRaw === normalRaw) {
                HdrPlusAlignmentResult()
            } else {
                val thumb = developer.createLumaThumbnail(underDeveloped)
                val coarse = aligner.calculateAlignment(
                    baseThumb = normalThumb,
                    secThumb = thumb,
                    baseExpProduct = normalDeveloped.exposureProduct,
                    secExpProduct = underDeveloped.exposureProduct,
                    fullWidth = normalDeveloped.width,
                    fullHeight = normalDeveloped.height
                )
                aligner.refineAlignmentFullRes(
                    baseImage = normalDeveloped,
                    secImage = underDeveloped,
                    coarse = coarse
                )
            }
        }

        val overAlignDeferred = async {
            if (overRaw === normalRaw) {
                HdrPlusAlignmentResult()
            } else {
                val thumb = developer.createLumaThumbnail(overDeveloped)
                val coarse = aligner.calculateAlignment(
                    baseThumb = normalThumb,
                    secThumb = thumb,
                    baseExpProduct = normalDeveloped.exposureProduct,
                    secExpProduct = overDeveloped.exposureProduct,
                    fullWidth = normalDeveloped.width,
                    fullHeight = normalDeveloped.height
                )
                aligner.refineAlignmentFullRes(
                    baseImage = normalDeveloped,
                    secImage = overDeveloped,
                    coarse = coarse
                )
            }
        }

        val underAlignment = underAlignDeferred.await()
        val overAlignment = overAlignDeferred.await()

        // 6. Run 3-Frame Exposure Fusion merging (Mertens et al.)
        merger.merge3FramesExposureFusion(
            underFrame = underDeveloped,
            normalFrame = normalDeveloped,
            overFrame = overDeveloped,
            underAlignment = underAlignment,
            overAlignment = overAlignment,
            jpegQuality = jpegQuality,
            orientationDegrees = orientationDegrees
        )
    }

    /**
     * Estimates an exposure normalization gain for the normal RAW frame so that normal AE exposures
     * maintain natural midtone brightness and shadow detail without crushing blacks.
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

        return if (avgLinear in 0.015f..0.14f && highRatio < 0.15f) {
            (0.16f / avgLinear.coerceAtLeast(0.06f)).coerceIn(1.0f, 1.85f)
        } else {
            1.0f
        }
    }
}
