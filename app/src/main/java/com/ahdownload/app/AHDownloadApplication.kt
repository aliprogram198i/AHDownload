package com.ahdownload.app

import android.app.Application
import com.ahdownload.app.diagnostics.PersistentDiagnosticLogger
import com.ahdownload.app.diagnostics.PersistentUiTraceLogger
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.app.download.DownloadWorkScheduler
import com.ahdownload.app.download.FileDownloadRepository

class AHDownloadApplication : Application() {
    val uiTraceLogger: PersistentUiTraceLogger by lazy {
        PersistentUiTraceLogger(this)
    }

    val diagnosticLogger: PersistentDiagnosticLogger by lazy {
        PersistentDiagnosticLogger(this, uiTraceLogger)
    }

    val downloadRepository: FileDownloadRepository by lazy {
        FileDownloadRepository(this)
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
