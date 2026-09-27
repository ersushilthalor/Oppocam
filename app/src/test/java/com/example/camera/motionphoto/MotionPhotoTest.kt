package com.example.camera.motionphoto

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionPhotoTest {

    @Test
    fun testMotionPhotoDurationDefaultsAndValues() {
        assertEquals("Default duration must be 2 seconds", MotionPhotoDuration.TWO_SECONDS, MotionPhotoDuration.DEFAULT)
        assertEquals(1000L, MotionPhotoDuration.ONE_SECOND.totalDurationMs)
        assertEquals(500L, MotionPhotoDuration.ONE_SECOND.beforeDurationMs)
        assertEquals(500L, MotionPhotoDuration.ONE_SECOND.afterDurationMs)

        assertEquals(2000L, MotionPhotoDuration.TWO_SECONDS.totalDurationMs)
        assertEquals(1000L, MotionPhotoDuration.TWO_SECONDS.beforeDurationMs)
        assertEquals(1000L, MotionPhotoDuration.TWO_SECONDS.afterDurationMs)

        assertEquals(MotionPhotoDuration.ONE_SECOND, MotionPhotoDuration.fromName("ONE_SECOND"))
        assertEquals(MotionPhotoDuration.ONE_SECOND, MotionPhotoDuration.fromName("1s"))
        assertEquals(MotionPhotoDuration.TWO_SECONDS, MotionPhotoDuration.fromName("TWO_SECONDS"))
        assertEquals(MotionPhotoDuration.TWO_SECONDS, MotionPhotoDuration.fromName("2s"))
        assertEquals(MotionPhotoDuration.TWO_SECONDS, MotionPhotoDuration.fromName("invalid_name"))
    }

    @Test
    fun testXmpMetadataGeneration() {
        val videoBytesLength = 543210L
        val presentationTimestampUs = 1000000L

        val xmpXml = MotionPhotoXmpPacker.buildXmpMetadata(
            videoLengthBytes = videoBytesLength,
            presentationTimestampUs = presentationTimestampUs
        )

        assertTrue(xmpXml.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(xmpXml.contains("GCamera:MotionPhotoVersion=\"1\""))
        assertTrue(xmpXml.contains("GCamera:MotionPhotoPresentationTimestampUs=\"1000000\""))
        assertTrue(xmpXml.contains("GCamera:MicroVideo=\"1\""))
        assertTrue(xmpXml.contains("GCamera:MicroVideoVersion=\"1\""))
        assertTrue(xmpXml.contains("GCamera:MicroVideoOffset=\"543210\""))
        assertTrue(xmpXml.contains("GCamera:MicroVideoPresentationTimestampUs=\"1000000\""))
        assertTrue(xmpXml.contains("<Item:Mime>image/jpeg</Item:Mime>"))
        assertTrue(xmpXml.contains("<Item:Semantic>Primary</Item:Semantic>"))
        assertTrue(xmpXml.contains("<Item:Mime>video/mp4</Item:Mime>"))
        assertTrue(xmpXml.contains("<Item:Semantic>MotionPhoto</Item:Semantic>"))
        assertTrue(xmpXml.contains("<Item:Length>543210</Item:Length>"))
    }

    @Test
    fun testInjectXmpIntoJpegAndPackMotionPhoto() {
        // Create a minimal synthetic JPEG byte array with SOI (0xFF, 0xD8),
        // an EXIF APP1 marker, image data, and EOI (0xFF, 0xD9)
        val dummyJpeg = ByteArrayOutputStream().apply {
            write(0xFF) // SOI
            write(0xD8)
            // Fake EXIF APP1
            write(0xFF)
            write(0xE1)
            val exifPayload = "Exif\u0000\u0000SampleExifData".toByteArray(StandardCharsets.ISO_8859_1)
            val exifLen = exifPayload.size + 2
            write((exifLen shr 8) and 0xFF)
            write(exifLen and 0xFF)
            write(exifPayload)
            // Image data
            write(byteArrayOf(0x01, 0x02, 0x03, 0x04))
            // EOI
            write(0xFF)
            write(0xD9)
        }.toByteArray()

        val dummyMp4 = "ftypmp42fake_mp4_video_stream_content_123456789".toByteArray(StandardCharsets.ISO_8859_1)

        val packedBytes = MotionPhotoXmpPacker.packMotionPhoto(
            stillJpegBytes = dummyJpeg,
            motionMp4Bytes = dummyMp4,
            presentationTimestampUs = 1000000L
        )

        assertNotNull(packedBytes)
        assertTrue(packedBytes.size > dummyJpeg.size + dummyMp4.size)

        // Verify motion photo markers
        assertTrue(MotionPhotoXmpPacker.isMotionPhoto(packedBytes))

        val details = MotionPhotoXmpPacker.extractMotionPhotoDetails(packedBytes)
        assertNotNull(details)
        assertTrue(details!!.isMotionPhoto)
        assertEquals(dummyMp4.size.toLong(), details.videoOffset)
        assertEquals(1000000L, details.presentationTimestampUs)

        // Verify MP4 is appended at the exact end of the file
        val mp4OffsetInPacked = packedBytes.size - dummyMp4.size
        val extractedMp4 = packedBytes.copyOfRange(mp4OffsetInPacked, packedBytes.size)
        assertArrayEquals(dummyMp4, extractedMp4)
    }

    @Test
    fun testMotionPhotoFrameBufferSlidingWindow() {
        val buffer = MotionPhotoFrameBuffer(maxBeforeDurationMs = 1000L)
        val baseTimeNs = 1_000_000_000_000L

        // Add 5 frames 300ms apart (total span 1200ms)
        for (i in 0..4) {
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val frameTimeNs = baseTimeNs + (i * 300_000_000L)
            buffer.addFrame(
                sourceBitmap = bmp,
                timestampNs = frameTimeNs,
                orientationDegrees = 0,
                isFrontCamera = false
            )
            bmp.recycle()
        }

        // Shutter moment at i=4 (baseTimeNs + 1200ms)
        val shutterTimeNs = baseTimeNs + (4 * 300_000_000L)
        // With 1000ms duration, cutoff is shutterTimeNs - 1000ms.
        // Frames at i=1 (300ms), i=2 (600ms), i=3 (900ms), i=4 (1200ms) should be within window;
        // frame at i=0 (0ms) should be trimmed/evicted.
        val extracted = buffer.extractPreShutterFrames(shutterTimeNs, 1000L)
        assertTrue("Extracted frames should be non-empty", extracted.isNotEmpty())
        for (frame in extracted) {
            assertTrue(frame.timestampNs >= shutterTimeNs - 1_000_000_000L)
            assertTrue(frame.timestampNs <= shutterTimeNs)
            assertFalse(frame.bitmap.isRecycled)
        }

        buffer.clear()
        assertEquals(0, buffer.currentFrameCount)
    }
}
