package com.ahdownload.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class DownloadDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "ahdownload.db",
    null,
    8
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
                error_code TEXT,
                favorite INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                media_type TEXT NOT NULL DEFAULT 'UNKNOWN',
                quality_label TEXT,
                codec TEXT,
                fps REAL,
                bitrate REAL
            )
        """.trimIndent())
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            addColumnIfMissing(db, "thumbnail_url", "TEXT")
            addColumnIfMissing(db, "duration_ms", "INTEGER")
        }
        if (oldVersion < 3) {
            addColumnIfMissing(db, "speed_bps", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "eta_seconds", "INTEGER")
            addColumnIfMissing(db, "error_code", "TEXT")
        }
        if (oldVersion < 4) {
            addColumnIfMissing(db, "extension", "TEXT")
            addColumnIfMissing(db, "merge_required", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "audio_url", "TEXT")
            addColumnIfMissing(db, "audio_extension", "TEXT")
        }
        if (oldVersion < 5) {
            addColumnIfMissing(db, "http_headers", "TEXT")
            addColumnIfMissing(db, "audio_headers", "TEXT")
        }
        if (oldVersion < 6) {
            addColumnIfMissing(db, "favorite", "INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) {
            addColumnIfMissing(db, "created_at", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "media_type", "TEXT NOT NULL DEFAULT 'UNKNOWN'")
            addColumnIfMissing(db, "quality_label", "TEXT")
            addColumnIfMissing(db, "codec", "TEXT")
            addColumnIfMissing(db, "fps", "REAL")
            addColumnIfMissing(db, "bitrate", "REAL")
        }
        if (oldVersion < 8) {
            // Repair databases created by the v7 onCreate schema, which referenced
            // favorite/created_at indexes before declaring those columns.
            addColumnIfMissing(db, "favorite", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "created_at", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "media_type", "TEXT NOT NULL DEFAULT 'UNKNOWN'")
            addColumnIfMissing(db, "quality_label", "TEXT")
            addColumnIfMissing(db, "codec", "TEXT")
            addColumnIfMissing(db, "fps", "REAL")
            addColumnIfMissing(db, "bitrate", "REAL")
        }
        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_status ON downloads(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_favorite ON downloads(favorite)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_created_at ON downloads(created_at)")
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, name: String, definition: String) {
        if (!hasColumn(db, name)) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN $name $definition")
        }
    }

    private fun hasColumn(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("PRAGMA table_info(downloads)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals(name, ignoreCase = true)) {
                    return@use true
                }
            }
            false
        }
}
