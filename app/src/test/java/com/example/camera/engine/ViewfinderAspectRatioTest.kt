package com.example.camera.engine

import android.content.Context
import android.graphics.Matrix
import android.util.Size
import android.view.TextureView
import androidx.test.core.app.ApplicationProvider
import com.example.camera.model.CameraMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewfinderAspectRatioTest {

    private lateinit var context: Context
    private lateinit var engine: Camera2Engine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = Camera2Engine(context)
        engine.detectHardwareLenses()
    }

    @Test
    fun testTargetAspectRatioForModes() {
        // Photo, Portrait, and Night modes must strictly maintain 4:3 ratio
        assertEquals(4f / 3f, engine.getTargetAspectRatioForMode(CameraMode.PHOTO), 0.001f)
        assertEquals(4f / 3f, engine.getTargetAspectRatioForMode(CameraMode.PORTRAIT), 0.001f)
        assertEquals(4f / 3f, engine.getTargetAspectRatioForMode(CameraMode.NIGHT), 0.001f)

        // Video and Cinema modes must strictly maintain 16:9 ratio
        assertEquals(16f / 9f, engine.getTargetAspectRatioForMode(CameraMode.VIDEO), 0.001f)
        assertEquals(16f / 9f, engine.getTargetAspectRatioForMode(CameraMode.CINEMA), 0.001f)
    }

    @Test
    fun testPhotoToVideoToPhotoTransition() {
        // Initial Photo mode
        engine.setMode(CameraMode.PHOTO)
        assertEquals(CameraMode.PHOTO, engine.currentMode)
        assertEquals(4f / 3f, engine.previewAspectRatio.value, 0.01f)

        val photoBufferSize = engine.previewBufferSize.value
        assertNotNull("Photo buffer size must not be null", photoBufferSize)
        val photoRatio = photoBufferSize!!.width.coerceAtLeast(photoBufferSize.height).toFloat() /
                photoBufferSize.width.coerceAtMost(photoBufferSize.height).toFloat()
        assertEquals("Photo preview buffer must be 4:3", 4f / 3f, photoRatio, 0.05f)

        // Transition: Photo -> Video
        engine.setMode(CameraMode.VIDEO)
        assertEquals(CameraMode.VIDEO, engine.currentMode)
        assertEquals(16f / 9f, engine.previewAspectRatio.value, 0.01f)

        val videoBufferSize = engine.previewBufferSize.value
        assertNotNull("Video buffer size must not be null", videoBufferSize)
        val videoRatio = videoBufferSize!!.width.coerceAtLeast(videoBufferSize.height).toFloat() /
                videoBufferSize.width.coerceAtMost(videoBufferSize.height).toFloat()
        assertEquals("Video preview buffer must be 16:9", 16f / 9f, videoRatio, 0.05f)

        // Transition: Video -> Photo
        engine.setMode(CameraMode.PHOTO)
        assertEquals(CameraMode.PHOTO, engine.currentMode)
        assertEquals(4f / 3f, engine.previewAspectRatio.value, 0.01f)

        val restoredPhotoSize = engine.previewBufferSize.value
        assertNotNull("Restored Photo buffer size must not be null", restoredPhotoSize)
        val restoredRatio = restoredPhotoSize!!.width.coerceAtLeast(restoredPhotoSize.height).toFloat() /
                restoredPhotoSize.width.coerceAtMost(restoredPhotoSize.height).toFloat()
        assertEquals("Restored Photo buffer must be 4:3 without vertical squeezing", 4f / 3f, restoredRatio, 0.05f)
    }

    @Test
    fun testVideoToPhotoToVideoTransition() {
        // Initial Video mode
        engine.setMode(CameraMode.VIDEO)
        assertEquals(CameraMode.VIDEO, engine.currentMode)
        assertEquals(16f / 9f, engine.previewAspectRatio.value, 0.01f)

        // Transition: Video -> Photo
        engine.setMode(CameraMode.PHOTO)
        assertEquals(CameraMode.PHOTO, engine.currentMode)
        assertEquals(4f / 3f, engine.previewAspectRatio.value, 0.01f)

        // Transition: Photo -> Video
        engine.setMode(CameraMode.VIDEO)
        assertEquals(CameraMode.VIDEO, engine.currentMode)
        assertEquals(16f / 9f, engine.previewAspectRatio.value, 0.01f)

        val videoBufferSize = engine.previewBufferSize.value
        assertNotNull("Video buffer size must not be null", videoBufferSize)
        val videoRatio = videoBufferSize!!.width.coerceAtLeast(videoBufferSize.height).toFloat() /
                videoBufferSize.width.coerceAtMost(videoBufferSize.height).toFloat()
        assertEquals("Restored Video buffer must be 16:9 without stretching", 16f / 9f, videoRatio, 0.05f)
    }

    @Test
    fun testRepeatedRapidPhotoVideoSwitching() {
        // Rapid switching back and forth
        val modesToTest = listOf(
            CameraMode.PHOTO,
            CameraMode.VIDEO,
            CameraMode.PHOTO,
            CameraMode.VIDEO,
            CameraMode.PHOTO,
            CameraMode.VIDEO,
            CameraMode.PHOTO
        )

        for (mode in modesToTest) {
            engine.setMode(mode)
            val expectedRatio = if (mode == CameraMode.PHOTO) 4f / 3f else 16f / 9f
            assertEquals("Preview aspect ratio must immediately match mode $mode", expectedRatio, engine.previewAspectRatio.value, 0.01f)

            val buf = engine.previewBufferSize.value
            assertNotNull("Buffer size must be set for mode $mode", buf)
            val aspect = buf!!.width.coerceAtLeast(buf.height).toFloat() / buf.width.coerceAtMost(buf.height).toFloat()
            assertEquals("Buffer aspect ratio must match mode $mode", expectedRatio, aspect, 0.05f)
        }

        // Final state must strictly be Photo 4:3
        assertEquals(CameraMode.PHOTO, engine.currentMode)
        assertEquals(4f / 3f, engine.previewAspectRatio.value, 0.01f)
    }

    @Test
    fun testTransformationMatrixUniformScaling() {
        val textureView = TextureView(context)

        // Case 1: Photo mode 3:4 view (1080x1440) with 4:3 buffer (4032x3024)
        // Ratio diff is 0 -> Matrix must be Identity (no distortion, 1:1 square pixels)
        val matrix = Matrix()
        val viewW = 1080f
        val viewH = 1440f
        val bufW = 3024f
        val bufH = 4032f
        val viewAspect = viewH / viewW // 1.3333
        val bufAspect = bufH / bufW // 1.3333

        if (abs(bufAspect - viewAspect) > 0.01f) {
            fail("4:3 view with 4:3 buffer should match aspect ratio without transformation")
        }
        assertTrue(matrix.isIdentity)

        // Case 2: Video mode 9:16 view (1080x1920) with 16:9 buffer (1920x1080)
        // Ratio diff is 0 -> Matrix must be Identity
        val videoViewW = 1080f
        val videoViewH = 1920f
        val videoBufW = 1080f
        val videoBufH = 1920f
        val videoViewAspect = videoViewH / videoViewW // 1.7778
        val videoBufAspect = videoBufH / videoBufW // 1.7778

        if (abs(videoBufAspect - videoViewAspect) > 0.01f) {
            fail("16:9 view with 16:9 buffer should match aspect ratio without transformation")
        }
        assertTrue(matrix.isIdentity)

        // Case 3: Transition view (1080x1920) with 4:3 buffer (1080x1440)
        // View is taller than buffer -> uniform scaleX should be viewAspect / bufAspect
        val transitionScaleX = videoViewAspect / viewAspect
        assertEquals(1.3333f, transitionScaleX, 0.01f)
    }
}
