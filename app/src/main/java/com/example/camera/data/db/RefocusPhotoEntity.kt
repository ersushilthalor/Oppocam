package com.example.camera.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists metadata and storage paths for photos captured with Refocus Photo enabled.
 * Allows instant lookup in the Gallery to enable post-capture interactive refocusing and 3D parallax.
 */
@Entity(tableName = "refocus_photos")
data class RefocusPhotoEntity(
    @PrimaryKey
    val photoUri: String,
    val bundleDir: String,
    val timestamp: Long = System.currentTimeMillis(),
    val nearPlanePath: String,
    val midPlanePath: String,
    val farPlanePath: String,
    val depthMapPath: String,
    val planeCount: Int = 3,
    val nearDiopters: Float = 0f,
    val midDiopters: Float = 0f,
    val farDiopters: Float = 0f,
    val alignmentDx: Float = 0f,
    val alignmentDy: Float = 0f,
    val width: Int = 0,
    val height: Int = 0
)
