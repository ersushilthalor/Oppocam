package com.example.camera.videopipeline

/**
 * Supported video processing pipelines in Video Mode.
 *
 * Provides:
 * - [NORMAL]: Default standard video mode pipeline (completely unadulterated sensor output).
 * - [CUSTOM]: Dedicated "Custom Pipeline" based exactly on Cinema Mode Natural Profile
 *   (Rec.2020 Log, neutral rendering, clean highlight & shadow response without LUT/filter look).
 */
enum class VideoPipelineType(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String
) {
    /**
     * Standard default video pipeline without specialized computational re-grading.
     * When Custom Pipeline is disabled, this pipeline remains completely unchanged.
     */
    NORMAL(
        id = "normal",
        title = "Normal",
        subtitle = "Standard Video",
        description = "Default standard video pipeline with unadulterated sensor output."
    ),

    /**
     * Dedicated Custom Pipeline based on Cinema Mode Natural Profile:
     * Rec.2020 Log, neutral natural rendering, clean highlight and shadow response.
     */
    CUSTOM(
        id = "custom",
        title = "Custom Pipeline",
        subtitle = "Natural Rec.2020 Log",
        description = "Cinema Mode Natural Profile: Rec.2020 Log, neutral rendering, clean highlight & shadow response without LUT/filter look."
    );

    companion object {
        fun fromId(id: String?): VideoPipelineType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NORMAL
    }
}
