package com.example.camera.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import com.example.camera.hdr.data.db.HdrVideoJobDao
import com.example.camera.hdr.data.db.HdrVideoJobEntity
import com.example.camera.ultrafast.data.UltraFastBurstDao
import com.example.camera.ultrafast.model.UltraFastBurstEntity

@Database(entities = [RefocusPhotoEntity::class, HdrVideoJobEntity::class, UltraFastBurstEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun refocusDao(): RefocusDao
    abstract fun hdrVideoJobDao(): HdrVideoJobDao
    abstract fun ultraFastBurstDao(): UltraFastBurstDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "camera_app_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
