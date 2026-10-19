package com.example.camera.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.camera.data.CameraPreferences
import com.example.camera.data.RefocusRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstantRefocusPhotoTest {

    private lateinit var context: Context
    private lateinit var preferences: CameraPreferences
    private lateinit var refocusEngine: RefocusEngine
    private lateinit var refocusRepository: RefocusRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = CameraPreferences(context)
        refocusEngine = RefocusEngine(context)
        refocusRepository = RefocusRepository(context)
    }

    private fun createSyntheticNv21(w: Int, h: Int, lumaVal: Int): ByteArray {
        val size = w * h * 3 / 2
        val buffer = ByteArray(size)
        // Fill Y plane
        buffer.fill(lumaVal.toByte(), 0, w * h)
        // Fill VU plane (neutral UV ~128)
        buffer.fill(128.toByte(), w * h, size)
        return buffer
    }

    private fun createSyntheticJpeg(w: Int, h: Int, color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h) { color }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        bmp.recycle()
        return stream.toByteArray()
    }

    @Test
    fun testRefocusFrameCountSettingsPersistenceAndClamping() {
        // Test 3-frame mode
        preferences.refocusFrameCount = 3
        assertEquals(3, preferences.refocusFrameCount)

        // Test 5-frame mode
        preferences.refocusFrameCount = 5
        assertEquals(5, preferences.refocusFrameCount)

        // Test 7-frame mode
        preferences.refocusFrameCount = 7
        assertEquals(7, preferences.refocusFrameCount)

        // Clamp below 3
        preferences.refocusFrameCount = 1
        assertEquals(3, preferences.refocusFrameCount)

        // Clamp above 20
        preferences.refocusFrameCount = 25
        assertEquals(20, preferences.refocusFrameCount)
    }

    @Test
    fun testRefocusBufferPoolAllocationAndRecycling() {
        val frameSize = 640 * 480 * 3 / 2
        val pool = RefocusBufferPool(frameSize, maxPoolCapacity = 5)

        val buf1 = pool.acquire()
        assertNotNull(buf1)
        assertEquals(frameSize, buf1!!.size)

        val buf2 = pool.acquire()
        assertNotNull(buf2)
        assertNotSame(buf1, buf2)

        // Recycle buf1 and re-acquire: should get the pooled instance without allocation
        pool.recycle(buf1)
        val bufRecycled = pool.acquire()
        assertSame(buf1, bufRecycled)

        pool.clear()
    }

    @Test
    fun testAlignmentAndDownsampledYExtraction() {
        val w = 320
        val h = 240
        val nv21 = createSyntheticNv21(w, h, 180)

        val targetW = 80
        val targetH = 60
        val downsampled = RefocusImageUtils.extractDownsampledY(nv21, w, h, targetW, targetH)
        assertEquals(targetW * targetH, downsampled.size)
        assertEquals(180f, downsampled[0], 0.1f)

        // Shift test
        val (dx, dy) = RefocusImageUtils.computeAlignmentShift(downsampled, downsampled, targetW, targetH)
        assertEquals(0f, dx, 0.01f)
        assertEquals(0f, dy, 0.01f)
    }

    @Test
    fun testAsyncRefocusProcessing3F() = runBlocking {
        val w = 320
        val h = 240
        val pool = RefocusBufferPool(w * h * 3 / 2, maxPoolCapacity = 4)

        val frames = listOf(
            RawRefocusFrame(0, 5.0f, w, h, nv21Bytes = createSyntheticNv21(w, h, 100)),
            RawRefocusFrame(1, 2.5f, w, h, nv21Bytes = createSyntheticNv21(w, h, 150)),
            RawRefocusFrame(2, 0.0f, w, h, nv21Bytes = createSyntheticNv21(w, h, 200))
        )

        var requestedFilePrefix = ""
        val entity = refocusEngine.processRawFocusFramesAsync(
            rawFrames = frames,
            planeDiopters = listOf(5.0f, 2.5f, 0.0f),
            captureOrientation = 90,
            isFrontFacing = false,
            saveMirrored = false,
            bufferPool = pool,
            saveMediaStoreJpeg = { _, count ->
                requestedFilePrefix = "REFOCUS_${count}F"
                Uri.parse("content://media/external/images/media/test_refocus_3f")
            }
        )

        assertNotNull("Refocus entity must not be null", entity)
        assertEquals(3, entity!!.planeCount)
        assertEquals("REFOCUS_3F", requestedFilePrefix)

        val bundleDir = File(entity.bundleDir)
        assertTrue(bundleDir.exists())
        assertTrue(File(bundleDir, "plane_0.jpg").exists())
        assertTrue(File(bundleDir, "plane_1.jpg").exists())
        assertTrue(File(bundleDir, "plane_2.jpg").exists())
        assertTrue(File(bundleDir, "plane_near.jpg").exists())
        assertTrue(File(bundleDir, "plane_mid.jpg").exists())
        assertTrue(File(bundleDir, "plane_far.jpg").exists())
        assertTrue(File(bundleDir, "depth_map.png").exists())
        assertTrue(File(bundleDir, "metadata.json").exists())

        // Verify metadata.json has 3F mode
        val metaJson = org.json.JSONObject(File(bundleDir, "metadata.json").readText())
        assertEquals(3, metaJson.getInt("planeCount"))
        assertEquals("3F", metaJson.getString("mode"))

        // Verify retrieval from repository
        val recovered = refocusRepository.getRefocusPhoto(entity.photoUri)
        assertNotNull(recovered)
        assertEquals(3, recovered!!.planeCount)
    }

    @Test
    fun testAsyncRefocusProcessing5FAnd7F() = runBlocking {
        val w = 160
        val h = 120

        // 5-Frame test with JPEGs
        val frames5 = List(5) { i ->
            RawRefocusFrame(
                index = i,
                focusDiopter = 5.0f - i * 1.25f,
                width = w,
                height = h,
                jpegBytes = createSyntheticJpeg(w, h, Color.rgb(i * 40, i * 40, i * 40))
            )
        }

        var prefix5 = ""
        val entity5 = refocusEngine.processRawFocusFramesAsync(
            rawFrames = frames5,
            planeDiopters = listOf(5.0f, 3.75f, 2.5f, 1.25f, 0.0f),
            captureOrientation = 0,
            bufferPool = null,
            saveMediaStoreJpeg = { _, count ->
                prefix5 = "REFOCUS_${count}F"
                Uri.parse("content://media/external/images/media/test_refocus_5f")
            }
        )
        assertNotNull(entity5)
        assertEquals(5, entity5!!.planeCount)
        assertEquals("REFOCUS_5F", prefix5)

        // 7-Frame test with JPEGs
        val frames7 = List(7) { i ->
            RawRefocusFrame(
                index = i,
                focusDiopter = 6.0f - i * 1.0f,
                width = w,
                height = h,
                jpegBytes = createSyntheticJpeg(w, h, Color.rgb(i * 30, i * 30, i * 30))
            )
        }

        var prefix7 = ""
        val entity7 = refocusEngine.processRawFocusFramesAsync(
            rawFrames = frames7,
            planeDiopters = (0..6).map { 6.0f - it },
            captureOrientation = 0,
            bufferPool = null,
            saveMediaStoreJpeg = { _, count ->
                prefix7 = "REFOCUS_${count}F"
                Uri.parse("content://media/external/images/media/test_refocus_7f")
            }
        )
        assertNotNull(entity7)
        assertEquals(7, entity7!!.planeCount)
        assertEquals("REFOCUS_7F", prefix7)
    }
}
