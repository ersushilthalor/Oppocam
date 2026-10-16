package com.example.camera.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.InflaterInputStream

private const val TAG = "CustomLutRepository"
private const val PREFS_KEY_CUSTOM_LUTS = "pref_custom_luts_json"

private data class BundledLutSpec(
    val id: String,
    val title: String,
    val sourceName: String,
    val index: Int
)

/**
 * Metadata & active values for a user-imported .cube LUT.
 */
data class CustomLutItem(
    val id: String,
    val title: String,
    val filePath: String,
    val contrast: Float = 1.15f,
    val saturation: Float = 1.05f,
    val highlightRollOff: Float = 0.65f,
    val shadowToe: Float = 0.02f,
    val tonemapCurve: FloatArray,
    val colorMatrix: FloatArray,
    val isBuiltIn: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomLutItem) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Manages locally imported .cube LUT files, saving them to internal storage
 * and persisting their metadata into SharedPreferences.
 */
class CustomLutRepository(private val context: Context) {

    companion object {
        @Volatile
        private var cachedJsonStr: String? = null
        @Volatile
        private var cachedLutList: List<CustomLutItem>? = null

        private val bundledSpecs = listOf(
            BundledLutSpec("brisa", "Brisa", "Brisa Tropical.cube", 0),
            BundledLutSpec("somber", "Somber", "DarkAndSomber.cube", 1),
            BundledLutSpec("nettai", "Nettai", "Nettai No Hikari.cube", 2),
            BundledLutSpec("romance", "Romance", "SamsungLog_to_Romance_Neutral_Rec709.cube", 3),
            BundledLutSpec("romancesoft", "RomanceSoft", "SamsungLog_to_Romance_Soft1_Rec709.cube", 4),
            BundledLutSpec("mono", "Mono", "SoftBlackAndWhite.cube", 5),
            BundledLutSpec("dusk", "Dusk", "Summer Dusk.cube", 6),
            BundledLutSpec("synch", "Synch", "Synch.cube", 7),
            BundledLutSpec("teal", "Teal", "Teal-and-Orange.cube", 8),
            BundledLutSpec("cinema", "Cinema", "True Cinematic.cube", 9),
            BundledLutSpec("wedding", "Wedding", "Wedding LUT Pack 1.cube", 10)
        )

        private const val BUNDLE_MAGIC = "OCL1"
        private const val BUNDLE_LUT_SIZE = 9
        private const val BUNDLE_LUT_COUNT = 11
    }

    private val prefs = context.getSharedPreferences("pro_camera_user_prefs", Context.MODE_PRIVATE)
    private val lutsDir = File(context.filesDir, "custom_luts").apply { mkdirs() }
    private val builtInDir = File(context.filesDir, "builtin_luts").apply { mkdirs() }
    private val bundledTable: ByteArray? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadBundledTable()
    }

    private val _customLuts = MutableStateFlow<List<CustomLutItem>>(builtInItems())
    val customLuts: StateFlow<List<CustomLutItem>> = _customLuts.asStateFlow()

    init {
        loadSavedLuts()
    }

    private fun loadSavedLuts() {
        val jsonStr = prefs.getString(PREFS_KEY_CUSTOM_LUTS, null) ?: return
        val cached = cachedLutList
        if (jsonStr == cachedJsonStr && cached != null) {
            _customLuts.value = builtInItems() + cached
            return
        }
        try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<CustomLutItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val filePath = obj.getString("filePath")
                val file = File(filePath)
                if (file.exists() && file.length() > 0L) {
                    val id = obj.getString("id")
                    val title = obj.getString("title")
                    val contrast = obj.optDouble("contrast", 1.15).toFloat()
                    val saturation = obj.optDouble("saturation", 1.05).toFloat()
                    val highlightRollOff = obj.optDouble("highlightRollOff", 0.65).toFloat()
                    val shadowToe = obj.optDouble("shadowToe", 0.02).toFloat()

                    val curveArray = obj.getJSONArray("curve")
                    val curve = FloatArray(curveArray.length()) { idx -> curveArray.getDouble(idx).toFloat() }

                    val matrixArray = obj.getJSONArray("matrix")
                    val matrix = FloatArray(matrixArray.length()) { idx -> matrixArray.getDouble(idx).toFloat() }

                    list.add(
                        CustomLutItem(
                            id = id,
                            title = title,
                            filePath = filePath,
                            contrast = contrast,
                            saturation = saturation,
                            highlightRollOff = highlightRollOff,
                            shadowToe = shadowToe,
                            tonemapCurve = curve,
                            colorMatrix = matrix
                        )
                    )
                }
            }
            cachedJsonStr = jsonStr
            cachedLutList = list
            _customLuts.value = builtInItems() + list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved custom LUTs", e)
        }
    }

    fun importLut(uri: Uri, fallbackTitle: String): CustomLutItem? {
        val tempId = "lut_" + System.currentTimeMillis()
        val targetFile = File(lutsDir, "$tempId.cube")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy .cube file from $uri", e)
            return null
        }

        if (!targetFile.exists() || targetFile.length() == 0L) {
            Log.e(TAG, "Import failed: copied file from $uri is missing or 0 bytes")
            targetFile.delete()
            return null
        }

        val parsed = CubeLutParser.parseFile(targetFile, fallbackTitle)
        if (parsed == null) {
            Log.e(TAG, "Import failed: CubeLutParser could not parse file ${targetFile.absolutePath} (size: ${targetFile.length()} bytes)")
            targetFile.delete()
            return null
        }

        Log.i(TAG, "Successfully parsed .cube LUT: title='${parsed.title}', 3D=${parsed.is3D}, size=${parsed.size}")

        val item = CustomLutItem(
            id = parsed.id,
            title = parsed.title,
            filePath = targetFile.absolutePath,
            contrast = parsed.estimatedContrast,
            saturation = parsed.estimatedSaturation,
            highlightRollOff = parsed.highlightRollOff,
            shadowToe = parsed.shadowToe,
            tonemapCurve = parsed.tonemapCurve,
            colorMatrix = parsed.colorMatrix
        )

        val userLuts = _customLuts.value
            .filterNot { it.isBuiltIn || it.filePath == item.filePath || it.id == item.id } + item
        _customLuts.value = builtInItems() + userLuts
        persistLuts(userLuts)
        return item
    }

    fun deleteLut(lutId: String) {
        val current = _customLuts.value
        val item = current.find { it.id == lutId } ?: return
        if (item.isBuiltIn) return

        try {
            File(item.filePath).delete()
        } catch (ignored: Exception) {}
        val updated = current.filterNot { it.isBuiltIn || it.id == lutId }
        _customLuts.value = builtInItems() + updated
        persistLuts(updated)
    }

    /**
     * Ensures a built-in LUT exists as a standard .cube file for the existing LUT pipeline.
     * User-imported LUTs already have a file and are returned unchanged.
     */
    fun ensureLutReady(item: CustomLutItem): String? {
        if (!item.isBuiltIn) {
            return item.filePath.takeIf { File(it).exists() && File(it).length() > 0L }
        }

        val spec = bundledSpecs.firstOrNull { it.id == item.id } ?: return null
        val target = File(builtInDir, "${spec.id}.cube")
        if (target.exists() && target.length() > 0L) return target.absolutePath

        val packed = bundledTable ?: return null
        if (packed.size < 6 || String(packed, 0, 4, Charsets.US_ASCII) != BUNDLE_MAGIC) return null
        val size = packed[4].toInt() and 0xff
        val count = packed[5].toInt() and 0xff
        if (size != BUNDLE_LUT_SIZE || count != BUNDLE_LUT_COUNT || spec.index !in 0 until count) return null

        val lutBytes = size * size * size * 3
        val offset = 6 + spec.index * lutBytes
        if (packed.size < offset + lutBytes) return null

        return try {
            target.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("# Bundled LUT: ${spec.sourceName}")
                writer.appendLine("TITLE \"${spec.title}\"")
                writer.appendLine("LUT_3D_SIZE $size")
                writer.appendLine("DOMAIN_MIN 0.0 0.0 0.0")
                writer.appendLine("DOMAIN_MAX 1.0 1.0 1.0")
                for (i in 0 until size * size * size) {
                    val base = offset + i * 3
                    val r = (packed[base].toInt() and 0xff) / 255.0
                    val g = (packed[base + 1].toInt() and 0xff) / 255.0
                    val b = (packed[base + 2].toInt() and 0xff) / 255.0
                    writer.append(String.format(Locale.US, "%.6f %.6f %.6f%n", r, g, b))
                }
            }
            if (CubeLutParser.parseFile(target, spec.title) != null) target.absolutePath
            else {
                target.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to materialize bundled LUT ${spec.title}", e)
            target.delete()
            null
        }
    }

    private fun loadBundledTable(): ByteArray? {
        return try {
            val encoded = buildString {
                for (part in 0..5) {
                    append(context.assets.open("luts/pack$part.b64").bufferedReader().use { it.readText().trim() })
                }
            }
            val compressed = Base64.decode(encoded, Base64.DEFAULT)
            val decoded = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            if (decoded.size != 6 + BUNDLE_LUT_COUNT * BUNDLE_LUT_SIZE * BUNDLE_LUT_SIZE * BUNDLE_LUT_SIZE * 3 ||
                String(decoded, 0, 4, Charsets.US_ASCII) != BUNDLE_MAGIC ||
                (decoded[4].toInt() and 0xff) != BUNDLE_LUT_SIZE ||
                (decoded[5].toInt() and 0xff) != BUNDLE_LUT_COUNT
            ) {
                Log.e(TAG, "Bundled LUT package is invalid")
                null
            } else decoded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bundled LUT package", e)
            null
        }
    }

    private fun builtInItems(): List<CustomLutItem> {
        val curve = FloatArray(128) { i ->
            val point = (i / 2).toFloat() / 63.0f
            point
        }
        val matrix = FloatArray(20).apply {
            this[0] = 1f
            this[6] = 1f
            this[12] = 1f
            this[18] = 1f
        }
        return bundledSpecs.map { spec ->
            CustomLutItem(
                id = spec.id,
                title = spec.title,
                filePath = "builtin://${spec.id}",
                tonemapCurve = curve.copyOf(),
                colorMatrix = matrix.copyOf(),
                isBuiltIn = true
            )
        }
    }

    fun getLutById(id: String?): CustomLutItem? {
        if (id == null) return null
        return _customLuts.value.find { it.id == id }
    }

    private fun persistLuts(list: List<CustomLutItem>) {
        try {
            val array = JSONArray()
            list.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("filePath", item.filePath)
                    put("contrast", item.contrast.toDouble())
                    put("saturation", item.saturation.toDouble())
                    put("highlightRollOff", item.highlightRollOff.toDouble())
                    put("shadowToe", item.shadowToe.toDouble())

                    val curveArray = JSONArray()
                    item.tonemapCurve.forEach { curveArray.put(it.toDouble()) }
                    put("curve", curveArray)

                    val matrixArray = JSONArray()
                    item.colorMatrix.forEach { matrixArray.put(it.toDouble()) }
                    put("matrix", matrixArray)
                }
                array.put(obj)
            }
            val serialized = array.toString()
            cachedJsonStr = serialized
            cachedLutList = list
            prefs.edit().putString(PREFS_KEY_CUSTOM_LUTS, serialized).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist custom LUTs", e)
        }
    }
}
