package com.example.camera.engine

import android.content.Context
import android.graphics.ColorMatrix
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.TonemapCurve
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.roundToInt

/**
 * Data structure representing a parsed 1D or 3D .cube LUT.
 */
data class ParsedCubeLut(
    val title: String,
    val is3D: Boolean,
    val size: Int,
    val curveRed: FloatArray,    // 128 floats (64 pairs of in, out)
    val curveGreen: FloatArray,  // 128 floats
    val curveBlue: FloatArray,   // 128 floats
    val matrix3x3: FloatArray,   // 9 floats (row-major 3x3)
    val matrix4x5: FloatArray    // 20 floats for Android ColorMatrix
) {
    fun toTonemapCurve(): TonemapCurve {
        return TonemapCurve(curveRed, curveGreen, curveBlue)
    }

    fun toAndroidColorMatrix(): ColorMatrix {
        return ColorMatrix(matrix4x5)
    }

    fun toColorSpaceTransform(): ColorSpaceTransform {
        val outRationals = IntArray(18)
        for (i in 0 until 9) {
            val cellVal = matrix3x3[i]
            val num = (cellVal * 256f).roundToInt().coerceIn(-1024, 1024)
            outRationals[i * 2] = num
            outRationals[i * 2 + 1] = 256
        }
        return ColorSpaceTransform(outRationals)
    }
}

/**
 * High-performance Parser and Manager for industry-standard .cube Look-Up Tables.
 * Supports:
 * - Adobe Cube 3D LUT format (LUT_3D_SIZE 17, 33, 65, etc.)
 * - Adobe Cube 1D LUT format (LUT_1D_SIZE 1024, etc.)
 * - Automatic extraction of 64-point neutral tonemap transfer curve for Camera2 ISP
 * - Automatic extraction of 3x3 color gamut transform and 4x5 ColorMatrix for viewfinder monitoring
 */
object CubeLutParser {
    private const val TAG = "CubeLutParser"
    private const val CURVE_POINTS = 64

    private var cachedPath: String? = null
    private var cachedLut: ParsedCubeLut? = null

    /**
     * Retrieves the parsed custom LUT from cache or loads from file.
     */
    fun getOrLoad(filePath: String?): ParsedCubeLut? {
        if (filePath.isNullOrBlank()) return null
        if (cachedPath == filePath && cachedLut != null) {
            return cachedLut
        }
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) return null

        return try {
            file.inputStream().use { stream ->
                val parsed = parse(stream, file.nameWithoutExtension)
                cachedPath = filePath
                cachedLut = parsed
                parsed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom LUT from $filePath", e)
            null
        }
    }

    /**
     * Imports a .cube file from a content [Uri], saves it to internal app storage, and returns the file path.
     */
    fun importCubeFromUri(context: Context, uri: Uri): Pair<File, ParsedCubeLut>? {
        return try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return null

            // Determine display name
            var fileName = "custom_lut_${System.currentTimeMillis()}.cube"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        fileName = if (name.endsWith(".cube", ignoreCase = true)) name else "$name.cube"
                    }
                }
            }

            val lutsDir = File(context.filesDir, "cinema_luts").apply { mkdirs() }
            val targetFile = File(lutsDir, fileName)

            // Read bytes and parse
            val bytes = inputStream.readBytes()
            targetFile.writeBytes(bytes)

            val parsed = targetFile.inputStream().use { stream ->
                parse(stream, fileName.removeSuffix(".cube"))
            }

            cachedPath = targetFile.absolutePath
            cachedLut = parsed

            Pair(targetFile, parsed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import .cube LUT from uri: $uri", e)
            null
        }
    }

    /**
     * Parses an input stream formatted in the Adobe .cube LUT standard.
     */
    fun parse(inputStream: InputStream, fallbackTitle: String = "Custom LUT"): ParsedCubeLut {
        val reader = BufferedReader(InputStreamReader(inputStream))
        var title = fallbackTitle
        var is3D = true
        var lutSize = 0
        var domainMinR = 0f; var domainMinG = 0f; var domainMinB = 0f
        var domainMaxR = 1f; var domainMaxG = 1f; var domainMaxB = 1f

        val dataFloats = ArrayList<Float>(33 * 33 * 33 * 3)

        var line: String? = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                line = reader.readLine()
                continue
            }

            val upper = trimmed.uppercase()
            when {
                upper.startsWith("TITLE") -> {
                    val firstQuote = trimmed.indexOf('"')
                    val lastQuote = trimmed.lastIndexOf('"')
                    title = if (firstQuote in 0 until lastQuote) {
                        trimmed.substring(firstQuote + 1, lastQuote).trim()
                    } else {
                        trimmed.substringAfter("TITLE").trim().removeSurrounding("\"")
                    }
                }
                upper.startsWith("LUT_3D_SIZE") -> {
                    is3D = true
                    lutSize = trimmed.substringAfter("LUT_3D_SIZE").trim().toIntOrNull() ?: 33
                }
                upper.startsWith("LUT_1D_SIZE") -> {
                    is3D = false
                    lutSize = trimmed.substringAfter("LUT_1D_SIZE").trim().toIntOrNull() ?: 1024
                }
                upper.startsWith("DOMAIN_MIN") -> {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        domainMinR = parts[1].toFloatOrNull() ?: 0f
                        domainMinG = parts[2].toFloatOrNull() ?: 0f
                        domainMinB = parts[3].toFloatOrNull() ?: 0f
                    }
                }
                upper.startsWith("DOMAIN_MAX") -> {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        domainMaxR = parts[1].toFloatOrNull() ?: 1f
                        domainMaxG = parts[2].toFloatOrNull() ?: 1f
                        domainMaxB = parts[3].toFloatOrNull() ?: 1f
                    }
                }
                else -> {
                    // Try parsing 3 RGB floats from this line
                    val tokens = trimmed.split("\\s+".toRegex())
                    if (tokens.size >= 3) {
                        val r = tokens[0].toFloatOrNull()
                        val g = tokens[1].toFloatOrNull()
                        val b = tokens[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            dataFloats.add(r)
                            dataFloats.add(g)
                            dataFloats.add(b)
                        }
                    }
                }
            }
            line = reader.readLine()
        }

        if (lutSize <= 0) {
            lutSize = if (is3D) 33 else 1024
        }

        val totalExpected = if (is3D) lutSize * lutSize * lutSize * 3 else lutSize * 3
        val rawData = FloatArray(totalExpected) { idx ->
            if (idx < dataFloats.size) dataFloats[idx] else 0f
        }

        // Helper to query RGB in rawData
        fun sample3D(rIdx: Int, gIdx: Int, bIdx: Int): FloatArray {
            val r = rIdx.coerceIn(0, lutSize - 1)
            val g = gIdx.coerceIn(0, lutSize - 1)
            val b = bIdx.coerceIn(0, lutSize - 1)
            // Adobe standard .cube ordering: r changes fastest, then g, then b
            val index = (b * lutSize * lutSize + g * lutSize + r) * 3
            return floatArrayOf(
                if (index < rawData.size) rawData[index] else 0f,
                if (index + 1 < rawData.size) rawData[index + 1] else 0f,
                if (index + 2 < rawData.size) rawData[index + 2] else 0f
            )
        }

        fun sample1D(idx: Int): FloatArray {
            val i = idx.coerceIn(0, lutSize - 1) * 3
            return floatArrayOf(
                if (i < rawData.size) rawData[i] else 0f,
                if (i + 1 < rawData.size) rawData[i + 1] else 0f,
                if (i + 2 < rawData.size) rawData[i + 2] else 0f
            )
        }

        // 1. Generate 64-point neutral tonemap curve
        val curveRed = FloatArray(CURVE_POINTS * 2)
        val curveGreen = FloatArray(CURVE_POINTS * 2)
        val curveBlue = FloatArray(CURVE_POINTS * 2)

        for (i in 0 until CURVE_POINTS) {
            val inNorm = i.toFloat() / (CURVE_POINTS - 1).toFloat()
            val sampled = if (is3D) {
                val step = ((lutSize - 1) * inNorm).roundToInt()
                sample3D(step, step, step)
            } else {
                val step = ((lutSize - 1) * inNorm).roundToInt()
                sample1D(step)
            }

            val outR = sampled[0].coerceIn(0f, 1f)
            val outG = sampled[1].coerceIn(0f, 1f)
            val outB = sampled[2].coerceIn(0f, 1f)

            val idx = i * 2
            curveRed[idx] = inNorm
            curveRed[idx + 1] = outR

            curveGreen[idx] = inNorm
            curveGreen[idx + 1] = outG

            curveBlue[idx] = inNorm
            curveBlue[idx + 1] = outB
        }

        // 2. Extract 3x3 color gamut cross-talk matrix
        val redCorner = if (is3D) sample3D(lutSize - 1, 0, 0) else sample1D(lutSize - 1)
        val greenCorner = if (is3D) sample3D(0, lutSize - 1, 0) else sample1D(lutSize / 2)
        val blueCorner = if (is3D) sample3D(0, 0, lutSize - 1) else sample1D(lutSize - 1)
        val blackCorner = if (is3D) sample3D(0, 0, 0) else sample1D(0)

        // Matrix 3x3:
        // [ R_r, G_r, B_r ]
        // [ R_g, G_g, B_g ]
        // [ R_b, G_b, B_b ]
        val rR = (redCorner[0] - blackCorner[0]).coerceIn(0.2f, 2.0f)
        val rG = (redCorner[1] - blackCorner[1]).coerceIn(-0.5f, 0.5f)
        val rB = (redCorner[2] - blackCorner[2]).coerceIn(-0.5f, 0.5f)

        val gR = (greenCorner[0] - blackCorner[0]).coerceIn(-0.5f, 0.5f)
        val gG = (greenCorner[1] - blackCorner[1]).coerceIn(0.2f, 2.0f)
        val gB = (greenCorner[2] - blackCorner[2]).coerceIn(-0.5f, 0.5f)

        val bR = (blueCorner[0] - blackCorner[0]).coerceIn(-0.5f, 0.5f)
        val bG = (blueCorner[1] - blackCorner[1]).coerceIn(-0.5f, 0.5f)
        val bB = (blueCorner[2] - blackCorner[2]).coerceIn(0.2f, 2.0f)

        val matrix3x3 = floatArrayOf(
            rR, gR, bR,
            rG, gG, bG,
            rB, gB, bB
        )

        // 4x5 Android ColorMatrix for viewfinder hardware layer fallback
        val matrix4x5 = floatArrayOf(
            rR, gR, bR, 0f, blackCorner[0] * 255f,
            rG, gG, bG, 0f, blackCorner[1] * 255f,
            rB, bG, bB, 0f, blackCorner[2] * 255f,
            0f, 0f, 0f, 1f, 0f
        )

        return ParsedCubeLut(
            title = title,
            is3D = is3D,
            size = lutSize,
            curveRed = curveRed,
            curveGreen = curveGreen,
            curveBlue = curveBlue,
            matrix3x3 = matrix3x3,
            matrix4x5 = matrix4x5
        )
    }
}
