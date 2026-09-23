package com.movementid.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Bump this on every entity change. History: v3 dropped providerName, v4 added productionYears,
// v5 added liftAngle, v6 added liftAngleSource, v7 added additionalPhotos, v8 added markings. Room compares the stored schema hash on open and hard-crashes if the schema
// changed without a version bump — fallbackToDestructiveMigration only runs when it increases.
@Database(entities = [MovementEntry::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun movementDao(): MovementDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "movementid.db"
                )
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
