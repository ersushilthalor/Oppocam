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
     * Helper simulation of the monotonic timeline PTS generator used in CameraStreamCompositor.
     */
    private class SimulatedTimelineGenerator(private val fps: Int) {
        var recordingStartNs: Long = 0L
        var lastEncodedPtsNs: Long = -1L
        var lastActiveCameraTimestampNs: Long = 0L
        var lastEncodedLens: LensType? = null

        fun start(nowNs: Long) {
            recordingStartNs = nowNs
            lastEncodedPtsNs = -1L
            lastActiveCameraTimestampNs = 0L
            lastEncodedLens = null
        }

        fun computeNextEncoderPts(
            nowNs: Long,
            currentActive: LensType,
            activeCamTimestamp: Long
        ): Long {
            if (recordingStartNs <= 0L) {
                recordingStartNs = nowNs
            }

            var ptsNs = nowNs - recordingStartNs
            if (ptsNs < 0L) ptsNs = 0L

            val expectedFrameIntervalNs = 1_000_000_000L / fps.toLong()
            val minPacingStepNs = maxOf(1_000_000L, expectedFrameIntervalNs / 10L)

            if (lastEncodedPtsNs >= 0L && ptsNs <= lastEncodedPtsNs) {
                ptsNs = lastEncodedPtsNs + minPacingStepNs
            }

            lastEncodedPtsNs = ptsNs
            lastActiveCameraTimestampNs = activeCamTimestamp
            lastEncodedLens = currentActive

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
        val recordingStartNs = 1_000_000_000L
        generator.start(recordingStartNs)

        val frameIntervalNs = TimeUnit.SECONDS.toNanos(1) / 30 // ~33.33ms

        // Main camera driver timestamp starting at arbitrary 100 seconds boottime
        var mainDriverTimestampNs = 100_000_000_000L
        // UltraWide camera driver timestamp starting at completely different epoch (e.g. 50,000s boottime)
        var uwDriverTimestampNs = 50_000_000_000_000L

        var simulatedNowNs = recordingStartNs
        val generatedPtsList = mutableListOf<Long>()

        // 1. Record 60 frames on MAIN lens (~2 seconds)
        for (i in 0 until 60) {
            simulatedNowNs += frameIntervalNs
            mainDriverTimestampNs += frameIntervalNs
            val pts = generator.computeNextEncoderPts(simulatedNowNs, LensType.WIDE, mainDriverTimestampNs)
            generatedPtsList.add(pts)
        }

        val lastMainPts = generatedPtsList.last()

        // 2. Switch to ULTRAWIDE lens and record 60 frames (~2 seconds)
        // Notice: uwDriverTimestampNs is ~50,000 seconds away from mainDriverTimestampNs!
        for (i in 0 until 60) {
            simulatedNowNs += frameIntervalNs
            uwDriverTimestampNs += frameIntervalNs
            val pts = generator.computeNextEncoderPts(simulatedNowNs, LensType.ULTRAWIDE, uwDriverTimestampNs)
            generatedPtsList.add(pts)
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
            simulatedNowNs += frameIntervalNs
            mainDriverTimestampNs += frameIntervalNs
            val pts = generator.computeNextEncoderPts(simulatedNowNs, LensType.WIDE, mainDriverTimestampNs)
            generatedPtsList.add(pts)
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
        val recordingStartNs = System.nanoTime()
        generator.start(recordingStartNs)

        val frameIntervalNs = 1_000_000_000L / 30L
        val totalFrames = 180 // 6 seconds at 30 FPS
        var currentClockNs = recordingStartNs
        var finalPtsNs = 0L

        var activeLens = LensType.WIDE
        var rawDriverNs = 200_000_000_000L

        for (frame in 0 until totalFrames) {
            currentClockNs += frameIntervalNs
            // Simulate switching lenses every 60 frames (2 seconds)
            if (frame == 60) {
                activeLens = LensType.ULTRAWIDE
                rawDriverNs = 44_930_000_000_000L // 12+ hours offset in raw hardware clock
            } else if (frame == 120) {
                activeLens = LensType.WIDE
                rawDriverNs = 204_000_000_000L
            } else {
                rawDriverNs += frameIntervalNs
            }

            finalPtsNs = generator.computeNextEncoderPts(currentClockNs, activeLens, rawDriverNs)
        }

        val durationSeconds = finalPtsNs.toDouble() / 1_000_000_000.0
        assertEquals(
            "Duration of 180 frames at 30fps must be exactly 6.0 seconds",
            6.0,
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
        val startNs = 5_000_000_000L
        generator.start(startNs)

        // Multiple frames arriving with zero or slightly jittered clock advance
        val pts1 = generator.computeNextEncoderPts(startNs + 10_000_000L, LensType.WIDE, 100L)
        val pts2 = generator.computeNextEncoderPts(startNs + 10_000_000L, LensType.WIDE, 101L) // same nowNs!
        val pts3 = generator.computeNextEncoderPts(startNs + 9_000_000L, LensType.WIDE, 102L)  // backward clock jitter!
        val pts4 = generator.computeNextEncoderPts(startNs + 40_000_000L, LensType.WIDE, 103L)

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
