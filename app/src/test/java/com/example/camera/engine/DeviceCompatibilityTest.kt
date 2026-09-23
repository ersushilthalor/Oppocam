package com.example.camera.engine

import android.content.Context
import android.os.Process
import com.example.camera.engine.hdr.HdrCapturePlanner
import com.example.camera.model.FlashMode
import com.example.camera.sound.CameraSoundManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceCompatibilityTest {

    @Test
    fun testCameraSoundManagerThreadPriorityDoesNotCrash() {
        // Shutter sound calls must execute smoothly and never throw IllegalArgumentException
        CameraSoundManager.playShutter()
        CameraSoundManager.playBurstShutter()
        CameraSoundManager.playStartVideo()
        CameraSoundManager.playStopVideo()
    }

    @Test
    fun testDeviceCompatibilityManagerSafeVideoConfig() {
        val config = DeviceCompatibilityManager.getValidatedVideoConfig(
            requestedWidth = 3840,
            requestedHeight = 2160,
            requestedFps = 60,
            requestedBitrate = 80_000_000,
            preferHevc = true,
            prefer10Bit = true
        )

        assertNotNull(config)
        assertTrue(config.width > 0)
        assertTrue(config.height > 0)
        assertTrue(config.fps > 0)
        assertTrue(config.bitrate > 0)
    }

    @Test
    fun testHdrCapturePlannerSafeWithInvertedOrEmptyRanges() {
        val planner = HdrCapturePlanner()

        // Plan capture with null characteristics and null results - must fallback safely to default plan
        val plan = planner.planCapture(
            chars = null,
            lastResult = null,
            flashMode = FlashMode.OFF,
            stats = null,
            gyroEngine = null
        )

        assertNotNull(plan)
        assertTrue(plan.specs.isNotEmpty())
    }

    @Test
    fun testJavaThreadPriorityRangeSafety() {
        // Ensure only 1..10 is passed to Thread.setPriority
        val t = Thread { }
        t.priority = Thread.NORM_PRIORITY
        assertEquals(5, t.priority)

        // Verify Process.THREAD_PRIORITY_AUDIO (-16) is NOT a valid Thread priority
        val osAudioPriority = Process.THREAD_PRIORITY_AUDIO
        assertEquals(-16, osAudioPriority)
        assertTrue(osAudioPriority < Thread.MIN_PRIORITY)
    }
}
