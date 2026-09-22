package com.example.camera.ultrafast.data

import androidx.room.*
import com.example.camera.ultrafast.model.UltraFastBurstEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UltraFastBurstDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBurst(burst: UltraFastBurstEntity)

    @Update
    suspend fun updateBurst(burst: UltraFastBurstEntity)

    @Query("SELECT * FROM ultra_fast_bursts WHERE burstId = :burstId LIMIT 1")
    suspend fun getBurstById(burstId: String): UltraFastBurstEntity?

    @Query("SELECT * FROM ultra_fast_bursts WHERE coverUri = :uri OR photoUrisJson LIKE '%' || :uri || '%' LIMIT 1")
    suspend fun getBurstForUri(uri: String): UltraFastBurstEntity?

    @Query("SELECT * FROM ultra_fast_bursts ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestBurst(): UltraFastBurstEntity?

    @Query("SELECT * FROM ultra_fast_bursts ORDER BY timestamp DESC")
    fun getAllBurstsFlow(): Flow<List<UltraFastBurstEntity>>

    @Query("SELECT * FROM ultra_fast_bursts ORDER BY timestamp DESC")
    suspend fun getAllBursts(): List<UltraFastBurstEntity>

    @Query("DELETE FROM ultra_fast_bursts WHERE burstId = :burstId")
    suspend fun deleteBurst(burstId: String)
}
