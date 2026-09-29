package com.example.camera.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.camera.data.CameraPreferences
import com.example.camera.model.ModeLayoutConfig
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewfinderCornerRadiusTest {

    private lateinit var context: Context
    private lateinit var preferences: CameraPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = CameraPreferences(context)
    }

    @Test
    fun testDefaultViewfinderCornerRadiusIsZero() {
        // Clear preferences
        val sharedPrefs = context.getSharedPreferences("pro_camera_user_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().commit()

        assertEquals(0, preferences.viewfinderCornerRadiusDp)
    }

    @Test
    fun testViewfinderCornerRadiusPersistence() {
        preferences.viewfinderCornerRadiusDp = 24
        assertEquals(24, preferences.viewfinderCornerRadiusDp)

        preferences.viewfinderCornerRadiusDp = 48
        assertEquals(48, preferences.viewfinderCornerRadiusDp)

        // Clamping check
        preferences.viewfinderCornerRadiusDp = 100
        assertEquals(48, preferences.viewfinderCornerRadiusDp)

        preferences.viewfinderCornerRadiusDp = -10
        assertEquals(0, preferences.viewfinderCornerRadiusDp)
    }

    @Test
    fun testModeLayoutConfigCornerRadiusSerialization() {
        val config = ModeLayoutConfig(viewfinderCornerRadiusDp = 28)
        val json = config.toJson()
        val parsed = ModeLayoutConfig.fromJson(json)
        assertEquals(28, parsed.viewfinderCornerRadiusDp)
    }
}
