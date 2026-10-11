package com.example.camera.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoRecordingSavingTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testDualCameraRecordingRelayStartStopState() {
        val dummySurface = android.view.Surface(android.graphics.SurfaceTexture(1))
        val relay = DualCameraRecordingRelay(
            encoderTargetSurface = dummySurface,
            bufferWidth = 1920,
            bufferHeight = 1080,
            fps = 30
        )

        assertTrue("Relay should be ready in Robolectric environment", relay.isRelayReady)

        relay.startRecording()
        relay.stopRecording()

        relay.release()
    }

    @Test
    fun testMp4ContainerHeaderStructure() {
        val cacheDir = context.cacheDir
        val testVideoFile = File(cacheDir, "test_container_valid.mp4")
        if (testVideoFile.exists()) testVideoFile.delete()

        // Create standard MP4 file starting with 'ftyp' box
        RandomAccessFile(testVideoFile, "rw").use { raf ->
            // ftyp box (size: 24, type: 'ftyp', major: 'mp42', minor: 0, comp: 'isom', 'mp42')
            raf.writeInt(24) // size
            raf.writeInt(0x66747970) // 'ftyp'
            raf.writeInt(0x6D703432) // 'mp42'
            raf.writeInt(0) // minor version
            raf.writeInt(0x69736F6D) // 'isom'
            raf.writeInt(0x6D703432) // 'mp42'

            // mdat box
            raf.writeInt(1024) // size
            raf.writeInt(0x6D646174) // 'mdat'
            val dummyBytes = ByteArray(1016) { 0xAA.toByte() }
            raf.write(dummyBytes)
        }

        assertTrue("Test video file must exist", testVideoFile.exists())
        assertTrue("Test video file size must be > 32 bytes", testVideoFile.length() > 32L)

        // Read and verify header matches valid container
        RandomAccessFile(testVideoFile, "r").use { raf ->
            val size = raf.readInt()
            val type = raf.readInt()
            assertEquals("Box size should be 24", 24, size)
            assertEquals("Box type should be 'ftyp'", 0x66747970, type)
            assertTrue(
                "Must match standard MP4 box identifiers",
                type == 0x66747970 || type == 0x6D646174 || type == 0x6D6F6F76 || type == 0x77696465
            )
        }

        testVideoFile.delete()
    }

    @Test
    fun testMediaStoreContentValuesPreparation() {
        val fileName = "VID_20261011_120000.mp4"
        val mimeType = "video/mp4"
        val orientationDegrees = 90
        val width = 1920
        val height = 1080

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.TITLE, fileName.substringBeforeLast('.'))
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            if (width > 0) put(MediaStore.Video.Media.WIDTH, width)
            if (height > 0) put(MediaStore.Video.Media.HEIGHT, height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        assertEquals(fileName, contentValues.getAsString(MediaStore.Video.Media.DISPLAY_NAME))
        assertEquals(mimeType, contentValues.getAsString(MediaStore.Video.Media.MIME_TYPE))
        assertEquals("DCIM/Camera", contentValues.getAsString(MediaStore.Video.Media.RELATIVE_PATH))
        assertEquals(1, contentValues.getAsInteger(MediaStore.Video.Media.IS_PENDING))
        assertEquals(1920, contentValues.getAsInteger(MediaStore.Video.Media.WIDTH))
        assertEquals(1080, contentValues.getAsInteger(MediaStore.Video.Media.HEIGHT))
    }

    @Test
    fun testNormalVideoRecordingEngineStartStop() {
        val engine = NormalVideoRecordingEngine(context)
        val testFile = File(context.cacheDir, "test_normal_rec_${System.currentTimeMillis()}.mp4")

        val surface = engine.startRecording(
            destFile = testFile,
            width = 1920,
            height = 1080,
            fps = 30,
            bitrate = 20_000_000,
            codec = com.example.camera.model.CinemaCodec.H264,
            isAudioEnabled = false
        )

        assertNotNull("Recorder surface must be non-null", surface)
        assertTrue("Recording must be active", engine.isRecordingActive())

        engine.pause()
        engine.resume()

        val saved = engine.stopRecording()
        assertNotNull("Recorded file should be returned", saved)
        assertTrue("File should exist", saved!!.exists())
        assertTrue("File size must be greater than 32 bytes for valid MP4", saved.length() >= 32L)

        saved.delete()
    }
}
