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
        jpegQuality: Int = 98
    ): ByteArray = withContext(Dispatchers.Default) {
        if (rawFrames.isEmpty()) {
            throw IllegalArgumentException("No RAW frames provided to HDR+ engine")
        }

        val baseRaw = rawFrames.firstOrNull { it.role == HdrPlusRole.BASE_PRIMARY } ?: rawFrames.first()
        val secondaries = rawFrames.filter { it != baseRaw }

        // Use base frame white-balance gains as the reference for all frames to guarantee color consistency
        val refGains = Triple(baseRaw.rGain, baseRaw.gGain, baseRaw.bGain)

        // Parallelize RAW frame developing
        val baseJob = async { developer.developRawToLinearRgb(baseRaw, refGains) }
        val secondaryJobs = secondaries.map { sec ->
            async { developer.developRawToLinearRgb(sec, refGains) }
        }

        val baseDeveloped = baseJob.await()
        val secondaryDeveloped = secondaryJobs.map { it.await() }

        // Rapid downsampled luminance thumbnail for base frame
        val baseThumb = developer.createLumaThumbnail(baseDeveloped)

        // Parallelize alignment of secondary frames against Frame 1
        val alignedPairs = secondaryDeveloped.map { sec ->
            val secThumb = developer.createLumaThumbnail(sec)
            val alignment = aligner.calculateAlignment(
                baseThumb = baseThumb,
                secThumb = secThumb,
                baseExpProduct = baseDeveloped.exposureProduct,
                secExpProduct = sec.exposureProduct,
                fullWidth = baseDeveloped.width,
                fullHeight = baseDeveloped.height
            )
            Pair(sec, alignment)
        }

        // Highlight-only selective merging with motion detection and tone mapping
        merger.mergeFrames(
            baseFrame = baseDeveloped,
            secondaryFrames = alignedPairs,
            jpegQuality = jpegQuality
        )
    }
}
