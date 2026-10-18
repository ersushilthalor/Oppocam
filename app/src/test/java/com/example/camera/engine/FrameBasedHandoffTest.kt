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
    fun preparedTargetFramesAreObservedBeforeTheVisibleHandoffBegins() {
        val controller = ZoomTransitionController()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        controller.prepareFrameBasedHandoff(
            fromSource = PreviewStreamSource.MAIN,
            targetSource = PreviewStreamSource.ULTRAWIDE
        )
        // Collect actual standby frames while Main remains the visible source.
        controller.onPreviewFrameAvailable(PreviewStreamSource.ULTRAWIDE)
        controller.onPreviewFrameAvailable(PreviewStreamSource.ULTRAWIDE)

        controller.beginFrameBasedHandoff(
            fromSource = PreviewStreamSource.MAIN,
            targetSource = PreviewStreamSource.ULTRAWIDE,
            scope = scope
        )
        assertTrue(controller.previewOverlapState.value.isOverlapping)
        assertEquals(0f, controller.previewOverlapState.value.overlapProgress, 0.0001f)

        // Previously collected fresh frames satisfy warm-up; the next real target frame starts
        // the blend instead of spending two more frames waiting at the switch boundary.
        controller.onPreviewFrameAvailable(PreviewStreamSource.ULTRAWIDE)
        assertTrue(controller.previewOverlapState.value.overlapProgress > 0f)
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
        repeat(15) {
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
    fun reverseHandoffAcceleratesFadeWhenUltraWideFovMovesWiderThanMain() {
        val atOneX = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 1,
            targetSource = PreviewStreamSource.ULTRAWIDE,
            currentZoom = 1.0f,
            previousProgress = 0f
        )
        val atPointEightX = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 1,
            targetSource = PreviewStreamSource.ULTRAWIDE,
            currentZoom = 0.8f,
            previousProgress = 0f
        )
        assertTrue("Reverse handoff should fade faster once Main cannot match the wider FOV", atPointEightX > atOneX)

        val nextFrame = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 2,
            targetSource = PreviewStreamSource.ULTRAWIDE,
            currentZoom = 0.7f,
            previousProgress = atPointEightX
        )
        assertTrue("Reverse handoff alpha must remain monotonic", nextFrame >= atPointEightX)
        assertTrue("Fade must finish before a large FOV mismatch remains visible", nextFrame > 0.8f)
    }

    @Test
    fun forwardHandoffShortensBlendAsMainFovMovesAwayFromSwitchFov() {
        val atSwitch = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 1,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.0f,
            previousProgress = 0f
        )
        val atOnePointZeroFive = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 2,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.05f,
            previousProgress = atSwitch
        )
        val atOnePointOne = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 3,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.10f,
            previousProgress = atOnePointZeroFive
        )
        val atOnePointFifteen = ZoomTransitionController.calculateFrameHandoffProgress(
            incomingFrameCount = 4,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.15f,
            previousProgress = atOnePointOne
        )

        assertTrue("Blend should begin gently at the shared switch FOV", atSwitch > 0f && atSwitch < 0.05f)
        assertTrue("Alpha must increase monotonically", atOnePointZeroFive > atSwitch)
        assertTrue("Alpha must increase monotonically", atOnePointOne > atOnePointZeroFive)
        assertTrue("Alpha must increase monotonically", atOnePointFifteen > atOnePointOne)
        assertTrue(
            "Blend should be nearly complete before large FOV mismatch creates visible double edges",
            atOnePointFifteen > 0.85f && atOnePointFifteen < 1f
        )
    }

    @Test
    fun handoffUsesTwoWarmupFramesThenSixteenBlendFramesAndIgnoresOutgoingFrames() {
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

        // Wait for two fresh Main frames before starting the actual 16-frame blend.
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
        // 16-frame blend rather than spending two more frames waiting.
        controller.onPreviewFrameAvailable(PreviewStreamSource.MAIN)
        assertTrue(controller.previewOverlapState.value.overlapProgress > 0f)
        assertTrue(controller.previewOverlapState.value.isOverlapping)

        repeat(14) {
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
}
