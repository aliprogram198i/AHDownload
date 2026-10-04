package com.ahdownload.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class DownloadDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "ahdownload.db",
    null,
    2
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE downloads (
                id TEXT PRIMARY KEY NOT NULL,
                source_url TEXT NOT NULL,
                title TEXT NOT NULL,
                format_url TEXT NOT NULL,
                status TEXT NOT NULL,
                progress INTEGER NOT NULL DEFAULT 0,
                downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                total_bytes INTEGER,
                output_uri TEXT,
                thumbnail_url TEXT,
                duration_ms INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_downloads_status ON downloads(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN thumbnail_url TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN duration_ms INTEGER")
        }
    }
}
