package com.ahdownload.app.download

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadHistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DownloadHistoryDatabase : RoomDatabase() {
    abstract fun downloadHistoryDao(): DownloadHistoryDao

    companion object {
        const val DATABASE_NAME = "ahdownload-download-history.db"

        @Volatile
        private var instance: DownloadHistoryDatabase? = null

        fun getInstance(context: Context): DownloadHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DownloadHistoryDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { instance = it }
            }

        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
