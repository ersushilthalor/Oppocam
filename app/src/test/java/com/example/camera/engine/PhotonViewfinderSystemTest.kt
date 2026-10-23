package com.example.camera.engine

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.test.core.app.ApplicationProvider
import com.example.camera.model.CameraMode
import com.example.camera.ui.configureTransform
import com.example.camera.ui.resolvePhotonAspectRatio
import com.example.camera.ui.updateTextureViewTransform
import com.hinnka.mycamera.camera.AspectRatio as PhotonAspectRatio
import com.hinnka.mycamera.camera.CameraCoordinateRect
import com.hinnka.mycamera.camera.PreviewMeteringMapper
import com.hinnka.mycamera.camera.PreviewMeteringZoomMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotonViewfinderSystemTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testPhotonAspectRatioResolution() {
        // 4:3 ratio
        val ratio43 = resolvePhotonAspectRatio(4f / 3f, CameraMode.PHOTO, null)
        assertEquals(PhotonAspectRatio.RATIO_4_3, ratio43)

        // 16:9 ratio
        val ratio169 = resolvePhotonAspectRatio(16f / 9f, CameraMode.VIDEO, null)
        assertEquals(PhotonAspectRatio.RATIO_16_9, ratio169)

        // 1:1 square ratio
        val ratio11 = resolvePhotonAspectRatio(1.0f, CameraMode.PHOTO, null)
        assertEquals(PhotonAspectRatio.RATIO_1_1, ratio11)

        // Full screen (0.0f)
        val ratioFull = resolvePhotonAspectRatio(0f, CameraMode.PHOTO, null)
        assertEquals(PhotonAspectRatio.FULL, ratioFull)

        // 3:2 ratio
        val ratio32 = resolvePhotonAspectRatio(3f / 2f, CameraMode.PHOTO, null)
        assertEquals(PhotonAspectRatio.RATIO_3_2, ratio32)

        // 21:9 ratio
        val ratio219 = resolvePhotonAspectRatio(21f / 9f, CameraMode.CINEMA, null)
        assertEquals(PhotonAspectRatio.RATIO_21_9, ratio219)

        // XPAN ratio (65:24)
        val ratioXpan = resolvePhotonAspectRatio(65f / 24f, CameraMode.PHOTO, null)
        assertEquals(PhotonAspectRatio.XPAN, ratioXpan)
    }

    @Test
    fun testSteadyStateTransformsProduceIdentityMatrix() {
        val tv = TextureView(context)

        // 4:3 steady-state (1080x1440 view with 1440x1080 Camera2 sensor buffer)
        tv.layout(0, 0, 1080, 1440)
        updateTextureViewTransform(
            textureView = tv,
            previewBufferSize = Size(1440, 1080),
            targetRatio = 4f / 3f,
            viewWidth = 1080,
            viewHeight = 1440
        )
        val matrix43 = Matrix()
        tv.getTransform(matrix43)
        assertTrue("4:3 steady-state transform must be Identity (no stretching)", matrix43.isIdentity)

        // 16:9 steady-state (1080x1920 view with 1920x1080 Camera2 sensor buffer)
        tv.layout(0, 0, 1080, 1920)
        updateTextureViewTransform(
            textureView = tv,
            previewBufferSize = Size(1920, 1080),
            targetRatio = 16f / 9f,
            viewWidth = 1080,
            viewHeight = 1920
        )
        val matrix169 = Matrix()
        tv.getTransform(matrix169)
        assertTrue("16:9 steady-state transform must be Identity (no stretching)", matrix169.isIdentity)
    }

    @Test
    fun testSquareViewfinderUniformCenterCropTransform() {
        val tv = TextureView(context)
        // 1:1 view (1080x1080) with 4:3 buffer (1440x1080 portrait upright)
        tv.layout(0, 0, 1080, 1080)
        configureTransform(
            textureView = tv,
            previewBufferSize = Size(1440, 1080),
            targetRatio = 1.0f,
            viewWidth = 1080,
            viewHeight = 1080
        )
        val matrix = Matrix()
        tv.getTransform(matrix)
        assertFalse("Square view with 4:3 buffer must apply center-crop transform", matrix.isIdentity)

        val values = FloatArray(9)
        matrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        val scaleY = values[Matrix.MSCALE_Y]

        // For square 1080x1080 view on 1080x1440 upright buffer:
        // Width fits 1:1 (scaleX = 1.0), height scales up by 1.3333x (4/3) to center-crop top and bottom
        assertEquals(1.0f, scaleX, 0.02f)
        assertEquals(1.3333f, scaleY, 0.05f)
    }

    @Test
    fun testWidescreenViewfinderUniformCenterCropTransform() {
        val tv = TextureView(context)
        // 16:9 view (1080x1920) with 4:3 buffer (1440x1080 portrait upright)
        tv.layout(0, 0, 1080, 1920)
        configureTransform(
            textureView = tv,
            previewBufferSize = Size(1440, 1080),
            targetRatio = 16f / 9f,
            viewWidth = 1080,
            viewHeight = 1920
        )
        val matrix = Matrix()
        tv.getTransform(matrix)
        assertFalse("16:9 view with 4:3 buffer must apply horizontal center-crop transform", matrix.isIdentity)

        val values = FloatArray(9)
        matrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        val scaleY = values[Matrix.MSCALE_Y]

        // For 16:9 1080x1920 view on 1080x1440 upright buffer:
        // Height fits 1920/1440 = 1.333x, so scaleX scales by 1.333x to crop sides cleanly
        assertEquals(1.3333f, scaleX, 0.05f)
        assertEquals(1.0f, scaleY, 0.02f)
    }

    @Test
    fun testPreviewMeteringMapperCenterCoordinates() {
        // Active array: 0, 0 to 4000, 3000 (4:3 sensor landscape)
        val activeArray = CameraCoordinateRect(0, 0, 4000, 3000)

        // Center tap (0.5, 0.5) in 4:3 portrait with 90° rear sensor
        val mapping43 = PreviewMeteringMapper.mapPoint(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            activeArray = activeArray,
            scalerCropRegion = null,
            zoomMode = PreviewMeteringZoomMode.POST_ZOOM_ACTIVE_ARRAY,
            previewViewAspectRatio = 4f / 3f,
            sensorOrientationDegrees = 90,
            isFrontFacing = false
        )
        assertNotNull("Mapping must not be null", mapping43)
        // Center of 4000x3000 is 2000, 1500
        assertEquals(2000, mapping43!!.centerX)
        assertEquals(1500, mapping43.centerY)

        // Center tap (0.5, 0.5) in 1:1 square view
        val mapping11 = PreviewMeteringMapper.mapPoint(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            activeArray = activeArray,
            scalerCropRegion = null,
            zoomMode = PreviewMeteringZoomMode.POST_ZOOM_ACTIVE_ARRAY,
            previewViewAspectRatio = 1.0f,
            sensorOrientationDegrees = 90,
            isFrontFacing = false
        )
        assertNotNull("Square view mapping must not be null", mapping11)
        assertEquals(2000, mapping11!!.centerX)
        assertEquals(1500, mapping11.centerY)

        // Centered region generation
        val region = PreviewMeteringMapper.buildCenteredRegion(mapping11, 0.12f, 0.12f)
        assertTrue("Region must be non-empty", region.width > 0 && region.height > 0)
        assertEquals(2000, (region.left + region.right) / 2)
        assertEquals(1500, (region.top + region.bottom) / 2)
    }
}
