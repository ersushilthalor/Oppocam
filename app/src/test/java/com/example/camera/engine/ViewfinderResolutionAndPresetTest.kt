package com.example.camera.engine

import androidx.test.core.app.ApplicationProvider
import com.example.camera.model.CameraMode
import com.example.camera.model.CameraResolution
import com.example.camera.viewmodel.CameraViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewfinderResolutionAndPresetTest {

    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        viewModel = CameraViewModel(app)
        viewModel.engine.detectHardwareLenses()
    }

    @Test
    fun testZoomPresetsWithoutSwitchPointsToggle() {
        // When toggle is OFF, zoom presets must remain standard
        viewModel.setIncludeLensSwitchPointsInPresets(false)
        val presets = viewModel.activeZoomPresets.value

        assertTrue("Presets must contain 1.0x", presets.contains(1.0f))
        assertTrue("Presets must contain 2.0x", presets.contains(2.0f))
        assertFalse("Presets must not contain non-default switch point when toggle is off", presets.any { it in 1.1f..1.9f })
    }

    @Test
    fun test28mmLensSwitchPointAddedToZoomPresetsBetween1xAnd2x() {
        // Enable switch points toggle
        viewModel.setIncludeLensSwitchPointsInPresets(true)

        // Select 28mm switch point
        viewModel.setLensSwitchPointMm(28.0f)

        val presets = viewModel.activeZoomPresets.value

        // Must contain ~1.3x between 1.0x and 2.0x
        val switchPointZoom = presets.firstOrNull { it in 1.2f..1.4f }
        assertNotNull("Must include switch point around 1.3x in zoom presets", switchPointZoom)
        assertEquals(1.3f, switchPointZoom!!, 0.05f)

        // Must be correctly positioned between 1.0x and 2.0x
        val idx1x = presets.indexOf(1.0f)
        val idxSwitch = presets.indexOf(switchPointZoom)
        val idx2x = presets.indexOf(2.0f)

        assertTrue("1.0x must precede switch point", idx1x >= 0 && idx1x < idxSwitch)
        assertTrue("Switch point must precede 2.0x", idxSwitch < idx2x)

        // Verify presets are strictly sorted ascending
        for (i in 0 until presets.size - 1) {
            assertTrue("Presets must be strictly sorted ascending", presets[i] < presets[i + 1])
        }

        // When toggled back off, non-default switch point is removed
        viewModel.setIncludeLensSwitchPointsInPresets(false)
        val revertedPresets = viewModel.activeZoomPresets.value
        assertFalse("Reverted presets must not contain 1.3x", revertedPresets.any { it in 1.2f..1.4f })
    }

    @Test
    fun testDefault23mmDoesNotAddDuplicatePreset() {
        viewModel.setIncludeLensSwitchPointsInPresets(true)
        // 23mm is the default switch point (1.0x)
        viewModel.setLensSwitchPointMm(23.0f)

        val presets = viewModel.activeZoomPresets.value
        assertEquals("1.0x should appear only once", 1, presets.count { kotlin.math.abs(it - 1.0f) < 0.05f })
        assertFalse("No extra switch point between 1x and 2x for default 23mm", presets.any { it in 1.1f..1.9f })
    }

    @Test
    fun testViewfinderUsesSelectedRecordingResolutionInVideoMode() {
        val engine = viewModel.engine
        engine.setMode(CameraMode.VIDEO)

        val chars = engine.getCharacteristics(engine.selectedLens.value?.cameraId)
        val map = chars?.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()
        val hasNative4K = supportedSizes.any { maxOf(it.width, it.height) >= 3840 }

        // 1. Select 4K recording resolution
        val res4K = CameraResolution(3840, 2160)
        engine.selectVideoResolution(res4K)

        val optimalSize4K = engine.getOptimalPreviewSize(targetRatio = 16f / 9f)
        val maxDim4K = maxOf(optimalSize4K.width, optimalSize4K.height)
        if (hasNative4K) {
            assertTrue("In Video mode with 4K selected on 4K-capable sensor, viewfinder optimal preview max dim must be >= 3840 (got $maxDim4K)", maxDim4K >= 3840)
        } else {
            // Falls back to highest available preview size on this sensor
            val expectedHighest = supportedSizes.filter {
                val r = maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height).toFloat()
                kotlin.math.abs(r - (16f / 9f)) < 0.08f
            }.maxOfOrNull { maxOf(it.width, it.height) } ?: 1920
            assertEquals("In Video mode with 4K selected on non-4K sensor, falls back to highest supported preview size", expectedHighest, maxDim4K)
        }

        // 2. Select 1080p recording resolution
        val res1080p = CameraResolution(1920, 1080)
        engine.selectVideoResolution(res1080p)

        val optimalSize1080p = engine.getOptimalPreviewSize(targetRatio = 16f / 9f)
        val maxDim1080p = maxOf(optimalSize1080p.width, optimalSize1080p.height)
        assertEquals("In Video mode with 1080p selected, viewfinder optimal preview max dim must be 1920", 1920, maxDim1080p)

        // 3. Select 720p recording resolution
        val res720p = CameraResolution(1280, 720)
        engine.selectVideoResolution(res720p)

        val optimalSize720p = engine.getOptimalPreviewSize(targetRatio = 16f / 9f)
        val maxDim720p = maxOf(optimalSize720p.width, optimalSize720p.height)
        assertEquals("In Video mode with 720p selected, viewfinder optimal preview max dim must be 1280", 1280, maxDim720p)
    }

    @Test
    fun testViewfinderUsesSelectedRecordingResolutionInCinemaMode() {
        val engine = viewModel.engine
        engine.setMode(CameraMode.CINEMA)

        val chars = engine.getCharacteristics(engine.selectedLens.value?.cameraId)
        val map = chars?.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()
        val hasNative4K = supportedSizes.any { maxOf(it.width, it.height) >= 3840 }

        // 1. Select 4K resolution in Cinema Mode
        val res4K = CameraResolution(3840, 2160)
        engine.setCinemaConfig(engine.cinemaConfig.value.copy(selectedResolution = res4K))

        val optimalSize4K = engine.getOptimalPreviewSize(targetRatio = 16f / 9f)
        val maxDim4K = maxOf(optimalSize4K.width, optimalSize4K.height)
        if (hasNative4K) {
            assertTrue("In Pro Video (Cinema) mode with 4K selected, viewfinder optimal preview max dim must be >= 3840", maxDim4K >= 3840)
        } else {
            val expectedHighest = supportedSizes.filter {
                val r = maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height).toFloat()
                kotlin.math.abs(r - (16f / 9f)) < 0.08f
            }.maxOfOrNull { maxOf(it.width, it.height) } ?: 1920
            assertEquals("In Pro Video with 4K selected on non-4K sensor, falls back to highest supported preview size", expectedHighest, maxDim4K)
        }

        // 2. Select 1080p resolution in Cinema Mode
        val res1080p = CameraResolution(1920, 1080)
        engine.setCinemaConfig(engine.cinemaConfig.value.copy(selectedResolution = res1080p))

        val optimalSize1080p = engine.getOptimalPreviewSize(targetRatio = 16f / 9f)
        val maxDim1080p = maxOf(optimalSize1080p.width, optimalSize1080p.height)
        assertEquals("In Pro Video (Cinema) mode with 1080p selected, viewfinder optimal preview max dim must be 1920", 1920, maxDim1080p)

        // 3. Select 720p resolution in Cinema Mode
        val res720p = CameraResolution(1280, 720)
        engine.setCinemaConfig(engine.cinemaConfig.value.copy(selectedResolution = res720p))

        val optimalSize720p = engine.getOptimalPreviewSize(targetRatio = 16f / 9f)
        val maxDim720p = maxOf(optimalSize720p.width, optimalSize720p.height)
        assertEquals("In Pro Video (Cinema) mode with 720p selected, viewfinder optimal preview max dim must be 1280", 1280, maxDim720p)
    }
        val maxDim1080p = maxOf(optimalSize1080p.width, optimalSize1080p.height)
        assertEquals("In Pro Video (Cinema) mode with 1080p selected, viewfinder optimal preview max dim must be 1920", 1920, maxDim1080p)
    }
}
