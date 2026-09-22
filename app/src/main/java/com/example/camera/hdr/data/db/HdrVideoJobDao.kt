package com.example.camera.hdr.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HdrVideoJobDao {
    @Query("SELECT * FROM hdr_video_jobs ORDER BY timestamp DESC")
    fun getAllJobs(): Flow<List<HdrVideoJobEntity>>

    @Query("SELECT * FROM hdr_video_jobs ORDER BY timestamp DESC")
    suspend fun getAllJobsList(): List<HdrVideoJobEntity>

    @Query("SELECT * FROM hdr_video_jobs WHERE id = :id LIMIT 1")
    suspend fun getJobById(id: String): HdrVideoJobEntity?

    @Query("SELECT * FROM hdr_video_jobs WHERE status IN ('CAPTURING', 'QUEUED', 'PROCESSING', 'HDR_MERGE', 'ENCODING') ORDER BY timestamp ASC")
    suspend fun getIncompleteJobs(): List<HdrVideoJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(job: HdrVideoJobEntity)

    @Update
    suspend fun update(job: HdrVideoJobEntity)

    @Query("UPDATE hdr_video_jobs SET status = :status, progressPercent = :progress, estimatedRemainingSec = :remainingSec, processingSpeedFps = :speedFps WHERE id = :id")
    suspend fun updateProgress(id: String, status: String, progress: Int, remainingSec: Int, speedFps: Float)

    @Query("UPDATE hdr_video_jobs SET status = :status, finalHdrSizeBytes = :finalSize WHERE id = :id")
    suspend fun markComplete(id: String, status: String, finalSize: Long)

    @Query("DELETE FROM hdr_video_jobs WHERE id = :id")
    suspend fun deleteJob(id: String)
}
