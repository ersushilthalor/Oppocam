package com.example.camera.computational.video

/**
 * Computational video processing profiles for real-time smartphone-style video.
 *
 * Each profile reproduces the real-world processing characteristics of modern flagship devices:
 * - [DEFAULT]: Clean pass-through of the Camera2/ISP normal video stream.
 * - [PIXEL]: Google HDR+ computational video style with deep contrasty shadows,
 *   Real Tone skin protection, high micro-contrast, and fine texture recovery.
 * - [SAMSUNG]: Samsung Super HDR style with open bright shadows, saturated blues and greens,
 *   crisp edge-aware micro-contrast, and punchy dynamic range.
 * - [IPHONE]: Apple Cinematic tone style with smooth highlight knee roll-off, warm golden tonality,
 *   delicate natural detail without halos, and temporal exposure consistency.
 * - [VIVO]: Vivo Zeiss Natural & Vivid clarity with ultra-clean chroma denoising,
 *   micro-structure definition, and soft porcelain skin protection.
 */
enum class ComputationalVideoPipeline(
    val id: String,
    val displayName: String,
    val badge: String,
    val subtitle: String,
    val description: String
) {
    DEFAULT(
        id = "default",
        displayName = "Default / Stock",
        badge = "STD",
        subtitle = "Standard ISP Video",
        description = "Direct Camera2/ISP video stream without computational alteration."
    ),
    PIXEL(
        id = "pixel",
        displayName = "Pixel",
        badge = "HDR+",
        subtitle = "Computational HDR+ & Real Tone",
        description = "Balanced contrast, rich natural shadows, fine texture recovery, and melanin-accurate Real Tone skin protection."
    ),
    SAMSUNG(
        id = "samsung",
        displayName = "Samsung",
        badge = "DYNAMIC",
        subtitle = "Vibrant Dynamic HDR",
        description = "Open bright shadows, saturated blues/greens, crisp edge-aware micro-contrast, and punchy finish."
    ),
    IPHONE(
        id = "iphone",
        displayName = "iPhone",
        badge = "CINEMATIC",
        subtitle = "Smooth Knee Roll-Off",
        description = "Smooth highlight roll-off, signature golden warmth, organic fine detail, and flicker-free exposure stability."
    ),
    VIVO(
        id = "vivo",
        displayName = "Vivo",
        badge = "ZEISS",
        subtitle = "Vivid Clarity & Denoise",
        description = "Ultra-clean chroma denoising, Zeiss-style micro-structure definition, and soft porcelain skin protection."
    );

    companion object {
        fun fromId(id: String?): ComputationalVideoPipeline {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
        }
    }
}
