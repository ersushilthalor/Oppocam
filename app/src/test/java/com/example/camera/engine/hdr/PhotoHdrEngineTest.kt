package com.example.camera.engine.hdr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.camera.engine.FrameLuminanceStats
import com.example.camera.model.FlashMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoHdrEngineTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun createTestJpeg(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { color }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    @Test
    fun testPlannerDecisions() {
        val planner = HdrCapturePlanner()

        // 1. Flash Active -> SINGLE_FRAME
        val flashPlan = planner.planCapture(
            chars = null,
            lastResult = null,
            flashMode = FlashMode.ON,
            stats = FrameLuminanceStats(p1 = 0.01f, p5 = 0.05f, p18 = 0.20f, p50 = 0.50f, p95 = 0.90f, p99 = 0.98f, dynamicRange = 0.90f, isHighContrast = true),
            gyroEngine = null
        )
        assertEquals(HdrBracketType.SINGLE_FRAME, flashPlan.bracketType)

        // 2. Uniform lighting -> SINGLE_FRAME
        val flatPlan = planner.planCapture(
            chars = null,
            lastResult = null,
            flashMode = FlashMode.OFF,
            stats = FrameLuminanceStats(p1 = 0.15f, p5 = 0.20f, p18 = 0.35f, p50 = 0.50f, p95 = 0.70f, p99 = 0.80f, dynamicRange = 0.40f, isHighContrast = false),
            gyroEngine = null
        )
        assertEquals(HdrBracketType.SINGLE_FRAME, flatPlan.bracketType)

        // 3. Blown highlights only -> TWO_FRAME_HIGHLIGHT
        val highlightPlan = planner.planCapture(
            chars = null,
            lastResult = null,
            flashMode = FlashMode.OFF,
            stats = FrameLuminanceStats(p1 = 0.10f, p5 = 0.18f, p18 = 0.35f, p50 = 0.55f, p95 = 0.88f, p99 = 0.96f, dynamicRange = 0.65f, isHighContrast = false),
            gyroEngine = null
        )
        assertEquals(HdrBracketType.TWO_FRAME_HIGHLIGHT, highlightPlan.bracketType)
        assertEquals(2, highlightPlan.specs.size)
        assertEquals(FrameRole.REFERENCE_BASE, highlightPlan.specs[0].role)
        assertEquals(FrameRole.SHORT_HIGHLIGHT, highlightPlan.specs[1].role)

        // 4. Extreme Dynamic Range (Deep shadows + blown highlights) -> THREE_FRAME_FULL
        val extremeHdrPlan = planner.planCapture(
            chars = null,
            lastResult = null,
            flashMode = FlashMode.OFF,
            stats = FrameLuminanceStats(p1 = 0.01f, p5 = 0.05f, p18 = 0.15f, p50 = 0.45f, p95 = 0.92f, p99 = 0.99f, dynamicRange = 0.85f, isHighContrast = true),
            gyroEngine = null
        )
        assertEquals(HdrBracketType.THREE_FRAME_FULL, extremeHdrPlan.bracketType)
        assertEquals(3, extremeHdrPlan.specs.size)
    }

    @Test
    fun testAlignmentAndMotionDetection() {
        val aligner = HdrFrameAligner()
        val motionDetector = HdrMotionDetector()

        val bmp1 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val luma1 = aligner.extractDownscaledLuminance(bmp1, HdrFrameAligner.ALIGN_GRID_WIDTH, HdrFrameAligner.ALIGN_GRID_HEIGHT)
        val luma2 = aligner.extractDownscaledLuminance(bmp2, HdrFrameAligner.ALIGN_GRID_WIDTH, HdrFrameAligner.ALIGN_GRID_HEIGHT)

        val alignResult = aligner.alignFrames(bmp1, bmp2, 0.05f, 0f, 0f)
        assertTrue(alignResult.isAligned)

        val motionMask = motionDetector.detectMotion(luma1, luma2, 0f, alignResult)
        assertNotNull(motionMask)
        assertEquals(HdrMotionDetector.MASK_GRID_WIDTH, motionMask.width)
        assertEquals(HdrMotionDetector.MASK_GRID_HEIGHT, motionMask.height)

        bmp1.recycle()
        bmp2.recycle()
    }

    @Test
    fun testRadianceFusionAndToneMapping() {
        val fusion = HdrRadianceFusion()
        val toneMapper = HdrToneMapper()
        val highlightRec = HdrHighlightRecovery()
        val shadowRec = HdrShadowRecovery()
        val colorRenderer = HdrColorRenderer()
        val detailProcessor = HdrDetailProcessor()

        val rgb = FloatArray(3)

        // Fuse a bright highlight pixel
        fusion.fusePixelLinear(
            baseR = 250, baseG = 250, baseB = 250,
            shortR = 180, shortG = 180, shortB = 180,
            shortEvOffset = -1.7f,
            longR = null, longG = null, longB = null,
            longEvOffset = 0f,
            motionConfidence = 0f,
            outRgb = rgb
        )

        assertTrue("Fused radiance must be positive", rgb[0] > 0f)

        highlightRec.recoverHighlights(rgb, 180, 180, 180)
        shadowRec.recoverShadows(rgb, 1.15f)
        toneMapper.toneMapPixel(rgb, 0.5f)
        colorRenderer.renderColor(rgb)

        val neighbors = floatArrayOf(0.45f, 0.45f, 0.45f, 0.45f)
        detailProcessor.processDetail(rgb, neighbors, 100)

        // Output should be normalized within [0.0, 1.0]
        for (i in 0..2) {
            assertTrue("RGB[$i] must be >= 0", rgb[i] >= 0f)
            assertTrue("RGB[$i] must be <= 1.0", rgb[i] <= 1.0f)
        }
    }

    @Test
    fun testPhotoHdrEngineFullProcessPipeline() = runBlocking {
        val engine = PhotoHdrEngine(context)
        val w = 64
        val h = 64

        val baseBytes = createTestJpeg(w, h, Color.rgb(120, 120, 120))
        val shortBytes = createTestJpeg(w, h, Color.rgb(60, 60, 60))
        val longBytes = createTestJpeg(w, h, Color.rgb(180, 180, 180))

        val frames = listOf(
            HdrInputFrame(baseBytes, FrameRole.REFERENCE_BASE, 0f, 33_333_333L, 100, 1000L),
            HdrInputFrame(shortBytes, FrameRole.SHORT_HIGHLIGHT, -1.7f, 10_000_000L, 100, 1050L),
            HdrInputFrame(longBytes, FrameRole.LONG_SHADOW, 1.4f, 80_000_000L, 100, 1100L)
        )

        val plan = HdrCapturePlan(
            bracketType = HdrBracketType.THREE_FRAME_FULL,
            specs = listOf(
                HdrExposureSpec(FrameRole.REFERENCE_BASE, 0f, 0, true),
                HdrExposureSpec(FrameRole.SHORT_HIGHLIGHT, -1.7f, -5, false),
                HdrExposureSpec(FrameRole.LONG_SHADOW, 1.4f, 4, false)
            ),
            reason = "Test 3-frame HDR"
        )

        val resultBytes = engine.processHdrCapture(frames, plan, 95)
        assertNotNull(resultBytes)
        assertTrue(resultBytes.isNotEmpty())

        val resultBmp = android.graphics.BitmapFactory.decodeByteArray(resultBytes, 0, resultBytes.size)
        assertNotNull("Resulting JPEG should decode into a valid bitmap", resultBmp)
        assertEquals(w, resultBmp.width)
        assertEquals(h, resultBmp.height)
        resultBmp.recycle()
    }

    @Test
    fun testFailsafeReturnsBaseFrameOnCorruptInput() = runBlocking {
        val engine = PhotoHdrEngine(context)
        val w = 32
        val h = 32

        val baseBytes = createTestJpeg(w, h, Color.rgb(100, 100, 100))
        val corruptBytes = byteArrayOf(1, 2, 3, 4, 5) // Invalid JPEG

        val frames = listOf(
            HdrInputFrame(baseBytes, FrameRole.REFERENCE_BASE, 0f, 33_333_333L, 100, 1000L),
            HdrInputFrame(corruptBytes, FrameRole.SHORT_HIGHLIGHT, -1.7f, 10_000_000L, 100, 1050L)
        )

        val plan = HdrCapturePlan(
            bracketType = HdrBracketType.TWO_FRAME_HIGHLIGHT,
            specs = listOf(
                HdrExposureSpec(FrameRole.REFERENCE_BASE, 0f, 0, true),
                HdrExposureSpec(FrameRole.SHORT_HIGHLIGHT, -1.7f, -5, false)
            ),
            reason = "Failsafe test"
        )

        val resultBytes = engine.processHdrCapture(frames, plan, 95)
        assertNotNull(resultBytes)
        // Corrupt secondary frame will proceed with base or return base bytes directly without throwing exception
        assertTrue(resultBytes.isNotEmpty())
    }
}
