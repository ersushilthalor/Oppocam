package com.example.camera.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBasedHandoffTest {

    @Test
    fun handoffUsesSixteenIncomingFramesAndIgnoresOutgoingFrames() {
        val controller = ZoomTransitionController()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        controller.beginFrameBasedHandoff(
            fromSource = PreviewStreamSource.ULTRAWIDE,
            targetSource = PreviewStreamSource.MAIN,
            scope = scope
        )

        assertTrue(controller.previewOverlapState.value.isOverlapping)
        assertEquals(0f, controller.previewOverlapState.value.overlapProgress, 0.0001f)

        // The outgoing stream must not advance the incoming-frame crossfade.
        controller.onPreviewFrameAvailable(PreviewStreamSource.ULTRAWIDE)
        assertEquals(0f, controller.previewOverlapState.value.overlapProgress, 0.0001f)

        var previousProgress = 0f
        repeat(15) {
            controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
            val state = controller.previewOverlapState.value
            assertTrue("Handoff must remain active before the sixteenth target frame", state.isOverlapping)
            assertTrue("Crossfade progress must increase on each real target frame", state.overlapProgress > previousProgress)
            assertTrue("Crossfade must not finish before the sixteenth target frame", state.overlapProgress < 1f)
            assertEquals("Outgoing preview remains underneath the incoming preview", 1f, state.ultraWideAlpha, 0.0001f)
            previousProgress = state.overlapProgress
        }

        controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
        val completed = controller.previewOverlapState.value
        assertFalse("Handoff should finish on the sixteenth target frame", completed.isOverlapping)
        assertEquals(PreviewStreamSource.MAIN, completed.activeSource)
        assertEquals(1f, completed.mainAlpha, 0.0001f)
        assertEquals(0f, completed.ultraWideAlpha, 0.0001f)
    }
}
