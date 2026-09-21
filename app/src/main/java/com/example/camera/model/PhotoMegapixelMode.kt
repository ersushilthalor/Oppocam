package com.example.camera.model

/**
 * Megapixel capture mode for photo mode:
 * - M12: Standard 12MP Quad-Bayer pixel binned (4000x3000 / 4080x3072)
 * - M50: 50MP Computational Ultra HD (Single-Frame Motion-Free Super-Resolution)
 */
enum class PhotoMegapixelMode(
    val label: String,
    val megapixels: Int,
    val description: String
) {
    M12("12M", 12, "Standard 12MP (4-in-1 Binned)"),
    M50("50M", 50, "50MP Computational Ultra HD")
}
