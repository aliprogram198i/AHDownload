package com.ahdownload.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class DownloadDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "ahdownload.db",
    null,
    7
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
                http_headers TEXT,
                audio_headers TEXT,
                speed_bps INTEGER NOT NULL DEFAULT 0,
                eta_seconds INTEGER,
                error_code TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_downloads_status ON downloads(status)")
        db.execSQL("CREATE INDEX idx_downloads_favorite ON downloads(favorite)")
        db.execSQL("CREATE INDEX idx_downloads_created_at ON downloads(created_at)")
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
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN http_headers TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN audio_headers TEXT")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE downloads ADD COLUMN media_type TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE downloads ADD COLUMN quality_label TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN codec TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN fps REAL")
            db.execSQL("ALTER TABLE downloads ADD COLUMN bitrate REAL")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_favorite ON downloads(favorite)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_created_at ON downloads(created_at)")
        }
    }
}
