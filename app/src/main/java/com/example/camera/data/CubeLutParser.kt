package com.example.camera.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val TAG = "CubeLutParser"

/**
 * Parsed representation of an Adobe .cube Look-Up Table (1D or 3D).
 */
data class ParsedCubeLut(
    val id: String,
    val title: String,
    val is3D: Boolean,
    val size: Int,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    val tonemapCurve: FloatArray, // 64 points for Camera2 TonemapCurve (x=0..1 -> y=0..1)
    val colorMatrix: FloatArray,  // 4x5 (20 floats) for Android/Compose ColorMatrix
    val estimatedContrast: Float = 1.15f,
    val estimatedSaturation: Float = 1.05f,
    val highlightRollOff: Float = 0.65f,
    val shadowToe: Float = 0.02f,
    val table3D: FloatArray = FloatArray(0)
) {
    val matrix3x3: FloatArray
        get() = floatArrayOf(
            colorMatrix[0], colorMatrix[1], colorMatrix[2],
            colorMatrix[5], colorMatrix[6], colorMatrix[7],
            colorMatrix[10], colorMatrix[11], colorMatrix[12]
        )

    @Volatile
    private var cachedStripBitmap: android.graphics.Bitmap? = null

    /**
     * Generates a 2D strip Bitmap representation of the 3D LUT table for OpenGL shader sampling.
     * Dimensions: (size * size) x size.
     */
    fun to2DStripBitmap(): android.graphics.Bitmap {
        cachedStripBitmap?.let { if (!it.isRecycled) return it }

        val n = size.coerceIn(2, 65)
        val width = n * n
        val height = n
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        if (is3D && table3D.size >= n * n * n * 3) {
            for (b in 0 until n) {
                for (g in 0 until n) {
                    for (r in 0 until n) {
                        val lutIdx = (r + g * n + b * n * n) * 3
                        val red = (table3D[lutIdx].coerceIn(0f, 1f) * 255f).toInt()
                        val green = (table3D[lutIdx + 1].coerceIn(0f, 1f) * 255f).toInt()
                        val blue = (table3D[lutIdx + 2].coerceIn(0f, 1f) * 255f).toInt()
                        val pixelIdx = g * width + (b * n + r)
                        pixels[pixelIdx] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                }
            }
        } else if (!is3D && table3D.size >= n * 3) {
            val total1D = table3D.size / 3
            for (b in 0 until n) {
                val bIdx = (b * (total1D - 1) / (n - 1)).coerceIn(0, total1D - 1) * 3
                val blue = (table3D[bIdx + 2].coerceIn(0f, 1f) * 255f).toInt()
                for (g in 0 until n) {
                    val gIdx = (g * (total1D - 1) / (n - 1)).coerceIn(0, total1D - 1) * 3
                    val green = (table3D[gIdx + 1].coerceIn(0f, 1f) * 255f).toInt()
                    for (r in 0 until n) {
                        val rIdx = (r * (total1D - 1) / (n - 1)).coerceIn(0, total1D - 1) * 3
                        val red = (table3D[rIdx].coerceIn(0f, 1f) * 255f).toInt()
                        val pixelIdx = g * width + (b * n + r)
                        pixels[pixelIdx] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                }
            }
        } else {
            // Fallback to identity
            for (b in 0 until n) {
                val blue = (b.toFloat() / (n - 1) * 255f).toInt()
                for (g in 0 until n) {
                    val green = (g.toFloat() / (n - 1) * 255f).toInt()
                    for (r in 0 until n) {
                        val red = (r.toFloat() / (n - 1) * 255f).toInt()
                        val pixelIdx = g * width + (b * n + r)
                        pixels[pixelIdx] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        cachedStripBitmap = bitmap
        return bitmap
    }

    fun toTonemapCurve(): android.hardware.camera2.params.TonemapCurve {
        val numPoints = 32
        val red = FloatArray(numPoints * 2)
        val green = FloatArray(numPoints * 2)
        val blue = FloatArray(numPoints * 2)
        for (i in 0 until numPoints) {
            val x = i.toFloat() / (numPoints - 1).toFloat()
            val curveIdx = (x * (tonemapCurve.size - 1)).toInt().coerceIn(0, tonemapCurve.size - 1)
            val y = tonemapCurve[curveIdx].coerceIn(0f, 1f)
            val idx = i * 2
            red[idx] = x
            red[idx + 1] = y
            green[idx] = x
            green[idx + 1] = y
            blue[idx] = x
            blue[idx + 1] = y
        }
        return android.hardware.camera2.params.TonemapCurve(red, green, blue)
    }

    fun toAndroidColorMatrix(): android.graphics.ColorMatrix {
        return android.graphics.ColorMatrix(colorMatrix)
    }
}

/**
 * High-performance, robust parser for industry-standard .cube LUT files.
 * Supports both 1D and 3D LUT tables conforming to Adobe Cube LUT specifications.
 */
object CubeLutParser {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, ParsedCubeLut>()

    fun getOrLoad(filePath: String): ParsedCubeLut? {
        cache[filePath]?.let { return it }
        val file = java.io.File(filePath)
        if (!file.exists()) return null
        return try {
            file.inputStream().use { stream ->
                parseStream(stream, file.nameWithoutExtension)?.also { parsed ->
                    cache[filePath] = parsed
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached .cube LUT from $filePath", e)
            null
        }
    }

    fun parse(context: Context, uri: Uri, fallbackTitle: String = "Custom LUT"): ParsedCubeLut? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseStream(stream, fallbackTitle)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse .cube LUT from URI: $uri", e)
            null
        }
    }

    fun parseStream(stream: InputStream, defaultTitle: String): ParsedCubeLut? {
        val reader = BufferedReader(InputStreamReader(stream))
        var title = defaultTitle
        var is3D = true
        var lutSize = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)

        val tableData = mutableListOf<FloatArray>()

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@forEachLine
            }

            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) return@forEachLine

            when (tokens[0].uppercase()) {
                "TITLE" -> {
                    title = tokens.drop(1).joinToString(" ").replace("\"", "").trim()
                }
                "LUT_3D_SIZE" -> {
                    is3D = true
                    lutSize = tokens.getOrNull(1)?.toIntOrNull() ?: 33
                }
                "LUT_1D_SIZE" -> {
                    is3D = false
                    lutSize = tokens.getOrNull(1)?.toIntOrNull() ?: 1024
                }
                "DOMAIN_MIN" -> {
                    if (tokens.size >= 4) {
                        domainMin = floatArrayOf(
                            tokens[1].toFloatOrNull() ?: 0f,
                            tokens[2].toFloatOrNull() ?: 0f,
                            tokens[3].toFloatOrNull() ?: 0f
                        )
                    }
                }
                "DOMAIN_MAX" -> {
                    if (tokens.size >= 4) {
                        domainMax = floatArrayOf(
                            tokens[1].toFloatOrNull() ?: 1f,
                            tokens[2].toFloatOrNull() ?: 1f,
                            tokens[3].toFloatOrNull() ?: 1f
                        )
                    }
                }
                else -> {
                    // Try parsing RGB triplet
                    if (tokens.size >= 3) {
                        val r = tokens[0].toFloatOrNull()
                        val g = tokens[1].toFloatOrNull()
                        val b = tokens[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            tableData.add(floatArrayOf(r, g, b))
                        }
                    }
                }
            }
        }

        if (tableData.isEmpty() || lutSize <= 0) {
            Log.w(TAG, "Empty or invalid .cube file: size=$lutSize, rows=${tableData.size}")
            return null
        }

        val totalExpected = if (is3D) lutSize * lutSize * lutSize else lutSize
        if (tableData.size < totalExpected) {
            Log.w(TAG, "Truncated .cube file: expected $totalExpected rows, found ${tableData.size}")
        }

        // 1. Sample 64-point Luminance / Tonemap Curve along neutral diagonal (R=G=B)
        val curvePoints = 64
        val tonemapCurve = FloatArray(curvePoints)

        if (is3D) {
            // In 3D LUT ordering: red fastest, green next, blue slowest
            // Index = r + g * size + b * size * size
            for (i in 0 until curvePoints) {
                val t = i.toFloat() / (curvePoints - 1).toFloat()
                val idx = (t * (lutSize - 1)).toInt().coerceIn(0, lutSize - 1)
                val flatIndex = (idx + idx * lutSize + idx * lutSize * lutSize).coerceIn(0, tableData.size - 1)
                val rgb = tableData[flatIndex]
                // Perceptual Rec.709 luminance Y = 0.2126 R + 0.7152 G + 0.0722 B
                val lum = (0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]).coerceIn(0f, 1f)
                tonemapCurve[i] = lum
            }
        } else {
            for (i in 0 until curvePoints) {
                val t = i.toFloat() / (curvePoints - 1).toFloat()
                val idx = (t * (tableData.size - 1)).toInt().coerceIn(0, tableData.size - 1)
                val rgb = tableData[idx]
                val lum = (0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]).coerceIn(0f, 1f)
                tonemapCurve[i] = lum
            }
        }

        // 2. Extract 4x5 ColorMatrix by sampling basis color responses
        // Red corner: (size-1, 0, 0)
        // Green corner: (0, size-1, 0)
        // Blue corner: (0, 0, size-1)
        // Black corner: (0, 0, 0)
        // White corner: (size-1, size-1, size-1)
        val blackRgb: FloatArray
        val redRgb: FloatArray
        val greenRgb: FloatArray
        val blueRgb: FloatArray
        val whiteRgb: FloatArray

        if (is3D) {
            val maxIdx = lutSize - 1
            fun get3D(r: Int, g: Int, b: Int): FloatArray {
                val idx = (r + g * lutSize + b * lutSize * lutSize).coerceIn(0, tableData.size - 1)
                return tableData[idx]
            }
            blackRgb = get3D(0, 0, 0)
            redRgb = get3D(maxIdx, 0, 0)
            greenRgb = get3D(0, maxIdx, 0)
            blueRgb = get3D(0, 0, maxIdx)
            whiteRgb = get3D(maxIdx, maxIdx, maxIdx)
        } else {
            blackRgb = tableData.first()
            whiteRgb = tableData.last()
            redRgb = floatArrayOf(whiteRgb[0], blackRgb[1], blackRgb[2])
            greenRgb = floatArrayOf(blackRgb[0], whiteRgb[1], blackRgb[2])
            blueRgb = floatArrayOf(blackRgb[0], blackRgb[1], whiteRgb[2])
        }

        // Calculate affine color transform matrix
        val rr = (redRgb[0] - blackRgb[0]).coerceIn(0.5f, 1.8f)
        val rg = (redRgb[1] - blackRgb[1]).coerceIn(-0.4f, 0.4f)
        val rb = (redRgb[2] - blackRgb[2]).coerceIn(-0.4f, 0.4f)
        val roff = blackRgb[0] * 255f

        val gr = (greenRgb[0] - blackRgb[0]).coerceIn(-0.4f, 0.4f)
        val gg = (greenRgb[1] - blackRgb[1]).coerceIn(0.5f, 1.8f)
        val gb = (greenRgb[2] - blackRgb[2]).coerceIn(-0.4f, 0.4f)
        val goff = blackRgb[1] * 255f

        val br = (blueRgb[0] - blackRgb[0]).coerceIn(-0.4f, 0.4f)
        val bg = (blueRgb[1] - blackRgb[1]).coerceIn(-0.4f, 0.4f)
        val bb = (blueRgb[2] - blackRgb[2]).coerceIn(0.5f, 1.8f)
        val boff = blackRgb[2] * 255f

        val colorMatrix = floatArrayOf(
            rr, rg, rb, 0f, roff,
            gr, gg, gb, 0f, goff,
            br, bg, bb, 0f, boff,
            0f, 0f, 0f, 1f, 0f
        )

        // 3. Compute characteristics
        val midToneIndex = curvePoints / 2
        val midVal = tonemapCurve[midToneIndex]
        val contrast = ((tonemapCurve[curvePoints * 3 / 4] - tonemapCurve[curvePoints / 4]) * 2.0f).coerceIn(0.8f, 1.5f)
        val shadowToe = tonemapCurve[0].coerceIn(0f, 0.2f)
        val highlightRollOff = (1.0f - tonemapCurve[curvePoints - 1]).coerceIn(0f, 0.3f) + 0.5f

        val table3D = FloatArray(tableData.size * 3)
        for (i in tableData.indices) {
            val triplet = tableData[i]
            val baseIdx = i * 3
            table3D[baseIdx] = triplet[0]
            table3D[baseIdx + 1] = triplet[1]
            table3D[baseIdx + 2] = triplet[2]
        }

        val id = "lut_" + System.currentTimeMillis()

        return ParsedCubeLut(
            id = id,
            title = if (title.isNotBlank()) title else defaultTitle,
            is3D = is3D,
            size = lutSize,
            domainMin = domainMin,
            domainMax = domainMax,
            tonemapCurve = tonemapCurve,
            colorMatrix = colorMatrix,
            estimatedContrast = contrast,
            estimatedSaturation = 1.05f,
            highlightRollOff = highlightRollOff,
            shadowToe = shadowToe,
            table3D = table3D
        )
    }

    fun parseFile(file: java.io.File, defaultTitle: String): ParsedCubeLut? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            file.inputStream().use { stream ->
                parseStream(stream, defaultTitle)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse file: ${file.absolutePath}", e)
            null
        }
    }

    private val presetStripCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

    fun generate3DStripBitmapForPreset(lut: com.example.camera.model.CinematicLut, size: Int = 33): android.graphics.Bitmap {
        presetStripCache[lut.id]?.let { if (!it.isRecycled) return it }

        val n = size
        val width = n * n
        val height = n
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        val mat = lut.matrixValues ?: floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        val contrast = lut.contrast
        val sat = lut.saturation
        val rollOff = lut.highlightRollOff
        val toe = lut.shadowToe
        val warmCool = lut.warmCoolOffset

        for (b in 0 until n) {
            val inB = b.toFloat() / (n - 1)
            for (g in 0 until n) {
                val inG = g.toFloat() / (n - 1)
                for (r in 0 until n) {
                    val inR = r.toFloat() / (n - 1)

                    var rOut = (mat[0] * inR + mat[1] * inG + mat[2] * inB + mat[4] / 255f)
                    var gOut = (mat[5] * inR + mat[6] * inG + mat[7] * inB + mat[9] / 255f)
                    var bOut = (mat[10] * inR + mat[11] * inG + mat[12] * inB + mat[14] / 255f)

                    if (warmCool != 0f) {
                        rOut *= (1f + warmCool * 0.15f)
                        bOut *= (1f - warmCool * 0.15f)
                    }

                    if (contrast != 1f) {
                        rOut = 0.18f + (rOut - 0.18f) * contrast
                        gOut = 0.18f + (gOut - 0.18f) * contrast
                        bOut = 0.18f + (bOut - 0.18f) * contrast
                    }

                    if (toe != 0f) {
                        val w = (1f - rOut.coerceIn(0f, 1f)).let { it * it }
                        rOut += toe * 0.1f * w
                        gOut += toe * 0.1f * w
                        bOut += toe * 0.1f * w
                    }
                    if (rollOff > 0.5f) {
                        val factor = (rollOff - 0.5f) * 2f
                        if (rOut > 0.6f) {
                            val rw = ((rOut - 0.6f) / 0.4f).let { it * it }
                            rOut -= factor * 0.05f * rw
                        }
                        if (gOut > 0.6f) {
                            val rw = ((gOut - 0.6f) / 0.4f).let { it * it }
                            gOut -= factor * 0.05f * rw
                        }
                        if (bOut > 0.6f) {
                            val rw = ((bOut - 0.6f) / 0.4f).let { it * it }
                            bOut -= factor * 0.05f * rw
                        }
                    }

                    if (sat != 1f) {
                        val lum = 0.2126f * rOut + 0.7152f * gOut + 0.0722f * bOut
                        rOut = lum + (rOut - lum) * sat
                        gOut = lum + (gOut - lum) * sat
                        bOut = lum + (bOut - lum) * sat
                    }

                    val red = (rOut.coerceIn(0f, 1f) * 255f).toInt()
                    val green = (gOut.coerceIn(0f, 1f) * 255f).toInt()
                    val blue = (bOut.coerceIn(0f, 1f) * 255f).toInt()

                    val pixelIdx = g * width + (b * n + r)
                    pixels[pixelIdx] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        presetStripCache[lut.id] = bitmap
        return bitmap
    }
}
