package com.example.camera.hdr.data

import android.content.Context
import com.example.camera.data.db.AppDatabase
import com.example.camera.hdr.data.db.HdrVideoJobDao
import com.example.camera.hdr.data.db.HdrVideoJobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Clean repository abstracting database operations for HDR video processing tasks.
 */
class HdrVideoRepository(context: Context) {
    private val dao: HdrVideoJobDao = AppDatabase.getInstance(context).hdrVideoJobDao()

    val allJobs: Flow<List<HdrVideoJobEntity>> = dao.getAllJobs()

    suspend fun getAllJobsList(): List<HdrVideoJobEntity> = withContext(Dispatchers.IO) {
        dao.getAllJobsList()
    }

    suspend fun getJobById(id: String): HdrVideoJobEntity? = withContext(Dispatchers.IO) {
        dao.getJobById(id)
    }

    suspend fun getIncompleteJobs(): List<HdrVideoJobEntity> = withContext(Dispatchers.IO) {
        dao.getIncompleteJobs()
    }

    suspend fun saveJob(job: HdrVideoJobEntity) = withContext(Dispatchers.IO) {
        dao.insertOrUpdate(job)
    }

    suspend fun updateProgress(id: String, status: String, progress: Int, remainingSec: Int, speedFps: Float) = withContext(Dispatchers.IO) {
        dao.updateProgress(id, status, progress, remainingSec, speedFps)
    }

    suspend fun markComplete(id: String, status: String, finalSize: Long) = withContext(Dispatchers.IO) {
        dao.markComplete(id, status, finalSize)
    }

    suspend fun deleteJob(id: String) = withContext(Dispatchers.IO) {
        dao.deleteJob(id)
    }
}
