package com.example.camera.engine.hdr

/**
 * Natural Photographic Tone Mapper.
 *
 * Preserves the pristine camera ISP exposure, contrast, and tone curve.
 * Avoids second-pass logarithmic filmic compression or artificial micro-contrast boosts
 * which cause crushed shadows, blown highlights, and halo artifacts on tone-mapped JPEGs.
 */
class HdrToneMapper {

    /**
     * Preserves natural ISP tone mapping with subtle highlight safety clamping.
     *
     * @param rgb FloatArray(3) RGB values in [0.0 .. 1.0] (modified in-place)
     * @param localBaseLuma Local base illumination
     */
    fun toneMapPixel(
        rgb: FloatArray,
        localBaseLuma: Float
    ) {
        rgb[0] = rgb[0].coerceIn(0f, 1f)
        rgb[1] = rgb[1].coerceIn(0f, 1f)
        rgb[2] = rgb[2].coerceIn(0f, 1f)
    }
}

