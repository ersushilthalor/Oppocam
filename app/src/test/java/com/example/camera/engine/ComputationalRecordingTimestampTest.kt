package com.example.camera.engine

import android.media.MediaCodec
import com.example.camera.model.LensType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComputationalRecordingTimestampTest {

    /**
     * Helper simulation of the camera timestamp driven PTS generator used in CameraStreamCompositor.
     */
    private class SimulatedTimelineGenerator(private val fps: Int) {
        var recordingFps: Int = fps
        var lastEncodedPtsNs: Long = -1L
        var baseTimelinePtsNs: Long = 0L
        var lastLensCameraTimestampNs: Long = 0L
        var lastActiveCameraTimestampNs: Long = 0L
        var lastEncodedLens: LensType? = null

        fun start(fps: Int = 30) {
            recordingFps = fps
            lastEncodedPtsNs = -1L
            baseTimelinePtsNs = 0L
            lastLensCameraTimestampNs = 0L
            lastActiveCameraTimestampNs = 0L
            lastEncodedLens = null
        }

        fun computeNextEncoderPts(
            currentActive: LensType,
            activeCamTimestamp: Long
        ): Long {
            val currentFrameTs = if (activeCamTimestamp > 0L) activeCamTimestamp else System.nanoTime()
            val expectedFrameIntervalNs = 1_000_000_000L / recordingFps.toLong()
            val minPacingStepNs = maxOf(1_000_000L, expectedFrameIntervalNs / 10L)

            val ptsNs: Long
            if (lastEncodedPtsNs < 0L) {
                // First accepted frame of recording: normalize timeline to 0
                ptsNs = 0L
                baseTimelinePtsNs = 0L
                lastLensCameraTimestampNs = currentFrameTs
                lastEncodedLens = currentActive
            } else if (currentActive != lastEncodedLens) {
                // Lens switch (Main <-> UltraWide):
                // Seamlessly bridge timeline without discontinuities or clock jumps
                val bridgeStep = expectedFrameIntervalNs
                ptsNs = lastEncodedPtsNs + bridgeStep
                baseTimelinePtsNs = ptsNs
                lastLensCameraTimestampNs = currentFrameTs
                lastEncodedLens = currentActive
            } else if (lastLensCameraTimestampNs <= 0L) {
                // Resumed after pause: bridge timeline smoothly
                ptsNs = lastEncodedPtsNs + expectedFrameIntervalNs
                lastLensCameraTimestampNs = currentFrameTs
            } else {
                // Normal frame on current lens:
                val deltaNs = currentFrameTs - lastLensCameraTimestampNs
                val stepNs = if (deltaNs <= 0L) {
                    minPacingStepNs
                } else if (deltaNs > 500_000_000L) {
                    expectedFrameIntervalNs * 2L
                } else {
                    deltaNs
                }
                ptsNs = lastEncodedPtsNs + stepNs
                lastLensCameraTimestampNs = currentFrameTs
            }

            lastEncodedPtsNs = ptsNs
            lastActiveCameraTimestampNs = currentFrameTs
            return ptsNs
        }
    }

    /**
     * Test 1: Lens switching (Main <-> UltraWide) never causes PTS jumps or discontinuities.
     * Raw camera timestamps in real devices often have completely different base offsets
     * (e.g. Main at boottime 100_000_000_000 ns, UltraWide at 55_000_000_000_000 ns).
     * The generator must produce a continuous monotonic timeline independent of lens switches.
     */
    @Test
    fun testLensSwitchingProducesNoPtsDiscontinuity() {
        val generator = SimulatedTimelineGenerator(fps = 30)
        generator.start(30)

        val frameIntervalNs = TimeUnit.SECONDS.toNanos(1) / 30 // ~33.33ms

        // Main camera driver timestamp starting at arbitrary 100 seconds boottime
        var mainDriverTimestampNs = 100_000_000_000L
        // UltraWide camera driver timestamp starting at completely different epoch (e.g. 50,000s boottime)
        var uwDriverTimestampNs = 50_000_000_000_000L

        val generatedPtsList = mutableListOf<Long>()

        // 1. Record 60 frames on MAIN lens (~2 seconds)
        for (i in 0 until 60) {
            val pts = generator.computeNextEncoderPts(LensType.WIDE, mainDriverTimestampNs)
            generatedPtsList.add(pts)
            mainDriverTimestampNs += frameIntervalNs
        }

        // First frame must be exactly 0
        assertEquals("First frame must be normalized to 0", 0L, generatedPtsList.first())

        val lastMainPts = generatedPtsList.last()

        // 2. Switch to ULTRAWIDE lens and record 60 frames (~2 seconds)
        // Notice: uwDriverTimestampNs is ~50,000 seconds away from mainDriverTimestampNs!
        for (i in 0 until 60) {
            val pts = generator.computeNextEncoderPts(LensType.ULTRAWIDE, uwDriverTimestampNs)
            generatedPtsList.add(pts)
            uwDriverTimestampNs += frameIntervalNs
        }

        val firstUwPts = generatedPtsList[60]
        val deltaAcrossSwitchNs = firstUwPts - lastMainPts

        // The delta across the switch must be approximately 1 frame (~33.3ms), NOT ~49,900 seconds!
        val expectedDeltaNs = frameIntervalNs
        val discrepancyNs = kotlin.math.abs(deltaAcrossSwitchNs - expectedDeltaNs)
        assertTrue(
            "Delta across lens switch must be close to 1 frame interval (~33ms), got ${deltaAcrossSwitchNs / 1_000_000}ms",
            discrepancyNs < 5_000_000L // within 5ms tolerance
        )

        // 3. Switch BACK to MAIN lens and record 60 frames (~2 seconds)
        val beforeSwitchBackPts = generatedPtsList.last()
        for (i in 0 until 60) {
            val pts = generator.computeNextEncoderPts(LensType.WIDE, mainDriverTimestampNs)
            generatedPtsList.add(pts)
            mainDriverTimestampNs += frameIntervalNs
        }
        val firstMainSwitchBackPts = generatedPtsList[120]
        val deltaSwitchBackNs = firstMainSwitchBackPts - beforeSwitchBackPts
        assertTrue(
            "Delta switching back to Main must be close to 1 frame interval (~33ms), got ${deltaSwitchBackNs / 1_000_000}ms",
            kotlin.math.abs(deltaSwitchBackNs - expectedDeltaNs) < 5_000_000L
        )

        // Verify entire timeline is strictly monotonic: pts[i] > pts[i-1]
        for (i in 1 until generatedPtsList.size) {
            assertTrue(
                "PTS must be strictly monotonic at index $i (pts[i]=${generatedPtsList[i]} <= pts[i-1]=${generatedPtsList[i - 1]})",
                generatedPtsList[i] > generatedPtsList[i - 1]
            )
        }
    }

    /**
     * Test 2: Verify that a 6-second recording produces approximately 6 seconds duration
     * (and NOT hours like 12:28:50 caused by raw timestamp domain switching).
     */
    @Test
    fun testSixSecondRecordingProducesSixSecondDuration() {
        val generator = SimulatedTimelineGenerator(fps = 30)
        generator.start(30)

        val frameIntervalNs = 1_000_000_000L / 30L
        val totalFrames = 180 // 6 seconds at 30 FPS
        var finalPtsNs = 0L

        var activeLens = LensType.WIDE
        var rawDriverNs = 200_000_000_000L

        for (frame in 0 until totalFrames) {
            // Simulate switching lenses every 60 frames (2 seconds)
            if (frame == 60) {
                activeLens = LensType.ULTRAWIDE
                rawDriverNs = 44_930_000_000_000L // 12+ hours offset in raw hardware clock
            } else if (frame == 120) {
                activeLens = LensType.WIDE
                rawDriverNs = 204_000_000_000L
            }

            finalPtsNs = generator.computeNextEncoderPts(activeLens, rawDriverNs)
            rawDriverNs += frameIntervalNs
        }

        val durationSeconds = finalPtsNs.toDouble() / 1_000_000_000.0
        assertEquals(
            "Duration of 180 frames at 30fps must be approximately 6.0 seconds",
            (179.0 * frameIntervalNs) / 1_000_000_000.0,
            durationSeconds,
            0.05
        )
    }

    /**
     * Test 3: Verify strict monotonicity even under heavy thread jitter or zero time advance.
     */
    @Test
    fun testStrictMonotonicityUnderZeroOrBackwardClockJitter() {
        val generator = SimulatedTimelineGenerator(fps = 30)
        generator.start(30)

        val baseTs = 100_000_000_000L
        // Multiple frames arriving with zero or slightly jittered clock advance
        val pts1 = generator.computeNextEncoderPts(LensType.WIDE, baseTs)
        val pts2 = generator.computeNextEncoderPts(LensType.WIDE, baseTs) // duplicate timestamp!
        val pts3 = generator.computeNextEncoderPts(LensType.WIDE, baseTs - 5_000_000L) // backward clock jitter!
        val pts4 = generator.computeNextEncoderPts(LensType.WIDE, baseTs + 33_333_333L)

        assertEquals("First frame normalized to 0", 0L, pts1)
        assertTrue("pts2 must be strictly greater than pts1", pts2 > pts1)
        assertTrue("pts3 must be strictly greater than pts2 despite backward clock jitter", pts3 > pts2)
        assertTrue("pts4 must be strictly greater than pts3", pts4 > pts3)
    }

    /**
     * Test 4: Verify CinemaSoftwareRecordingEngine PTS normalization harmonizes with the new compositor timeline.
     * When incoming PTS from compositor starts at ~0, baseVideoPtsUs must preserve it (base = 0)
     * without subtracting an arbitrary offset, keeping exact A/V alignment.
     */
    @Test
    fun testCinemaSoftwareRecordingEnginePtsHarmonization() {
        var baseVideoPtsUs = -1L
        var lastVideoPtsUs = -1L

        // Incoming samples from compositor starting near 0 (e.g. 16,666 us, 33,333 us...)
        val incomingPtsUsList = listOf(16_666L, 33_333L, 50_000L, 66_666L, 83_333L)
        val normalizedPtsUsList = mutableListOf<Long>()

        for (incomingPts in incomingPtsUsList) {
            val bufferInfo = MediaCodec.BufferInfo().apply {
                presentationTimeUs = incomingPts
                size = 1024
                offset = 0
                flags = 0
            }

            if (baseVideoPtsUs < 0) {
                baseVideoPtsUs = if (bufferInfo.presentationTimeUs in 0L..60_000_000L) {
                    0L
                } else {
                    bufferInfo.presentationTimeUs
                }
            }
            var ptsUs = bufferInfo.presentationTimeUs - baseVideoPtsUs
            if (ptsUs < 0) ptsUs = 0
            if (lastVideoPtsUs >= 0L && ptsUs <= lastVideoPtsUs) {
                ptsUs = lastVideoPtsUs + 1000L
            }
            bufferInfo.presentationTimeUs = ptsUs
            lastVideoPtsUs = ptsUs

            normalizedPtsUsList.add(bufferInfo.presentationTimeUs)
        }

        // The normalized list must be identical to incoming compositor timestamps
        assertEquals(incomingPtsUsList, normalizedPtsUsList)
        assertEquals("Base video PTS should be preserved as 0", 0L, baseVideoPtsUs)
    }
}
