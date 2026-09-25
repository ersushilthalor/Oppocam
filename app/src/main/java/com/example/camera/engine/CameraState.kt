package com.example.camera.engine

/**
 * Camera hardware lifecycle state tracking adapted from PhotonCamera's architecture.
 */
enum class CameraLifecycleState {
    UNINITIALIZED,
    CLOSED,
    OPENING,
    OPENED,
    CONFIGURING_SESSION,
    ACTIVE_PREVIEW,
    SWITCHING,
    ERROR
}

/**
 * Switching strategy for a given lens target:
 * - INDEPENDENT_DEVICE: Full teardown of previous camera and opening of the target camera device (e.g. Back <-> Front, or separate Aux ID)
 * - LOGICAL_PHYSICAL_STREAM: Reconfiguring session on the same logical camera using OutputConfiguration.setPhysicalCameraId()
 * - LOGICAL_ZOOM: Adjusting continuous zoom ratio within the same logical camera
 */
enum class LensSwitchStrategy {
    INDEPENDENT_DEVICE,
    LOGICAL_PHYSICAL_STREAM,
    LOGICAL_ZOOM
}
