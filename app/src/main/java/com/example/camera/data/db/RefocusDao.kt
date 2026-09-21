package com.example.camera.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RefocusDao {
    @Query("SELECT * FROM refocus_photos WHERE photoUri = :uri LIMIT 1")
    suspend fun getRefocusPhoto(uri: String): RefocusPhotoEntity?

    @Query("SELECT * FROM refocus_photos ORDER BY timestamp DESC")
    fun getAllRefocusPhotos(): Flow<List<RefocusPhotoEntity>>

    @Query("SELECT * FROM refocus_photos ORDER BY timestamp DESC")
    suspend fun getAllRefocusPhotosList(): List<RefocusPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRefocusPhoto(entity: RefocusPhotoEntity)

    @Query("DELETE FROM refocus_photos WHERE photoUri = :uri")
    suspend fun deleteRefocusPhoto(uri: String)
}
