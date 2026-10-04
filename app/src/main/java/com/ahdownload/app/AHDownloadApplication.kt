package com.ahdownload.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ahdownload.app.diagnostics.AppLogger
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class AHDownloadApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel("downloads", "AHDownload", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        // Chaquopy must be initialized with AndroidPlatform before any
        // Python.getInstance() call from the resolver or workers.
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
            AppLogger.info(this, "python.initialized", "platform=AndroidPlatform")
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.error(this, "uncaught_exception", throwable, "thread=" + thread.name)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
