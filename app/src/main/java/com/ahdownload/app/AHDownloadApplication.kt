package com.ahdownload.app

import android.app.Application
import com.ahdownload.app.diagnostics.ErrorLog

class AHDownloadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorLog.install(this)
    }
}
