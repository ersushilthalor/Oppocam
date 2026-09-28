package com.example.camera.motionphoto

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Handles Google Photos-compatible XMP metadata creation, JPEG APP1 segment injection,
 * and binary combining of the still JPEG photo and the motion MP4 video clip into a
 * single Motion Photo file.
 *
 * Implements both Google Camera MicroVideo v1 and Container Directory v2 specifications:
 * - GCamera:MotionPhoto="1"
 * - GCamera:MotionPhotoVersion="1"
 * - GCamera:MotionPhotoPresentationTimestampUs="<us>"
 * - GCamera:MicroVideo="1"
 * - GCamera:MicroVideoVersion="1"
 * - GCamera:MicroVideoOffset="<video_byte_count>"
 * - GCamera:MicroVideoPresentationTimestampUs="<us>"
 * - Container:Directory with Primary item (length 0) and MotionPhoto video item (length = video_byte_count).
 */
object MotionPhotoXmpPacker {

    private const val TAG = "MotionPhotoXmpPacker"
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.UTF_8)

    /**
     * Builds the Google Photos-compliant XMP metadata XML string.
     *
     * @param videoLengthBytes The size of the embedded MP4 video in bytes.
     * @param presentationTimestampUs The timestamp in microseconds within the video
     *                                corresponding to the still image shutter moment.
     */
    fun buildXmpMetadata(
        videoLengthBytes: Long,
        presentationTimestampUs: Long
    ): String {
        return "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">\n" +
                "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
                "    <rdf:Description rdf:about=\"\"\n" +
                "        xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n" +
                "        xmlns:Camera=\"http://ns.google.com/photos/1.0/camera/\"\n" +
                "        xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n" +
                "        xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n" +
                "        GCamera:MotionPhoto=\"1\"\n" +
                "        GCamera:MotionPhotoVersion=\"1\"\n" +
                "        GCamera:MotionPhotoPresentationTimestampUs=\"$presentationTimestampUs\"\n" +
                "        Camera:MotionPhoto=\"1\"\n" +
                "        Camera:MotionPhotoVersion=\"1\"\n" +
                "        Camera:MotionPhotoPresentationTimestampUs=\"$presentationTimestampUs\"\n" +
                "        GCamera:MicroVideo=\"1\"\n" +
                "        GCamera:MicroVideoVersion=\"1\"\n" +
                "        GCamera:MicroVideoOffset=\"$videoLengthBytes\"\n" +
                "        GCamera:MicroVideoPresentationTimestampUs=\"$presentationTimestampUs\"\n" +
                "        Camera:MicroVideo=\"1\"\n" +
                "        Camera:MicroVideoVersion=\"1\"\n" +
                "        Camera:MicroVideoOffset=\"$videoLengthBytes\"\n" +
                "        Camera:MicroVideoPresentationTimestampUs=\"$presentationTimestampUs\">\n" +
                "      <Container:Directory>\n" +
                "        <rdf:Seq>\n" +
                "          <rdf:li rdf:parseType=\"Resource\">\n" +
                "            <Container:Item\n" +
                "                Item:Mime=\"image/jpeg\"\n" +
                "                Item:Semantic=\"Primary\"\n" +
                "                Item:Length=\"0\"\n" +
                "                Item:Padding=\"0\">\n" +
                "              <Item:Mime>image/jpeg</Item:Mime>\n" +
                "              <Item:Semantic>Primary</Item:Semantic>\n" +
                "              <Item:Length>0</Item:Length>\n" +
                "              <Item:Padding>0</Item:Padding>\n" +
                "            </Container:Item>\n" +
                "          </rdf:li>\n" +
                "          <rdf:li rdf:parseType=\"Resource\">\n" +
                "            <Container:Item\n" +
                "                Item:Mime=\"video/mp4\"\n" +
                "                Item:Semantic=\"MotionPhoto\"\n" +
                "                Item:Length=\"$videoLengthBytes\"\n" +
                "                Item:Padding=\"0\">\n" +
                "              <Item:Mime>video/mp4</Item:Mime>\n" +
                "              <Item:Semantic>MotionPhoto</Item:Semantic>\n" +
                "              <Item:Length>$videoLengthBytes</Item:Length>\n" +
                "              <Item:Padding>0</Item:Padding>\n" +
                "            </Container:Item>\n" +
                "          </rdf:li>\n" +
                "        </rdf:Seq>\n" +
                "      </Container:Directory>\n" +
                "    </rdf:Description>\n" +
                "  </rdf:RDF>\n" +
                "</x:xmpmeta>\n" +
                "<?xpacket end=\"w\"?>"
    }

    /**
     * Injects the XMP metadata into the JPEG byte stream within an APP1 (0xFFE1) marker segment.
     * Preserves existing EXIF metadata intact, removes any pre-existing XMP segments to avoid
     * duplicate headers, and inserts the Motion Photo XMP segment right after EXIF APP1
     * (or after SOI if no EXIF segment exists).
     */
    fun injectXmpIntoJpeg(
        jpegBytes: ByteArray,
        xmpXml: String
    ): ByteArray {
        val xmpPayload = xmpXml.toByteArray(StandardCharsets.UTF_8)
        val segmentLength = 2 + XMP_HEADER.size + xmpPayload.size
        if (segmentLength > 65535) {
            Log.e(TAG, "XMP packet length ($segmentLength) exceeds maximum JPEG marker segment size (65535)")
            return jpegBytes
        }

        // Verify valid JPEG SOI (0xFF, 0xD8)
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            Log.w(TAG, "Not a valid JPEG byte stream, returning original")
            return jpegBytes
        }

        // Parse existing JPEG segments
        val out = ByteArrayOutputStream(jpegBytes.size + segmentLength + 4)
        out.write(0xFF)
        out.write(0xD8) // SOI

        var pos = 2
        var xmpInserted = false

        while (pos + 4 <= jpegBytes.size) {
            if (jpegBytes[pos] != 0xFF.toByte()) {
                // Not a marker boundary, copy the rest of scan data
                break
            }
            val marker = jpegBytes[pos + 1].toInt() and 0xFF
            // SOS (0xDA) or EOI (0xD9) signals start of image scan data / end
            if (marker == 0xDA || marker == 0xD9) {
                break
            }

            val length = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
            if (length < 2 || pos + 2 + length > jpegBytes.size) {
                break
            }

            val isApp1 = (marker == 0xE1)
            val app1Start = pos + 4
            var isExif = false
            var isExistingXmp = false

            if (isApp1) {
                if (pos + 10 <= jpegBytes.size &&
                    jpegBytes[app1Start] == 'E'.code.toByte() &&
                    jpegBytes[app1Start + 1] == 'x'.code.toByte() &&
                    jpegBytes[app1Start + 2] == 'i'.code.toByte() &&
                    jpegBytes[app1Start + 3] == 'f'.code.toByte()
                ) {
                    isExif = true
                } else if (pos + 4 + XMP_HEADER.size <= jpegBytes.size) {
                    var matchesXmp = true
                    for (k in XMP_HEADER.indices) {
                        if (jpegBytes[app1Start + k] != XMP_HEADER[k]) {
                            matchesXmp = false
                            break
                        }
                    }
                    if (matchesXmp) {
                        isExistingXmp = true
                    }
                }
            }

            if (isExistingXmp) {
                // Skip pre-existing XMP segment to avoid duplicates
                pos += 2 + length
                continue
            }

            // Write this segment (e.g. EXIF APP1 or APP0 or DQT/DHT)
            out.write(jpegBytes, pos, 2 + length)
            pos += 2 + length

            if (isExif && !xmpInserted) {
                // Insert our complete Motion Photo XMP segment right after EXIF APP1
                writeXmpSegment(out, segmentLength, xmpPayload)
                xmpInserted = true
            }
        }

        // If no EXIF was present, insert XMP now before image scan data
        if (!xmpInserted) {
            writeXmpSegment(out, segmentLength, xmpPayload)
            xmpInserted = true
        }

        // Copy remaining bytes (SOS marker and compressed image data through EOI)
        if (pos < jpegBytes.size) {
            out.write(jpegBytes, pos, jpegBytes.size - pos)
        }

        return out.toByteArray()
    }

    private fun writeXmpSegment(out: ByteArrayOutputStream, segmentLength: Int, xmpPayload: ByteArray) {
        out.write(0xFF)
        out.write(0xE1)
        out.write((segmentLength shr 8) and 0xFF)
        out.write(segmentLength and 0xFF)
        out.write(XMP_HEADER)
        out.write(xmpPayload)
    }

    /**
     * Combines the still JPEG photo (with embedded XMP) and the motion MP4 video bytes
     * into a single Motion Photo file.
     *
     * @param stillJpegBytes The full-resolution JPEG image bytes.
     * @param motionMp4Bytes The motion video MP4 clip bytes.
     * @param presentationTimestampUs The synchronization timestamp in microseconds.
     * @return The combined Motion Photo file bytes.
     */
    fun packMotionPhoto(
        stillJpegBytes: ByteArray,
        motionMp4Bytes: ByteArray,
        presentationTimestampUs: Long
    ): ByteArray {
        val xmpXml = buildXmpMetadata(
            videoLengthBytes = motionMp4Bytes.size.toLong(),
            presentationTimestampUs = presentationTimestampUs
        )

        val jpegWithXmp = injectXmpIntoJpeg(stillJpegBytes, xmpXml)

        val out = ByteArrayOutputStream(jpegWithXmp.size + motionMp4Bytes.size)
        out.write(jpegWithXmp)
        out.write(motionMp4Bytes)
        out.flush()

        Log.i(TAG, "Packed Motion Photo: JPEG=${jpegWithXmp.size} bytes, MP4=${motionMp4Bytes.size} bytes, Total=${out.size()} bytes, SyncTimestamp=${presentationTimestampUs}us")
        return out.toByteArray()
    }

    /**
     * Inspects a binary byte array to check if it contains Google Motion Photo markers.
     */
    fun isMotionPhoto(bytes: ByteArray): Boolean {
        if (bytes.size < 100) return false
        val headerSample = String(bytes.copyOfRange(0, minOf(bytes.size, 16384)), StandardCharsets.ISO_8859_1)
        return headerSample.contains("GCamera:MotionPhoto=\"1\"") ||
                headerSample.contains("GCamera:MicroVideo=\"1\"") ||
                headerSample.contains("MicroVideoOffset")
    }

    /**
     * Extracts Motion Photo metadata details from bytes for verification.
     */
    fun extractMotionPhotoDetails(bytes: ByteArray): MotionPhotoDetails? {
        val sampleSize = minOf(bytes.size, 32768)
        val text = String(bytes.copyOfRange(0, sampleSize), StandardCharsets.ISO_8859_1)

        val isMotion = text.contains("MotionPhoto=\"1\"") || text.contains("MicroVideo=\"1\"")
        if (!isMotion) return null

        val offsetMatch = Regex("MicroVideoOffset=\"(\\d+)\"").find(text)
            ?: Regex("<Item:Length>(\\d+)</Item:Length>").findAll(text).toList().getOrNull(1)
        val offset = offsetMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val ptsMatch = Regex("PresentationTimestampUs=\"(\\d+)\"").find(text)
            ?: Regex("MicroVideoPresentationTimestampUs=\"(\\d+)\"").find(text)
        val ptsUs = ptsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        return MotionPhotoDetails(
            isMotionPhoto = true,
            videoOffset = offset,
            presentationTimestampUs = ptsUs,
            totalFileSize = bytes.size.toLong()
        )
    }

    data class MotionPhotoDetails(
        val isMotionPhoto: Boolean,
        val videoOffset: Long,
        val presentationTimestampUs: Long,
        val totalFileSize: Long
    )
}
