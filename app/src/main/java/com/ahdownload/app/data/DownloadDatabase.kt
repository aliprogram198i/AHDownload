package com.ahdownload.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class DownloadDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "ahdownload.db",
    null,
    4
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
                duration_ms INTEGER,
                extension TEXT,
                merge_required INTEGER NOT NULL DEFAULT 0,
                audio_url TEXT,
                audio_extension TEXT,
                speed_bps INTEGER NOT NULL DEFAULT 0,
                eta_seconds INTEGER,
                error_code TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_downloads_status ON downloads(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN thumbnail_url TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN duration_ms INTEGER")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN speed_bps INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE downloads ADD COLUMN eta_seconds INTEGER")
            db.execSQL("ALTER TABLE downloads ADD COLUMN error_code TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN extension TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN merge_required INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE downloads ADD COLUMN audio_url TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN audio_extension TEXT")
        }
    }
}
