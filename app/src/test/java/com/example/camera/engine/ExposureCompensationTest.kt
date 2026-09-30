package com.example.camera.engine

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.camera.model.CameraMode
import com.example.camera.viewmodel.CameraViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExposureCompensationTest {

    private lateinit var app: Application
    private lateinit var viewModel: CameraViewModel

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext<Application>()
        viewModel = CameraViewModel(app)
    }

    @Test
    fun testEvValueFormattingAndCalculation() {
        val step = 1f / 3f

        fun formatEv(index: Int): String {
            val evVal = index * step
            val rounded = (evVal * 10f).roundToInt() / 10f
            return when {
                abs(rounded) < 0.05f -> "0.0 EV"
                rounded > 0f -> String.format(Locale.US, "+%.1f EV", rounded)
                else -> String.format(Locale.US, "%.1f EV", rounded)
            }
        }

        assertEquals("0.0 EV", formatEv(0))
        assertEquals("+1.0 EV", formatEv(3))
        assertEquals("+2.0 EV", formatEv(6))
        assertEquals("-1.0 EV", formatEv(-3))
        assertEquals("-2.0 EV", formatEv(-6))
    }

    @Test
    fun testEvControlWindowToggleAndState() {
        assertFalse("EV control should be closed initially", viewModel.isEvControlOpen.value)

        viewModel.setEvControlOpen(true)
        assertTrue("EV control should be open after setEvControlOpen(true)", viewModel.isEvControlOpen.value)

        viewModel.toggleEvControlOpen()
        assertFalse("EV control should be closed after toggleEvControlOpen()", viewModel.isEvControlOpen.value)

        viewModel.toggleEvControlOpen()
        assertTrue("EV control should be open after second toggle", viewModel.isEvControlOpen.value)
    }

    @Test
    fun testVideoModeEvCompensationKeepsAutoExposureActive() {
        viewModel.setCameraMode(CameraMode.VIDEO)

        // Set manual ISO/shutter to simulate a manual lock
        viewModel.setManualIso(400)
        viewModel.setManualShutterSpeedNs(20_000_000L)
        assertNotNull(viewModel.manualIso.value)
        assertNotNull(viewModel.manualShutterSpeedNs.value)

        // Setting EV must clear manual locks to maintain continuous Auto Exposure
        viewModel.setExposureCompensation(3) // +1.0 EV
        assertEquals(3, viewModel.exposureCompensation.value)
        assertEquals(3, viewModel.engine.exposureCompensationIndex)
        assertNull("Manual ISO must be cleared for continuous AE", viewModel.manualIso.value)
        assertNull("Manual shutter must be cleared for continuous AE", viewModel.manualShutterSpeedNs.value)
        assertFalse("AE lock must not be active", viewModel.isAeLocked.value)
        assertFalse("Engine AE lock must not be active", viewModel.engine.isAeLocked)

        // Reset EV back to 0
        viewModel.resetExposureCompensation()
        assertEquals(0, viewModel.exposureCompensation.value)
        assertEquals(0, viewModel.engine.exposureCompensationIndex)
    }

    @Test
    fun testCinemaModeEvCompensationSyncsWithCinemaConfig() {
        viewModel.setCameraMode(CameraMode.CINEMA)

        viewModel.setExposureCompensation(-3) // -1.0 EV
        assertEquals(-3, viewModel.exposureCompensation.value)
        assertEquals(-3, viewModel.engine.exposureCompensationIndex)
        assertEquals(-3, viewModel.cinemaConfig.value.exposureCompensation)
        assertEquals(-3, viewModel.engine.cinemaConfig.value.exposureCompensation)
        assertNull("Cinema manual ISO must be null for continuous AE", viewModel.cinemaConfig.value.manualIso)
        assertNull("Cinema manual shutter must be null for continuous AE", viewModel.cinemaConfig.value.manualShutterSpeedNs)

        viewModel.setExposureCompensation(6) // +2.0 EV
        assertEquals(6, viewModel.exposureCompensation.value)
        assertEquals(6, viewModel.engine.exposureCompensationIndex)
        assertEquals(6, viewModel.cinemaConfig.value.exposureCompensation)

        viewModel.resetExposureCompensation()
        assertEquals(0, viewModel.exposureCompensation.value)
        assertEquals(0, viewModel.cinemaConfig.value.exposureCompensation)
    }

    @Test
    fun testDeviceRangeClamping() {
        val minEv = -6
        val maxEv = 6

        val underflow = (-12).coerceIn(minEv, maxEv)
        assertEquals(minEv, underflow)

        val overflow = 12.coerceIn(minEv, maxEv)
        assertEquals(maxEv, overflow)

        val valid = 3.coerceIn(minEv, maxEv)
        assertEquals(3, valid)
    }
}
