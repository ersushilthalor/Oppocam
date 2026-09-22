package com.example.camera.hdr.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent representation of an Adaptive Dual-Exposure HDR video job.
 * Preserves state across app restarts, enabling background worker recovery.
 */
@Entity(tableName = "hdr_video_jobs")
data class HdrVideoJobEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String, // HdrJobStatus name
    val progressPercent: Int = 0,
    val estimatedRemainingSec: Int = 0,
    val processingSpeedFps: Float = 0f,
    val rawDirectory: String,
    val finalHdrVideoUri: String,
    val sdrCompatVideoUri: String? = null,
    val thumbnailUri: String,
    val durationSeconds: Int = 0,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitDepth: Int = 10,
    val originalSizeBytes: Long = 0L,
    val finalHdrSizeBytes: Long = 0L,
    val priority: String, // ProcessingPriority name
    val isOriginalKept: Boolean = false,
    val errorDescription: String? = null,
    val shortIso: Int = 100,
    val shortExposureNs: Long = 2_000_000L,
    val longIso: Int = 100,
    val longExposureNs: Long = 16_666_666L,
    val evDelta: Float = 2.0f
)
