package com.ahdownload.app

import android.app.Application
import com.ahdownload.app.download.DownloadWorkScheduler

class AHDownloadApplication : Application() {
    val downloadWorkScheduler: DownloadWorkScheduler by lazy {
        DownloadWorkScheduler(this)
    }
}
