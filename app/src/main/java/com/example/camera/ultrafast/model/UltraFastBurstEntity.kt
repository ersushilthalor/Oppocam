package com.example.camera.ultrafast.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray

/**
 * Persists an Ultra Fast Shutter burst sequence in Room database.
 * Enables burst grouping in the gallery and in-app media viewer.
 */
@Entity(tableName = "ultra_fast_bursts")
data class UltraFastBurstEntity(
    @PrimaryKey
    val burstId: String,
    val coverUri: String,
    val photoUrisJson: String,
    val frameCount: Int,
    val fps: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val width: Int = 0,
    val height: Int = 0,
    val title: String = "",
    val isCompleted: Boolean = true
) {
    /**
     * Parses the photo URIs stored in JSON array format.
     */
    fun getPhotoUris(): List<String> {
        return try {
            val array = JSONArray(photoUrisJson)
            val list = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: Exception) {
            if (coverUri.isNotEmpty()) listOf(coverUri) else emptyList()
        }
    }

    companion object {
        fun createJsonFromUris(uris: List<String>): String {
            val array = JSONArray()
            for (uri in uris) {
                array.put(uri)
            }
            return array.toString()
        }
    }
}
