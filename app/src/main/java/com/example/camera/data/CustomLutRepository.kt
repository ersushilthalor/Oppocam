package com.example.camera.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "CustomLutRepository"
private const val PREFS_KEY_CUSTOM_LUTS = "pref_custom_luts_json"

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
    val colorMatrix: FloatArray
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

    private val prefs = context.getSharedPreferences("pro_camera_user_prefs", Context.MODE_PRIVATE)
    private val lutsDir = File(context.filesDir, "custom_luts").apply { mkdirs() }

    private val _customLuts = MutableStateFlow<List<CustomLutItem>>(emptyList())
    val customLuts: StateFlow<List<CustomLutItem>> = _customLuts.asStateFlow()

    init {
        loadSavedLuts()
    }

    private fun loadSavedLuts() {
        val jsonStr = prefs.getString(PREFS_KEY_CUSTOM_LUTS, null) ?: return
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
            _customLuts.value = list
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
            targetFile.delete()
            return null
        }

        val parsed = CubeLutParser.parseFile(targetFile, fallbackTitle)
        if (parsed == null) {
            targetFile.delete()
            return null
        }

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

        val updated = _customLuts.value + item
        _customLuts.value = updated
        persistLuts(updated)
        return item
    }

    fun deleteLut(lutId: String) {
        val current = _customLuts.value
        val item = current.find { it.id == lutId }
        if (item != null) {
            try {
                File(item.filePath).delete()
            } catch (ignored: Exception) {}
        }
        val updated = current.filterNot { it.id == lutId }
        _customLuts.value = updated
        persistLuts(updated)
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
            prefs.edit().putString(PREFS_KEY_CUSTOM_LUTS, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist custom LUTs", e)
        }
    }
}
