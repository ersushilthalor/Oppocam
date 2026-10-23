package com.hinnka.mycamera.camera

import kotlin.math.abs

/**
 * Photon Camera Aspect Ratio System
 *
 * Supports:
 * - 4:3 (RATIO_4_3)
 * - 16:9 (RATIO_16_9)
 * - 1:1 (RATIO_1_1)
 * - 3:2 (RATIO_3_2)
 * - 21:9 (RATIO_21_9)
 * - XPAN (65:24)
 * - FULL (Full screen)
 * - Custom aspect ratios
 */
class AspectRatio private constructor(
    val name: String,
    val widthRatio: Int,
    val heightRatio: Int
) {
    override fun equals(other: Any?): Boolean {
        return other is AspectRatio && name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun toString(): String {
        return name
    }

    fun getValue(isLandscape: Boolean): Float {
        if (this == FULL) {
            return 0f
        }
        return if (isLandscape) {
            widthRatio.toFloat() / heightRatio
        } else {
            heightRatio.toFloat() / widthRatio
        }
    }

    fun getDisplayName(): String {
        if (this == XPAN) {
            return "XPAN"
        }
        if (this == FULL) {
            return "FULL"
        }
        return "$widthRatio:$heightRatio"
    }

    companion object {
        const val TOP_SHEET_MAX_COUNT = 6
        private const val CUSTOM_PREFIX = "CUSTOM_"

        val RATIO_3_2 = AspectRatio("RATIO_3_2", 3, 2)
        val RATIO_4_3 = AspectRatio("RATIO_4_3", 4, 3)
        val RATIO_16_9 = AspectRatio("RATIO_16_9", 16, 9)
        val RATIO_1_1 = AspectRatio("RATIO_1_1", 1, 1)
        val RATIO_21_9 = AspectRatio("RATIO_21_9", 21, 9)
        val XPAN = AspectRatio("XPAN", 65, 24)
        val FULL = AspectRatio("FULL", 0, 0)

        val entries: List<AspectRatio> = listOf(
            RATIO_4_3,
            RATIO_16_9,
            RATIO_1_1,
            FULL,
            RATIO_3_2,
            RATIO_21_9,
            XPAN
        )

        val defaultTopSheetRatios: List<AspectRatio> = listOf(
            RATIO_4_3,
            RATIO_16_9,
            RATIO_1_1,
            FULL,
            RATIO_3_2,
            XPAN
        )

        fun fromString(string: String): AspectRatio {
            return valueOfOrNull(string) ?: entries.firstOrNull { it.getDisplayName() == string } ?: RATIO_4_3
        }

        fun valueOf(name: String): AspectRatio {
            return valueOfOrNull(name) ?: throw IllegalArgumentException("Unknown AspectRatio: $name")
        }

        fun valueOfOrNull(name: String): AspectRatio? {
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
            if (!name.startsWith(CUSTOM_PREFIX)) return null
            val parts = name.removePrefix(CUSTOM_PREFIX).split("_")
            if (parts.size != 2) return null
            val width = parts[0].toIntOrNull() ?: return null
            val height = parts[1].toIntOrNull() ?: return null
            return custom(width, height)
        }

        fun custom(widthRatio: Int, heightRatio: Int): AspectRatio {
            val width = widthRatio.coerceIn(1, 999)
            val height = heightRatio.coerceIn(1, 999)
            val divisor = gcd(width, height)
            val normalizedWidth = width / divisor
            val normalizedHeight = height / divisor
            entries.firstOrNull {
                it.widthRatio == normalizedWidth && it.heightRatio == normalizedHeight
            }?.let { return it }
            return AspectRatio(
                name = "${CUSTOM_PREFIX}${normalizedWidth}_${normalizedHeight}",
                widthRatio = normalizedWidth,
                heightRatio = normalizedHeight
            )
        }

        fun sanitizeTopSheetRatios(ratios: List<AspectRatio>): List<AspectRatio> {
            val sanitized = ratios
                .distinct()
                .take(TOP_SHEET_MAX_COUNT)
            return sanitized.ifEmpty { defaultTopSheetRatios }
        }

        fun sanitizeCustomRatios(ratios: List<AspectRatio>): List<AspectRatio> {
            return ratios
                .map { custom(it.widthRatio, it.heightRatio) }
                .filter { ratio -> entries.none { it.name == ratio.name } }
                .distinctBy { it.name }
        }

        private fun gcd(a: Int, b: Int): Int {
            var num1 = a
            var num2 = b
            while (num2 != 0) {
                val temp = num2
                num2 = num1 % num2
                num1 = temp
            }
            return if (num1 == 0) 1 else abs(num1)
        }
    }
}
