package com.example.camera.ui

import com.example.camera.model.CameraMode
import com.example.camera.model.UiTemplateType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewfinderTemplateSizingTest {

    @Test
    fun fullScreenVideoPreviewIsEnabledForOppoAndOneUi() {
        assertTrue(shouldUseFullScreenVideoViewfinder(UiTemplateType.OPPO, CameraMode.VIDEO))
        assertTrue(shouldUseFullScreenVideoViewfinder(UiTemplateType.SAMSUNG, CameraMode.VIDEO))
    }

    @Test
    fun fullScreenVideoPreviewDoesNotChangeOtherTemplatesOrModes() {
        assertFalse(shouldUseFullScreenVideoViewfinder(UiTemplateType.CUSTOM, CameraMode.VIDEO))
        assertFalse(shouldUseFullScreenVideoViewfinder(UiTemplateType.OPPO, CameraMode.PHOTO))
        assertFalse(shouldUseFullScreenVideoViewfinder(UiTemplateType.SAMSUNG, CameraMode.PHOTO))
        assertFalse(shouldUseFullScreenVideoViewfinder(UiTemplateType.OPPO, CameraMode.CINEMA))
    }
}
