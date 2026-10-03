package com.ahdownload.app

import android.app.Application
import com.ahdownload.app.diagnostics.AppLogger

class AHDownloadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.error(this, "uncaught_exception", throwable, "thread=" + thread.name)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
