package com.example.camera.engine

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import androidx.test.core.app.ApplicationProvider
import com.example.camera.data.CameraPreferences
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LensColourMatchingTest {

    private lateinit var context: Context
    private lateinit var matchingEngine: LensColourMatchingEngine
    private lateinit var preferences: CameraPreferences
    private lateinit var camera2Engine: Camera2Engine

    private val mainLens = LensInfo(
        cameraId = "0",
        facing = CameraCharacteristics.LENS_FACING_BACK,
        lensType = LensType.WIDE,
        displayName = "1x Main",
        focalLengthMm = 5.0f,
        maxAperture = 1.8f,
        isPrimaryMain = true,
        baseZoomRatio = 1.0f
    )

    private val ultraWideLens = LensInfo(
        cameraId = "2",
        facing = CameraCharacteristics.LENS_FACING_BACK,
        lensType = LensType.ULTRAWIDE,
        displayName = "0.5x Ultra Wide",
        focalLengthMm = 2.0f,
        maxAperture = 2.2f,
        isIndependentCamera = true,
        baseZoomRatio = 0.5f
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = CameraPreferences(context)
        matchingEngine = LensColourMatchingEngine(context)
        camera2Engine = Camera2Engine(context)
    }

    @Test
    fun testPreferencesPersistence() {
        preferences.isCorrectColourShift = true
        assertTrue(preferences.isCorrectColourShift)

        preferences.isCorrectColourShift = false
        assertFalse(preferences.isCorrectColourShift)

        preferences.isCorrectColourShift = true
        assertTrue(preferences.isCorrectColourShift)
    }

    @Test
    fun testEngineToggleState() {
        matchingEngine.isEnabled = true
        assertTrue(matchingEngine.isEnabled)

        matchingEngine.isEnabled = false
        assertFalse(matchingEngine.isEnabled)

        // When disabled, render color matrix must always be null
        assertNull(matchingEngine.computeRenderColorMatrix(LensType.ULTRAWIDE))
        assertNull(matchingEngine.computeRenderColorMatrix(LensType.WIDE))
    }

    @Test
    fun testApertureLightGatheringCalculation() {
        // Manually register characteristics mock or use engine's calculated aperture ratio
        val lightRatioMainToUw = matchingEngine.getApertureLightRatio(LensType.ULTRAWIDE, LensType.WIDE)
        // (2.2 / 1.8)^2 ≈ 1.4938
        assertEquals(1.4938f, lightRatioMainToUw, 0.05f)

        val lightRatioUwToMain = matchingEngine.getApertureLightRatio(LensType.WIDE, LensType.ULTRAWIDE)
        // (1.8 / 2.2)^2 ≈ 0.6694
        assertEquals(0.6694f, lightRatioUwToMain, 0.05f)

        // Same lens type should have 1.0 ratio
        val ratioIdentical = matchingEngine.getApertureLightRatio(LensType.WIDE, LensType.WIDE)
        assertEquals(1.0f, ratioIdentical, 0.001f)
    }

    @Test
    fun testCamera2EngineIntegrationToggle() {
        camera2Engine.setCorrectColourShift(true)
        assertTrue(camera2Engine.isCorrectColourShift.value)
        assertTrue(camera2Engine.lensColourMatchingEngine.isEnabled)
        assertTrue(preferences.isCorrectColourShift)

        camera2Engine.setCorrectColourShift(false)
        assertFalse(camera2Engine.isCorrectColourShift.value)
        assertFalse(camera2Engine.lensColourMatchingEngine.isEnabled)
        assertFalse(preferences.isCorrectColourShift)

        // Restore to true
        camera2Engine.setCorrectColourShift(true)
        assertTrue(camera2Engine.isCorrectColourShift.value)
    }

    @Test
    fun testRenderColorMatrixReturnsNullWhenDisabledOrUnconverged() {
        matchingEngine.isEnabled = false
        assertNull(matchingEngine.computeRenderColorMatrix(LensType.ULTRAWIDE))

        matchingEngine.isEnabled = true
        // Without telemetry, should safely return null (no arbitrary hardcoded guessing)
        assertNull(matchingEngine.computeRenderColorMatrix(LensType.ULTRAWIDE))
        assertNull(matchingEngine.computeRenderColorMatrix(LensType.WIDE))
    }

    @Test
    fun testDisabledStateDoesNotRunCorrectionInStandby() {
        matchingEngine.isEnabled = false
        assertFalse(matchingEngine.isEnabled)
        assertNull(matchingEngine.computeRenderColorMatrix(LensType.ULTRAWIDE))
        assertNull(matchingEngine.computeRenderColorMatrix(LensType.WIDE))
    }
}
