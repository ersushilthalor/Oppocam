package com.example.camera.ultrafast.data

import android.content.Context
import com.example.camera.data.db.AppDatabase
import com.example.camera.ultrafast.model.UltraFastBurstEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository for storing and retrieving Ultra Fast Burst sequences.
 */
class UltraFastBurstRepository(context: Context) {
    private val dao = AppDatabase.getInstance(context).ultraFastBurstDao()

    suspend fun saveBurst(burst: UltraFastBurstEntity) = withContext(Dispatchers.IO) {
        dao.insertBurst(burst)
    }

    suspend fun getBurstById(burstId: String): UltraFastBurstEntity? = withContext(Dispatchers.IO) {
        dao.getBurstById(burstId)
    }

    suspend fun getBurstForUri(uri: String): UltraFastBurstEntity? = withContext(Dispatchers.IO) {
        dao.getBurstForUri(uri)
    }

    fun getAllBursts(): Flow<List<UltraFastBurstEntity>> = dao.getAllBurstsFlow()

    suspend fun deleteBurst(burstId: String) = withContext(Dispatchers.IO) {
        dao.deleteBurst(burstId)
    }
}
