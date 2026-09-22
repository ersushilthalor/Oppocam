package com.example.camera.engine.night

import android.graphics.Bitmap
import android.graphics.Color
import com.example.camera.model.NightConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UltraNightFusionEngineTest {

    private fun createTestBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { color }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    @Test
    fun testProcessUltraNightFramesCompletesSuccessfully() = runBlocking {
        val engine = UltraNightFusionEngine()
        val width = 64
        val height = 64

        val b1 = createTestBitmap(width, height, Color.rgb(30, 30, 40))
        val b2 = createTestBitmap(width, height, Color.rgb(60, 60, 70))
        val b3 = createTestBitmap(width, height, Color.rgb(20, 20, 30))

        val f1 = CapturedNightFrame(0, b1, 10_000_000L, 800, System.nanoTime(), BracketExposureType.SHORT)
        val f2 = CapturedNightFrame(1, b2, 33_333_333L, 400, System.nanoTime(), BracketExposureType.MEDIUM)
        val f3 = CapturedNightFrame(2, b3, 66_666_666L, 200, System.nanoTime(), BracketExposureType.LONG)

        val aligned = listOf(
            AlignedNightFrame(0, 0, 0, 0f, 0f, isReference = false, motionWeights = FloatArray(0)),
            AlignedNightFrame(1, 0, 0, 0f, 0f, isReference = true, motionWeights = FloatArray(0)),
            AlignedNightFrame(2, 0, 0, 0f, 0f, isReference = false, motionWeights = FloatArray(0))
        )

        var progressReported = 0f
        val output = engine.processUltraNightFrames(
            frames = listOf(f1, f2, f3),
            alignedData = aligned,
            config = NightConfig(shadowLift = 1.35f),
            onProgress = { p -> progressReported = p }
        )

        assertNotNull("Output bitmap must not be null", output)
        assertEquals(width, output.width)
        assertEquals(height, output.height)
        assertEquals(1.0f, progressReported, 0.001f)

        // Non-reference frames should be recycled immediately to preserve heap
        assertTrue("f1 bitmap should be recycled after accumulation", b1.isRecycled)
        assertTrue("f3 bitmap should be recycled after accumulation", b3.isRecycled)
        // Reference frame bitmap is preserved
        assertFalse("Reference frame bitmap should not be recycled during engine run", b2.isRecycled)

        output.recycle()
        b2.recycle()
    }

    @Test
    fun testAlignmentEngineAndFusionPipeline() = runBlocking {
        val alignmentEngine = NightAlignmentEngine()
        val fusionEngine = UltraNightFusionEngine()
        val width = 64
        val height = 64

        val b1 = createTestBitmap(width, height, Color.rgb(40, 40, 50))
        val b2 = createTestBitmap(width, height, Color.rgb(80, 80, 90))

        val f1 = CapturedNightFrame(0, b1, 20_000_000L, 400, System.nanoTime(), BracketExposureType.MEDIUM)
        val f2 = CapturedNightFrame(1, b2, 40_000_000L, 400, System.nanoTime(), BracketExposureType.LONG)
        val frames = listOf(f1, f2)

        val refIdx = alignmentEngine.selectOptimalReferenceFrame(frames)
        assertEquals(0, refIdx)

        val aligned = alignmentEngine.alignFrames(frames, refIdx)
        assertEquals(2, aligned.size)
        assertTrue(aligned[0].isReference)
        assertFalse(aligned[1].isReference)

        val output = fusionEngine.processUltraNightFrames(
            frames = frames,
            alignedData = aligned,
            config = NightConfig(shadowLift = 1.2f)
        )
        assertNotNull(output)
        assertEquals(width, output.width)
        assertEquals(height, output.height)

        output.recycle()
        if (!b1.isRecycled) b1.recycle()
        if (!b2.isRecycled) b2.recycle()
    }
}
