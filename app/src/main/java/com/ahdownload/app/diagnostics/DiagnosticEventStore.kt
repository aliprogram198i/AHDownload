package com.ahdownload.app.diagnostics

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import com.ahdownload.core.common.UiTraceEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The single local persistence boundary for app diagnostics and UI trace events.
 *
 * UI/INFO/WARNING writes are serialized on a private executor. ERROR writes are
 * drained and committed synchronously so a crash does not silently lose the
 * final incident. Existing SharedPreferences logs are migrated once and removed
 * only after the SQLite transaction and migration marker succeed.
 */
class DiagnosticEventStore(context: Context) {
    private val appContext = context.applicationContext
    private val helper = DiagnosticDatabaseHelper(appContext)
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AHDownload-Diagnostics").apply { isDaemon = true }
    }
    private val sequence = AtomicLong(0L)
    val sessionId: String = UUID.randomUUID().toString()

    private val gson = Gson()
    private val contextType = object : TypeToken<Map<String, String>>() {}.type
    private val diagnosticListType = object : TypeToken<List<DiagnosticLog>>() {}.type
    private val uiTraceListType = object : TypeToken<List<UiTraceEvent>>() {}.type

    init {
        helper.setWriteAheadLoggingEnabled(true)
        migrateLegacyData()
    }

    fun nextSequence(): Long = sequence.incrementAndGet()

    fun appendDiagnostic(log: DiagnosticLog) {
        val event = StoredEvent(
            id = log.id,
            timestampEpochMs = log.timestampEpochMs,
            kind = KIND_DIAGNOSTIC,
            sequence = log.context["event_sequence"]?.toLongOrNull() ?: nextSequence(),
            sessionId = log.context["diagnostic_session_id"] ?: sessionId,
            level = log.level,
            diagnosticType = log.type,
            reason = log.reason,
            operation = log.operation,
            context = log.context,
            throwableType = log.throwableType,
            throwableMessage = log.throwableMessage,
            throwableStackTrace = log.throwableStackTrace,
        )
        enqueue(event, critical = log.level == DiagnosticLevel.ERROR)
    }

    fun appendUiTrace(event: UiTraceEvent) {
        enqueue(
            StoredEvent(
                id = event.id,
                timestampEpochMs = event.timestampEpochMs,
                kind = KIND_UI_TRACE,
                sequence = event.sequence,
                sessionId = event.sessionId,
                level = event.level,
                screen = event.screen,
                component = event.component,
                eventName = event.event,
                state = event.state,
                context = event.context,
            ),
            critical = event.level == DiagnosticLevel.ERROR,
        )
    }

    fun listDiagnostics(limit: Int = MAX_DIAGNOSTIC_EVENTS): List<DiagnosticLog> {
        flushPendingWrites()
        return query(KIND_DIAGNOSTIC, limit).map { row ->
            DiagnosticLog(
                id = row.id,
                timestampEpochMs = row.timestampEpochMs,
                level = row.level,
                type = row.diagnosticType ?: "UNKNOWN",
                reason = row.reason ?: "",
                operation = row.operation ?: "",
                context = row.context,
                throwableType = row.throwableType,
                throwableMessage = row.throwableMessage,
                throwableStackTrace = row.throwableStackTrace,
            )
        }
    }

    fun listUiTraces(limit: Int = MAX_UI_TRACE_EVENTS): List<UiTraceEvent> {
        flushPendingWrites()
        return query(KIND_UI_TRACE, limit).map { row ->
            UiTraceEvent(
                id = row.id,
                timestampEpochMs = row.timestampEpochMs,
                sessionId = row.sessionId,
                sequence = row.sequence,
                level = row.level,
                screen = row.screen ?: "UNKNOWN",
                component = row.component ?: "UNKNOWN",
                event = row.eventName ?: "UNKNOWN",
                state = row.state,
                context = row.context,
            )
        }
    }

    /** Deletes diagnostics and UI traces together, intentionally. */
    fun clear() {
        flushPendingWrites()
        synchronized(lock) {
            helper.writableDatabase.delete(TABLE_EVENTS, null, null)
        }
    }

    private fun enqueue(event: StoredEvent, critical: Boolean) {
        val task = Runnable {
            synchronized(lock) {
                val database = helper.writableDatabase
                database.beginTransaction()
                try {
                    insert(database, event)
                    prune(database)
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }
        if (critical) {
            flushPendingWrites()
            runCatching { task.run() }
        } else {
            runCatching {
                writer.execute { runCatching { task.run() } }
            }
        }
    }

    private fun flushPendingWrites() {
        runCatching { writer.submit {}.get(3, TimeUnit.SECONDS) }
    }

    private fun insert(database: SQLiteDatabase, event: StoredEvent) {
        val values = ContentValues().apply {
            put("id", event.id)
            put("timestamp_epoch_ms", event.timestampEpochMs)
            put("event_kind", event.kind)
            put("sequence_no", event.sequence)
            put("session_id", event.sessionId)
            put("level", event.level.name)
            putNullable("screen", event.screen)
            putNullable("component", event.component)
            putNullable("event_name", event.eventName)
            putNullable("state", event.state)
            putNullable("diagnostic_type", event.diagnosticType)
            putNullable("reason", event.reason)
            putNullable("operation", event.operation)
            put("context_json", gson.toJson(DiagnosticDataSanitizer.sanitizeContext(event.context)))
            putNullable("throwable_type", event.throwableType)
            putNullable("throwable_message", event.throwableMessage)
            putNullable("throwable_stack_trace", event.throwableStackTrace)
        }
        database.insertWithOnConflict(TABLE_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, DiagnosticDataSanitizer.sanitizeText(value))
    }

    private fun prune(database: SQLiteDatabase) {
        database.execSQL(
            """
            DELETE FROM $TABLE_EVENTS
            WHERE id IN (
                SELECT id FROM $TABLE_EVENTS
                ORDER BY timestamp_epoch_ms DESC, sequence_no DESC
                LIMIT -1 OFFSET $MAX_STORED_EVENTS
            )
            """.trimIndent(),
        )
    }

    private fun query(kind: String, limit: Int): List<StoredEvent> = synchronized(lock) {
        val result = ArrayList<StoredEvent>()
        helper.readableDatabase.query(
            TABLE_EVENTS,
            null,
            "event_kind = ?",
            arrayOf(kind),
            null,
            null,
            "timestamp_epoch_ms DESC, sequence_no DESC",
            limit.coerceIn(1, MAX_STORED_EVENTS).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toStoredEvent()
        }
        result
    }

    private fun Cursor.toStoredEvent(): StoredEvent {
        fun text(name: String): String? = getString(getColumnIndexOrThrow(name))
        fun number(name: String): Long = getLong(getColumnIndexOrThrow(name))
        val parsedContext = runCatching {
            gson.fromJson<Map<String, String>>(text("context_json"), contextType) ?: emptyMap()
        }.getOrDefault(emptyMap())
        val level = runCatching {
            DiagnosticLevel.valueOf(text("level") ?: DiagnosticLevel.INFO.name)
        }.getOrDefault(DiagnosticLevel.INFO)
        return StoredEvent(
            id = text("id") ?: UUID.randomUUID().toString(),
            timestampEpochMs = number("timestamp_epoch_ms"),
            kind = text("event_kind") ?: KIND_DIAGNOSTIC,
            sequence = number("sequence_no"),
            sessionId = text("session_id") ?: "unknown",
            level = level,
            screen = text("screen"),
            component = text("component"),
            eventName = text("event_name"),
            state = text("state"),
            diagnosticType = text("diagnostic_type"),
            reason = text("reason"),
            operation = text("operation"),
            context = DiagnosticDataSanitizer.sanitizeContext(parsedContext),
            throwableType = text("throwable_type"),
            throwableMessage = text("throwable_message")?.let(DiagnosticDataSanitizer::sanitizeText),
            throwableStackTrace = text("throwable_stack_trace")?.let(DiagnosticDataSanitizer::sanitizeText),
        )
    }

    private fun migrateLegacyData() {
        val migrationPreferences = appContext.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        val diagnosticsPreferences = appContext.getSharedPreferences(LEGACY_DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
        val uiPreferences = appContext.getSharedPreferences(LEGACY_UI_PREFS, Context.MODE_PRIVATE)

        if (migrationPreferences.getBoolean(MIGRATION_COMPLETE, false)) {
            removeLegacyKeys(diagnosticsPreferences, uiPreferences)
            return
        }

        val legacyLogs = runCatching {
            gson.fromJson<List<DiagnosticLog>>(
                diagnosticsPreferences.getString(LEGACY_DIAGNOSTIC_KEY, null),
                diagnosticListType,
            ) ?: emptyList()
        }.getOrDefault(emptyList())

        val legacyUiEvents = runCatching {
            gson.fromJson<List<UiTraceEvent>>(
                uiPreferences.getString(LEGACY_UI_KEY, null),
                uiTraceListType,
            ) ?: emptyList()
        }.getOrDefault(emptyList())

        val legacyEntries = parseLegacyEntries(diagnosticsPreferences.getString(LEGACY_ENTRIES_KEY, null))

        synchronized(lock) {
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                legacyLogs.forEach { log ->
                    val safe = DiagnosticDataSanitizer.sanitizeDiagnosticLog(log)
                    insert(
                        database,
                        StoredEvent(
                            id = safe.id,
                            timestampEpochMs = safe.timestampEpochMs,
                            kind = KIND_DIAGNOSTIC,
                            sequence = safe.context["event_sequence"]?.toLongOrNull() ?: safe.timestampEpochMs,
                            sessionId = safe.context["diagnostic_session_id"] ?: "legacy-diagnostics",
                            level = safe.level,
                            diagnosticType = safe.type,
                            reason = safe.reason,
                            operation = safe.operation,
                            context = safe.context,
                            throwableType = safe.throwableType,
                            throwableMessage = safe.throwableMessage,
                            throwableStackTrace = safe.throwableStackTrace,
                        ),
                    )
                }
                legacyEntries.forEach { log ->
                    insert(
                        database,
                        StoredEvent(
                            id = log.id,
                            timestampEpochMs = log.timestampEpochMs,
                            kind = KIND_DIAGNOSTIC,
                            sequence = log.timestampEpochMs,
                            sessionId = "legacy-diagnostics",
                            level = log.level,
                            diagnosticType = log.type,
                            reason = log.reason,
                            operation = log.operation,
                            context = log.context,
                            throwableType = log.throwableType,
                            throwableMessage = log.throwableMessage,
                            throwableStackTrace = log.throwableStackTrace,
                        ),
                    )
                }
                legacyUiEvents.forEach { trace ->
                    val safe = DiagnosticDataSanitizer.sanitizeUiEvent(trace)
                    insert(
                        database,
                        StoredEvent(
                            id = safe.id,
                            timestampEpochMs = safe.timestampEpochMs,
                            kind = KIND_UI_TRACE,
                            sequence = safe.sequence,
                            sessionId = safe.sessionId,
                            level = safe.level,
                            screen = safe.screen,
                            component = safe.component,
                            eventName = safe.event,
                            state = safe.state,
                            context = safe.context,
                        ),
                    )
                }
                prune(database)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }

        // The marker is committed only after the SQLite transaction succeeds.
        if (migrationPreferences.edit().putBoolean(MIGRATION_COMPLETE, true).commit()) {
            removeLegacyKeys(diagnosticsPreferences, uiPreferences)
        }
    }

    private fun parseLegacyEntries(raw: String?): List<DiagnosticLog> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val timestamp = item.optLong("timestamp", System.currentTimeMillis())
                    val details = item.optString("detail", "").takeIf(String::isNotBlank)
                    add(
                        DiagnosticLog(
                            id = "legacy-$timestamp-$index",
                            timestampEpochMs = timestamp,
                            level = DiagnosticLevel.ERROR,
                            type = DiagnosticDataSanitizer.sanitizeText(item.optString("type", "LEGACY")),
                            reason = DiagnosticDataSanitizer.sanitizeText(item.optString("reason", "Unknown legacy error")),
                            operation = "legacy.facade",
                            context = emptyMap(),
                            throwableType = null,
                            throwableMessage = details?.let(DiagnosticDataSanitizer::sanitizeText),
                            throwableStackTrace = null,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun removeLegacyKeys(
        diagnosticsPreferences: SharedPreferences,
        uiPreferences: SharedPreferences,
    ) {
        diagnosticsPreferences.edit()
            .remove(LEGACY_DIAGNOSTIC_KEY)
            .remove(LEGACY_ENTRIES_KEY)
            .commit()
        uiPreferences.edit().remove(LEGACY_UI_KEY).commit()
    }

    private data class StoredEvent(
        val id: String,
        val timestampEpochMs: Long,
        val kind: String,
        val sequence: Long,
        val sessionId: String,
        val level: DiagnosticLevel,
        val screen: String? = null,
        val component: String? = null,
        val eventName: String? = null,
        val state: String? = null,
        val diagnosticType: String? = null,
        val reason: String? = null,
        val operation: String? = null,
        val context: Map<String, String> = emptyMap(),
        val throwableType: String? = null,
        val throwableMessage: String? = null,
        val throwableStackTrace: String? = null,
    )

    private class DiagnosticDatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE $TABLE_EVENTS (
                    id TEXT PRIMARY KEY NOT NULL,
                    timestamp_epoch_ms INTEGER NOT NULL,
                    event_kind TEXT NOT NULL,
                    sequence_no INTEGER NOT NULL,
                    session_id TEXT NOT NULL,
                    level TEXT NOT NULL,
                    screen TEXT,
                    component TEXT,
                    event_name TEXT,
                    state TEXT,
                    diagnostic_type TEXT,
                    reason TEXT,
                    operation TEXT,
                    context_json TEXT NOT NULL,
                    throwable_type TEXT,
                    throwable_message TEXT,
                    throwable_stack_trace TEXT
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX idx_diagnostic_events_time ON $TABLE_EVENTS(timestamp_epoch_ms DESC)")
            database.execSQL("CREATE INDEX idx_diagnostic_events_kind_time ON $TABLE_EVENTS(event_kind, timestamp_epoch_ms DESC)")
            database.execSQL("CREATE INDEX idx_diagnostic_events_session ON $TABLE_EVENTS(session_id, sequence_no)")
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Explicit ALTER TABLE migrations must be added for future schema changes.
            // Diagnostic history is never dropped automatically.
        }
    }

    companion object {
        private const val DATABASE_NAME = "ahdownload_diagnostics.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_EVENTS = "diagnostic_events"
        private const val KIND_DIAGNOSTIC = "DIAGNOSTIC"
        private const val KIND_UI_TRACE = "UI_TRACE"
        private const val MAX_STORED_EVENTS = 2_000
        private const val MAX_DIAGNOSTIC_EVENTS = 500
        private const val MAX_UI_TRACE_EVENTS = 800

        private const val LEGACY_DIAGNOSTICS_PREFS = "ahdownload_diagnostics"
        private const val LEGACY_DIAGNOSTIC_KEY = "logs"
        private const val LEGACY_ENTRIES_KEY = "entries"
        private const val LEGACY_UI_PREFS = "ahdownload_ui_trace"
        private const val LEGACY_UI_KEY = "events"
        private const val MIGRATION_PREFS = "ahdownload_diagnostic_center_migration"
        private const val MIGRATION_COMPLETE = "sqlite_v1_complete"
    }
}

/** Shared redaction policy for persistence and diagnostic exports. */
object DiagnosticDataSanitizer {
    private val sensitiveKey = Regex(
        "(cookie|set[-_]?cookie|authorization|token|password|passwd|secret|signature|api[_-]?key|credential|session[_-]?id|private[_-]?key|^url$|_url$|^uri$|_uri$|^link$|_link$)",
        RegexOption.IGNORE_CASE,
    )
    private val urlPattern = Regex("(?i)https?://[^\\s<>\\\"']+")
    private val secretValuePattern = Regex(
        "(?i)(cookie|set-cookie|authorization|token|password|passwd|secret|signature|sig|api[_-]?key|po[_-]?token)(\\s*[:=]\\s*)[^\\s&;,]+",
    )

    fun sanitizeContext(context: Map<String, String>): Map<String, String> =
        context.mapValues { (key, value) ->
            if (sensitiveKey.containsMatchIn(key)) "[REDACTED]" else sanitizeText(value)
        }

    fun sanitizeText(value: String): String {
        val withoutUrls = urlPattern.replace(value, "[URL_REDACTED]")
        return secretValuePattern.replace(withoutUrls, "$1$2[REDACTED]")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(12_000)
    }

    fun sanitizeDiagnosticLog(log: DiagnosticLog): DiagnosticLog = log.copy(
        type = sanitizeText(log.type),
        reason = sanitizeText(log.reason),
        operation = sanitizeText(log.operation),
        context = sanitizeContext(log.context),
        throwableMessage = log.throwableMessage?.let(::sanitizeText),
        throwableStackTrace = log.throwableStackTrace?.let(::sanitizeText),
    )

    fun sanitizeUiEvent(event: UiTraceEvent): UiTraceEvent = event.copy(
        screen = sanitizeText(event.screen),
        component = sanitizeText(event.component),
        event = sanitizeText(event.event),
        state = event.state?.let(::sanitizeText),
        context = sanitizeContext(event.context),
    )
}
