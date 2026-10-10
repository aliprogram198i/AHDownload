package com.ahdownload.app

import android.app.Application
import com.ahdownload.app.diagnostics.DiagnosticEventStore
import com.ahdownload.app.diagnostics.PersistentDiagnosticLogger
import com.ahdownload.app.diagnostics.PersistentUiTraceLogger
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.app.download.DownloadWorkScheduler
import com.ahdownload.app.download.FileDownloadRepository
import com.ahdownload.app.performance.PerformanceLogStore

class AHDownloadApplication : Application() {
    val diagnosticEventStore: DiagnosticEventStore by lazy {
        DiagnosticEventStore(this)
    }

    val uiTraceLogger: PersistentUiTraceLogger by lazy {
        PersistentUiTraceLogger(this, diagnosticEventStore)
    }

    val diagnosticLogger: PersistentDiagnosticLogger by lazy {
        PersistentDiagnosticLogger(this, uiTraceLogger, diagnosticEventStore)
    }

    val downloadRepository: FileDownloadRepository by lazy {
        FileDownloadRepository(this)
    }

    val performanceLogStore: PerformanceLogStore by lazy {
        PerformanceLogStore(this)
    }

    val downloadWorkScheduler: DownloadWorkScheduler by lazy {
        DownloadWorkScheduler(this)
    }

    override fun onCreate() {
        super.onCreate()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                diagnosticLogger.log(
                    DiagnosticLevel.ERROR,
                    type = "APP_UNCAUGHT_EXCEPTION",
                    reason = throwable.message ?: throwable::class.simpleName.orEmpty(),
                    operation = "app.runtime",
                    context = mapOf(
                        "thread" to thread.name,
                        "thread_id" to thread.id.toString(),
                    ),
                    throwable = throwable,
                )
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
