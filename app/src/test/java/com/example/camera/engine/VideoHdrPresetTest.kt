package com.example.camera.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.camera.data.CameraPreferences
import com.example.camera.model.HdrPresetTargetControl
import com.example.camera.model.VideoAdjustments
import com.example.camera.model.VideoHdrPresetConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoHdrPresetTest {

    private lateinit var context: Context
    private lateinit var preferences: CameraPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = CameraPreferences(context)
    }

    @Test
    fun testDefaultVideoHdrPresetConfiguration() {
        val config = VideoHdrPresetConfig()
        assertFalse("HDR Preset should be disabled by default", config.isEnabled)
        assertTrue("Enabled controls should contain HIGHLIGHTS", config.enabledControls.contains("HIGHLIGHTS"))
        assertTrue("Enabled controls should contain SHADOWS", config.enabledControls.contains("SHADOWS"))
        assertTrue("Enabled controls should contain CONTRAST", config.enabledControls.contains("CONTRAST"))
        assertTrue("Enabled controls should contain CLARITY", config.enabledControls.contains("CLARITY"))
        assertTrue("Enabled controls should contain COLOR_VIBRANCE", config.enabledControls.contains("COLOR_VIBRANCE"))

        // Reuses VideoAdjustments model
        val adj = config.adjustments
        assertTrue("Highlights should be compressed for HDR look", adj.highlights < 0f)
        assertTrue("Shadows should be lifted for HDR look", adj.shadows > 0f)
        assertTrue("Clarity should be boosted for HDR structure", adj.clarity > 0f)
        assertTrue("Vibrance should be boosted for HDR color range", adj.colorVibrance > 0f)
    }

    @Test
    fun testHdrPresetAppliesOnlySelectedControlsAndPreservesManualSettings() {
        val baseManual = VideoAdjustments(
            exposure = -0.5f,
            temperature = 15f,
            saturation = 10f,
            highlights = 0f,
            shadows = 0f
        )

        val config = VideoHdrPresetConfig(
            enabledControls = setOf("HIGHLIGHTS", "SHADOWS", "CLARITY"),
            adjustments = VideoAdjustments(
                highlights = -35f,
                shadows = 40f,
                clarity = 25f,
                exposure = 1.0f // Not in enabledControls
            )
        )

        val result = config.applyPreset(baseManual)

        // Selected controls must be updated to preset values
        assertEquals(-35f, result.highlights, 0.001f)
        assertEquals(40f, result.shadows, 0.001f)
        assertEquals(25f, result.clarity, 0.001f)

        // Non-selected controls must preserve the user's manual settings
        assertEquals(-0.5f, result.exposure, 0.001f)
        assertEquals(15f, result.temperature, 0.001f)
        assertEquals(10f, result.saturation, 0.001f)
    }

    @Test
    fun testPreferencesPersistenceAndRestore() {
        val manualSettings = VideoAdjustments(
            exposure = 0.3f,
            saturation = 12f,
            highlights = 5f,
            shadows = -5f
        )
        preferences.savePreHdrVideoAdjustments(manualSettings)

        val loadedManual = preferences.getPreHdrVideoAdjustments()
        assertEquals(0.3f, loadedManual.exposure, 0.001f)
        assertEquals(12f, loadedManual.saturation, 0.001f)
        assertEquals(5f, loadedManual.highlights, 0.001f)
        assertEquals(-5f, loadedManual.shadows, 0.001f)

        // Test saving custom HDR preset configuration
        val customConfig = VideoHdrPresetConfig(
            isEnabled = true,
            enabledControls = setOf("HIGHLIGHTS", "SHADOWS", "CONTRAST"),
            adjustments = VideoAdjustments(highlights = -40f, shadows = 50f, contrast = 15f)
        )
        preferences.saveVideoHdrPresetConfig(customConfig)
        preferences.isVideoHdrPresetActive = true

        val retrievedConfig = preferences.getVideoHdrPresetConfig()
        assertTrue(retrievedConfig.isEnabled)
        assertEquals(3, retrievedConfig.enabledControls.size)
        assertEquals(-40f, retrievedConfig.adjustments.highlights, 0.001f)
        assertEquals(50f, retrievedConfig.adjustments.shadows, 0.001f)
        assertEquals(15f, retrievedConfig.adjustments.contrast, 0.001f)
    }

    @Test
    fun testHdrPresetTargetControlGetAndSet() {
        val adj = VideoAdjustments(highlights = -20f, shadows = 30f, exposure = 0.5f)

        assertEquals(-20f, HdrPresetTargetControl.HIGHLIGHTS.getValue(adj), 0.001f)
        assertEquals(30f, HdrPresetTargetControl.SHADOWS.getValue(adj), 0.001f)
        assertEquals(0.5f, HdrPresetTargetControl.EXPOSURE.getValue(adj), 0.001f)

        val updated = HdrPresetTargetControl.CONTRAST.withValue(adj, 25f)
        assertEquals(25f, updated.contrast, 0.001f)
        assertEquals(-20f, updated.highlights, 0.001f) // untouched

        assertEquals("-20", HdrPresetTargetControl.HIGHLIGHTS.formatValue(-20f))
        assertEquals("+30", HdrPresetTargetControl.SHADOWS.formatValue(30f))
        assertEquals("0.5", HdrPresetTargetControl.EXPOSURE.formatValue(0.5f))
    }

    @Test
    fun testSameShaderPipelineAppliesHdrPreset() {
        val config = VideoHdrPresetConfig()
        val hdrAdjustments = config.applyPreset(VideoAdjustments())

        // Ensure VideoAdjustmentsPipeline processes HDR preset without errors
        val matrix = VideoAdjustmentsPipeline.computeColorMatrix(hdrAdjustments)
        assertNotNull("Color matrix should be computed for HDR tone adjustments", matrix)

        val arr = matrix!!.array
        assertEquals(20, arr.size)
        // Alpha row remains standard identity
        assertEquals(1f, arr[18], 0.001f)
        assertEquals(0f, arr[19], 0.001f)
    }
}
