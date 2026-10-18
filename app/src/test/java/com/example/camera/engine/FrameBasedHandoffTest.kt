package com.example.camera.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBasedHandoffTest {

    @Test
    fun frameBasedHandoffIsRestrictedToHalfXAndTwoOrThreeXEndpoints() {
        assertTrue(ZoomTransitionController.isFrameBasedHandoffEligible(0.5f, 2.0f))
        assertTrue(ZoomTransitionController.isFrameBasedHandoffEligible(0.5f, 3.0f))
        assertTrue(ZoomTransitionController.isFrameBasedHandoffEligible(2.0f, 0.5f))
        assertTrue(ZoomTransitionController.isFrameBasedHandoffEligible(3.0f, 0.5f))

        assertFalse(ZoomTransitionController.isFrameBasedHandoffEligible(0.5f, 1.0f))
        assertFalse(ZoomTransitionController.isFrameBasedHandoffEligible(1.0f, 0.5f))
        assertFalse(ZoomTransitionController.isFrameBasedHandoffEligible(0.7f, 2.0f))
        assertFalse(ZoomTransitionController.isFrameBasedHandoffEligible(2.0f, 1.0f))

        assertTrue(ZoomTransitionController.isHalfXToTelephotoPresetTransition(0.5f, 2.0f))
        assertFalse(ZoomTransitionController.isHalfXToTelephotoPresetTransition(0.5f, 1.0f))
    }

    @Test
    fun reverseHandoffBlendsOnlyIncomingUltraWideFrames() {
        val controller = ZoomTransitionController()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        controller.beginFrameBasedHandoff(
            fromSource = PreviewStreamSource.MAIN,
            targetSource = PreviewStreamSource.ULTRAWIDE,
            scope = scope
        )

        assertTrue(controller.previewOverlapState.value.isOverlapping)
        assertEquals(0f, controller.previewOverlapState.value.overlapProgress, 0.0001f)

        // Two fresh target frames warm up the Camera2 crop/zoom request while the outgoing
        // preview remains visible; do not blend frames that may still have mismatched FOV.
        repeat(2) {
            controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
            controller.onPreviewFrameAvailable(PreviewStreamSource.ULTRAWIDE)
            val warmup = controller.previewOverlapState.value
            assertTrue(warmup.isOverlapping)
            assertEquals(0f, warmup.overlapProgress, 0.0001f)
            assertEquals(1f, warmup.mainAlpha, 0.0001f)
            assertEquals(0f, warmup.ultraWideAlpha, 0.0001f)
        }

        var previousProgress = 0f
        repeat(31) {
            controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
            assertEquals(0f, controller.previewOverlapState.value.overlapProgress, 0.0001f)

            controller.onPreviewFrameAvailable(PreviewStreamSource.ULTRAWIDE)
            val state = controller.previewOverlapState.value
            assertTrue(state.isOverlapping)
            assertTrue("Reverse handoff must progress on new Ultra-Wide frames", state.overlapProgress > previousProgress)
            assertTrue(state.overlapProgress < 1f)
            assertEquals(1f, state.mainAlpha, 0.0001f)
            previousProgress = state.overlapProgress
        }

        controller.onPreviewFrameAvailable(PreviewStreamSource.ULTRAWIDE)
        val completed = controller.previewOverlapState.value
        assertFalse(completed.isOverlapping)
        assertEquals(PreviewStreamSource.ULTRAWIDE, completed.activeSource)
        assertEquals(0f, completed.mainAlpha, 0.0001f)
        assertEquals(1f, completed.ultraWideAlpha, 0.0001f)
    }

    @Test
    fun everyIncomingFrameAdvancesBlendByExactlyOneOfThirtyTwoSteps() {
        val firstAtOneX = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 1,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.0f
        )
        val firstAwayFromOneX = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 1,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.15f
        )
        val frame31 = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 31,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.15f,
            previousProgress = 30f / 32f
        )
        val frame32 = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 32,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.15f,
            previousProgress = frame31
        )

        assertEquals(1f / 32f, firstAtOneX, 0.0001f)
        assertEquals("FOV changes must not skip handoff frames", firstAtOneX, firstAwayFromOneX, 0.0001f)
        assertEquals(31f / 32f, frame31, 0.0001f)
        assertTrue("Frame 31 must not finish the blend", frame31 < 1f)
        assertEquals(1f, frame32, 0.0001f)
    }

    @Test
    fun frameProgressNeverRegressesAndUsesOnlyIncomingFrames() {
        val afterTwoFrames = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 2,
            targetSource = PreviewStreamSource.ULTRAWIDE,
            currentZoom = 0.8f
        )
        val withLaterIncomingFrame = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 3,
            targetSource = PreviewStreamSource.ULTRAWIDE,
            currentZoom = 0.7f,
            previousProgress = afterTwoFrames
        )
        assertEquals(2f / 32f, afterTwoFrames, 0.0001f)
        assertEquals(3f / 32f, withLaterIncomingFrame, 0.0001f)
        assertTrue(withLaterIncomingFrame > afterTwoFrames)
    }

    @Test
    fun handoffUsesTwoWarmupFramesThenThirtyTwoBlendFramesAndIgnoresOutgoingFrames() {
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

        // Wait for two fresh Main frames before starting the actual 32-frame blend.
        repeat(2) {
            controller.onPreviewFrameAvailable(PreviewStreamSource.ULTRAWIDE)
            controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
            val warmup = controller.previewOverlapState.value
            assertTrue(warmup.isOverlapping)
            assertEquals(0f, warmup.overlapProgress, 0.0001f)
            assertEquals(0f, warmup.mainAlpha, 0.0001f)
            assertEquals(1f, warmup.ultraWideAlpha, 0.0001f)
            assertEquals(PreviewStreamSource.ULTRAWIDE, warmup.topSource)
        }

        var previousProgress = 0f
        repeat(31) {
            controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
            val state = controller.previewOverlapState.value
            assertTrue("Handoff must remain active before the thirty-second target frame", state.isOverlapping)
            assertTrue("Crossfade progress must increase on each real target frame", state.overlapProgress > previousProgress)
            assertTrue("Crossfade must not finish before the thirty-second target frame", state.overlapProgress < 1f)
            assertEquals("Outgoing preview remains underneath the incoming preview", 1f, state.ultraWideAlpha, 0.0001f)
            previousProgress = state.overlapProgress
        }

        controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
        val completed = controller.previewOverlapState.value
        assertFalse("Handoff should finish on the thirty-second target frame", completed.isOverlapping)
        assertEquals(PreviewStreamSource.MAIN, completed.activeSource)
        assertEquals(1f, completed.mainAlpha, 0.0001f)
        assertEquals(0f, completed.ultraWideAlpha, 0.0001f)
    }

    @Test
    fun prewarmedIncomingFramesStartBlendImmediatelyAfterSwitchPoint() {
        val controller = ZoomTransitionController()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        controller.prepareFrameBasedHandoff(
            fromSource = PreviewStreamSource.ULTRAWIDE,
            targetSource = PreviewStreamSource.MAIN
        )

        // The target stream can be running while the visible lens is still Ultra-Wide.
        repeat(4) {
            controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
        }
        assertFalse("Pre-warming must not start visible overlap before the switch point",
            controller.previewOverlapState.value.isOverlapping)

        controller.beginFrameBasedHandoff(
            fromSource = PreviewStreamSource.ULTRAWIDE,
            targetSource = PreviewStreamSource.MAIN,
            scope = scope
        )
        assertTrue(controller.previewOverlapState.value.isOverlapping)
        assertEquals(0f, controller.previewOverlapState.value.overlapProgress, 0.0001f)

        // Fresh standby frames satisfy warm-up. The first post-switch target frame starts the
        // 32-frame blend rather than spending two more frames waiting.
        controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
        assertTrue(controller.previewOverlapState.value.overlapProgress > 0f)
        assertTrue(controller.previewOverlapState.value.isOverlapping)

        repeat(30) {
            controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
            assertTrue(controller.previewOverlapState.value.isOverlapping)
        }
        controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
        val completed = controller.previewOverlapState.value
        assertFalse(completed.isOverlapping)
        assertEquals(PreviewStreamSource.MAIN, completed.activeSource)
        assertEquals(1f, completed.mainAlpha, 0.0001f)
        assertEquals(0f, completed.ultraWideAlpha, 0.0001f)
    }

    @Test
    fun recordingHandoffRendersThirtyTwoBlendFramesEvenIfPreviewFinishesFirst() {
        val recording = RecordingFrameHandoff()
        recording.update(
            PreviewOverlapState(
                isOverlapping = true,
                activeSource = PreviewStreamSource.MAIN,
                overlapProgress = 0f
            )
        )

        // Before the preview starts blending, the recorded stream must hold the outgoing lens.
        assertEquals(1f, recording.outgoingAlphaForFrame(PreviewStreamSource.MAIN, true)!!, 0.0001f)

        recording.update(
            PreviewOverlapState(
                isOverlapping = true,
                activeSource = PreviewStreamSource.MAIN,
                overlapProgress = 1f / 32f
            )
        )

        for (frame in 1..32) {
            val alpha = recording.outgoingAlphaForFrame(PreviewStreamSource.MAIN, true)
            assertTrue("Recording should report every one of its 32 handoff frames", alpha != null)
            assertEquals(1f - frame / 32f, alpha!!, 0.0001f)
            if (frame == 4) {
                // The UI can finish on its own clock; recording must finish all 32 real frames.
                recording.update(
                    PreviewOverlapState(
                        isOverlapping = false,
                        activeSource = PreviewStreamSource.MAIN
                    )
                )
            }
        }
        assertTrue(recording.outgoingAlphaForFrame(PreviewStreamSource.MAIN, true) == null)
    }

}
