package com.example.camera.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import com.example.camera.hdr.data.db.HdrVideoJobDao
import com.example.camera.hdr.data.db.HdrVideoJobEntity

@Database(entities = [RefocusPhotoEntity::class, HdrVideoJobEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun refocusDao(): RefocusDao
    abstract fun hdrVideoJobDao(): HdrVideoJobDao

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
