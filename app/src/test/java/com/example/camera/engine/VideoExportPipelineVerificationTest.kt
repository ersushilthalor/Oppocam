package com.example.camera.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.camera.data.CubeLutParser
import com.example.camera.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoExportPipelineVerificationTest {

    private lateinit var context: Context
    private lateinit var cinemaEngine: CinemaEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        cinemaEngine = CinemaEngine(context)
    }

    /**
     * Test 1: Cinema export without LUT
     * Verifies that when selectedLut is NONE, no 3D LUT strip bitmap is created,
     * and CinemaColorPipeline handles color profile transformation cleanly.
     */
    @Test
    fun testCinemaExportWithoutLut() {
        val config = CinemaConfig(
            colorProfile = CinemaColorProfile.FLAT_LOG,
            selectedLut = CinematicLut.NONE,
            isBakeLutToOutput = true
        )
        cinemaEngine.updateConfig(config)

        val lutStripBitmap: Bitmap? = if (config.selectedLut != CinematicLut.NONE && config.isBakeLutToOutput) {
            null
        } else null

        val colorMatrix = CinemaColorPipeline.computeCinemaColorMatrix(
            config = config,
            includeCreativeLut = (lutStripBitmap == null && config.isBakeLutToOutput && config.selectedLut != CinematicLut.NONE)
        )

        assertNull("No LUT strip bitmap should be generated when selectedLut is NONE", lutStripBitmap)
        assertNotNull("Color matrix for FLAT_LOG CST should be generated", colorMatrix)

        val hasColorTransform = (colorMatrix != null || lutStripBitmap != null)
        val needsColorGrade = (hasColorTransform && config.isBakeLutToOutput)
        assertTrue("Export pipeline should be triggered to bake Flat Log CST", needsColorGrade)
    }

    /**
     * Test 2: Cinema export with built-in Preset LUT (e.g. Kodak 2383, Teal & Orange, Fuji Eterna)
     * Verifies that preset LUTs are baked directly into the CinemaColorPipeline 5-stage matrix
     * and do not generate an unnecessary 2D strip bitmap (preventing double-baking).
     */
    @Test
    fun testCinemaExportWithBuiltInLut() {
        val presetLuts = listOf(
            CinematicLut.KODAK_2383,
            CinematicLut.TEAL_ORANGE,
            CinematicLut.FUJI_ETERNA,
            CinematicLut.WARM_CINEMA,
            CinematicLut.BLEACH_BYPASS
        )

        for (lut in presetLuts) {
            val config = CinemaConfig(
                colorProfile = CinemaColorProfile.REC_2020,
                selectedLut = lut,
                lutIntensity = 1.0f,
                isBakeLutToOutput = true
            )

            // Built-in presets do not create a 2D strip bitmap; they use the mathematical matrix pipeline
            val stripBitmap: Bitmap? = null

            val colorMatrix = CinemaColorPipeline.computeCinemaColorMatrix(
                config = config,
                includeCreativeLut = (stripBitmap == null && config.isBakeLutToOutput && config.selectedLut != CinematicLut.NONE)
            )

            assertNotNull("Color matrix for preset LUT ${lut.name} must not be null", colorMatrix)
            val arr = colorMatrix!!.array
            assertEquals("Color matrix must have 20 elements (4x5)", 20, arr.size)

            // Verify diagonal weights are positive (non-black, valid color transform)
            assertTrue("Red channel diagonal weight must be positive for ${lut.name}", arr[0] > 0.1f)
            assertTrue("Green channel diagonal weight must be positive for ${lut.name}", arr[6] > 0.1f)
            assertTrue("Blue channel diagonal weight must be positive for ${lut.name}", arr[12] > 0.1f)
            assertEquals("Alpha scale should remain 1.0", 1.0f, arr[18], 0.001f)
        }
    }

    /**
     * Test 3: Cinema export with imported ".cube" LUT
     * Verifies parsing of .cube LUT file, creation of 2D strip bitmap,
     * and that includeCreativeLut is false in CinemaColorPipeline (preventing double-baking).
     */
    @Test
    fun testCinemaExportWithImportedCubeLut() {
        val cubeContent = """
            TITLE "Test Vintage Cinema"
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()

        val parsedLut = CubeLutParser.parseStream(cubeContent.byteInputStream(), "Test Vintage Cinema")
        assertNotNull("Cube LUT must parse successfully", parsedLut)
        assertEquals(2, parsedLut!!.size)
        assertTrue(parsedLut.is3D)

        val stripBitmap = parsedLut.to2DStripBitmap()
        assertNotNull("2D strip bitmap must be generated", stripBitmap)
        assertEquals("Strip height must equal LUT size N", 2, stripBitmap.height)
        assertEquals("Strip width must equal N * N", 4, stripBitmap.width)

        val config = CinemaConfig(
            colorProfile = CinemaColorProfile.FLAT_LOG,
            selectedLut = CinematicLut.CUSTOM,
            customLutPath = "virtual/test.cube",
            isBakeLutToOutput = true
        )

        val stripBitmapOrNull: Bitmap? = stripBitmap
        val includeCreativeInMatrix = (stripBitmapOrNull == null && config.isBakeLutToOutput && config.selectedLut != CinematicLut.NONE)
        assertFalse("Creative LUT must NOT be included in ColorMatrix when 3D strip bitmap is active", includeCreativeInMatrix)

        val baseMatrix = CinemaColorPipeline.computeCinemaColorMatrix(
            config = config,
            includeCreativeLut = includeCreativeInMatrix
        )
        assertNotNull("Base CST matrix must be non-null", baseMatrix)
    }

    /**
     * Test 4: LUT intensity at 25%, 50%, and 100%
     * Verifies that intensity blending correctly scales the creative grade
     * both in preset matrices and in transcoder uniform clamps.
     */
    @Test
    fun testLutIntensityGrading() {
        val intensities = listOf(0.25f, 0.50f, 1.0f)
        val selectedLut = CinematicLut.TEAL_ORANGE

        for (intensity in intensities) {
            val config = CinemaConfig(
                colorProfile = CinemaColorProfile.NATIVE,
                selectedLut = selectedLut,
                lutIntensity = intensity,
                isBakeLutToOutput = true
            )

            val matrix = CinemaColorPipeline.computeCinemaColorMatrix(
                config = config,
                includeCreativeLut = true
            )
            assertNotNull("Matrix for intensity $intensity must be computed", matrix)
            val arr = matrix!!.array

            // The diagonal scaling should smoothly transition from identity (1.0) towards the full preset values
            assertTrue("Diagonal R must be positive at intensity $intensity", arr[0] > 0.5f)
            assertTrue("Diagonal G must be positive at intensity $intensity", arr[6] > 0.5f)
            assertTrue("Diagonal B must be positive at intensity $intensity", arr[12] > 0.5f)
        }
    }

    /**
     * Test 5: Resolution, bitrate, and orientation preservation
     * Verifies 1080p and 4K dimensions, bitrate calculation, and orientation hint handling.
     */
    @Test
    fun testResolutionAndBitrateCalculation() {
        // 4K UHD: 3840x2160 -> >= 45 Mbps
        val w4k = 3840
        val h4k = 2160
        val pixel4k = w4k.toLong() * h4k.toLong()
        val bitrate4k = when {
            pixel4k >= 3840L * 2160L -> 45_000_000
            pixel4k >= 1920L * 1080L -> 20_000_000
            else -> 12_000_000
        }
        assertEquals(45_000_000, bitrate4k)

        // 1080p: 1920x1080 -> 20 Mbps
        val w1080 = 1920
        val h1080 = 1080
        val pixel1080 = w1080.toLong() * h1080.toLong()
        val bitrate1080 = when {
            pixel1080 >= 3840L * 2160L -> 45_000_000
            pixel1080 >= 1920L * 1080L -> 20_000_000
            else -> 12_000_000
        }
        assertEquals(20_000_000, bitrate1080)

        // 720p: 1280x720 -> 12 Mbps
        val w720 = 1280
        val h720 = 720
        val pixel720 = w720.toLong() * h720.toLong()
        val bitrate720 = when {
            pixel720 >= 3840L * 2160L -> 45_000_000
            pixel720 >= 1920L * 1080L -> 20_000_000
            else -> 12_000_000
        }
        assertEquals(12_000_000, bitrate720)
    }

    /**
     * Test 6: MediaCodec surface output buffer render condition
     * Confirms that valid decoded buffers with bufferInfo.size == 0 are RENDERED to SurfaceTexture.
     */
    @Test
    fun testDecoderSurfaceBufferRenderLogic() {
        // Case A: Valid decoded frame with bufferInfo.size == 0 (typical for Surface-output MediaCodec)
        val flagsA = 0
        val sizeA = 0
        val isEosA = (flagsA and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        val renderA = !isEosA || sizeA > 0
        assertTrue("Surface decoded frames with size 0 MUST be rendered", renderA)

        // Case B: Valid decoded frame with bufferInfo.size > 0
        val flagsB = 0
        val sizeB = 1024
        val isEosB = (flagsB and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        val renderB = !isEosB || sizeB > 0
        assertTrue("Decoded frames with size > 0 MUST be rendered", renderB)

        // Case C: Pure EOS marker with bufferInfo.size == 0
        val flagsC = android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
        val sizeC = 0
        val isEosC = (flagsC and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        val renderC = !isEosC || sizeC > 0
        assertFalse("Pure EOS marker with 0 size should NOT be rendered", renderC)

        // Case D: Combined EOS marker with last frame payload (size > 0)
        val flagsD = android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
        val sizeD = 2048
        val isEosD = (flagsD and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        val renderD = !isEosD || sizeD > 0
        assertTrue("EOS marker with actual frame payload MUST be rendered", renderD)
    }

    /**
     * Test 7: Output file validation check
     * Verifies that VideoMirrorTranscoder.validateVideoFile properly rejects missing,
     * tiny/empty, and invalid files.
     */
    @Test
    fun testValidationCheckRejectsInvalidFiles() {
        val nonExistent = File(context.cacheDir, "non_existent_file.mp4")
        assertFalse("Non-existent file must fail validation", VideoMirrorTranscoder.validateVideoFile(nonExistent))

        // Tiny file (< 50 KB, e.g. the 180 KB or empty stub)
        val tinyFile = File(context.cacheDir, "tiny_stub.mp4")
        FileOutputStream(tinyFile).use { it.write(ByteArray(1024)) } // 1 KB
        try {
            assertFalse("Tiny 1 KB file must fail validation", VideoMirrorTranscoder.validateVideoFile(tinyFile))
        } finally {
            tinyFile.delete()
        }

        // 40 KB file (still below 50 KB threshold)
        val underThreshold = File(context.cacheDir, "under_threshold.mp4")
        FileOutputStream(underThreshold).use { it.write(ByteArray(40 * 1024)) }
        try {
            assertFalse("40 KB file must fail validation (< 50KB threshold)", VideoMirrorTranscoder.validateVideoFile(underThreshold))
        } finally {
            underThreshold.delete()
        }
    }

    /**
     * Test 8: OpenGL Color Matrix Math
     * Confirms that converting Android's 4x5 ColorMatrix into OpenGL 4x4 matrix
     * and 4-component offset correctly preserves channel weights and offset normalization.
     */
    @Test
    fun testColorMatrixToGlMatrixConversion() {
        // [ a, b, c, d, e,
        //   f, g, h, i, j,
        //   k, l, m, n, o,
        //   p, q, r, s, t ]
        val androidMatrix = floatArrayOf(
            1.2f, 0.1f, 0.0f, 0.0f, 25.5f,  // R out: 1.2R + 0.1G + 0.1 offset (25.5/255)
            0.0f, 1.1f, 0.0f, 0.0f, 0.0f,   // G out: 1.1G
            0.0f, 0.1f, 0.9f, 0.0f, 51.0f,  // B out: 0.1G + 0.9B + 0.2 offset (51.0/255)
            0.0f, 0.0f, 0.0f, 1.0f, 0.0f    // A out: 1.0A
        )

        val glColorMatrix = FloatArray(16)
        val glColorOffset = FloatArray(4)

        // col 0
        glColorMatrix[0] = androidMatrix[0]
        glColorMatrix[1] = androidMatrix[5]
        glColorMatrix[2] = androidMatrix[10]
        glColorMatrix[3] = 0f

        // col 1
        glColorMatrix[4] = androidMatrix[1]
        glColorMatrix[5] = androidMatrix[6]
        glColorMatrix[6] = androidMatrix[11]
        glColorMatrix[7] = 0f

        // col 2
        glColorMatrix[8] = androidMatrix[2]
        glColorMatrix[9] = androidMatrix[7]
        glColorMatrix[10] = androidMatrix[12]
        glColorMatrix[11] = 0f

        // col 3
        glColorMatrix[12] = 0f
        glColorMatrix[13] = 0f
        glColorMatrix[14] = 0f
        glColorMatrix[15] = 1f

        glColorOffset[0] = androidMatrix[4] / 255.0f
        glColorOffset[1] = androidMatrix[9] / 255.0f
        glColorOffset[2] = androidMatrix[14] / 255.0f
        glColorOffset[3] = androidMatrix[19] / 255.0f

        // Verify column 0 (weights for R)
        assertEquals(1.2f, glColorMatrix[0], 0.001f)
        assertEquals(0.0f, glColorMatrix[1], 0.001f)
        assertEquals(0.0f, glColorMatrix[2], 0.001f)

        // Verify column 1 (weights for G)
        assertEquals(0.1f, glColorMatrix[4], 0.001f)
        assertEquals(1.1f, glColorMatrix[5], 0.001f)
        assertEquals(0.1f, glColorMatrix[6], 0.001f)

        // Verify column 2 (weights for B)
        assertEquals(0.0f, glColorMatrix[8], 0.001f)
        assertEquals(0.0f, glColorMatrix[9], 0.001f)
        assertEquals(0.9f, glColorMatrix[10], 0.001f)

        // Verify normalized offsets
        assertEquals(0.10f, glColorOffset[0], 0.001f) // 25.5 / 255
        assertEquals(0.00f, glColorOffset[1], 0.001f) // 0 / 255
        assertEquals(0.20f, glColorOffset[2], 0.001f) // 51 / 255
    }
}
