package com.example.camera.engine

/**
 * Shared 32-frame handoff budget used by the viewfinder controller and both GPU recording paths.
 */
internal const val CAMERA_FRAME_HANDOFF_BLEND_FRAMES = 32

/**
 * Makes the recording pipeline blend on its own actual encoder-input camera frames.
 *
 * Preview callbacks and encoder callbacks are asynchronous. Sampling the preview alpha directly
 * could skip intermediate alpha values in the saved video, so this tracker waits for the preview
 * to start its blend, then renders one monotonic alpha step for each real target recording frame.
 */
internal class RecordingFrameHandoff {
    private var previewState = PreviewOverlapState()
    private var targetSource: PreviewStreamSource? = null
    private var isHandoffActive = false
    private var isBlendStarted = false
    private var renderedBlendFrameCount = 0

    @Synchronized
    fun update(state: PreviewOverlapState) {
        val previousState = previewState
        val newHandoffStarted = state.isOverlapping &&
            (!previousState.isOverlapping || previousState.activeSource != state.activeSource)

        if (newHandoffStarted) {
            targetSource = state.activeSource
            isHandoffActive = true
            isBlendStarted = false
            renderedBlendFrameCount = 0
        } else if (!state.isOverlapping && isHandoffActive && !isBlendStarted) {
            // Cancel/timeout before the visible fade began: do not leave recording stuck on the
            // outgoing source indefinitely.
            clear()
        }

        previewState = state
    }

    /**
     * Returns the outgoing-layer alpha for one target-source recording frame, or null when no
     * recording handoff is active. A result of 1 holds the old lens during warm-up; values then
     * progress 31/32, 30/32 ... 0/32 over exactly 32 actual target recording frames.
     */
    @Synchronized
    fun outgoingAlphaForFrame(
        source: PreviewStreamSource,
        outgoingFrameAvailable: Boolean
    ): Float? {
        if (!isHandoffActive || targetSource != source || !outgoingFrameAvailable) return null

        if (!isBlendStarted) {
            if (!previewState.isOverlapping || previewState.activeSource != source) {
                clear()
                return null
            }
            if (previewState.overlapProgress <= 0f) return 1f
            isBlendStarted = true
            renderedBlendFrameCount = 0
        }

        renderedBlendFrameCount =
            (renderedBlendFrameCount + 1).coerceAtMost(CAMERA_FRAME_HANDOFF_BLEND_FRAMES)
        val alpha = 1f - renderedBlendFrameCount.toFloat() / CAMERA_FRAME_HANDOFF_BLEND_FRAMES.toFloat()

        if (renderedBlendFrameCount >= CAMERA_FRAME_HANDOFF_BLEND_FRAMES) {
            isHandoffActive = false
            isBlendStarted = false
            targetSource = null
            renderedBlendFrameCount = 0
        }
        return alpha
    }

    private fun clear() {
        targetSource = null
        isHandoffActive = false
        isBlendStarted = false
        renderedBlendFrameCount = 0
    }
}
