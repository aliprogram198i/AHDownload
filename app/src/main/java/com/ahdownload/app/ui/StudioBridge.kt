package com.ahdownload.app.ui

import android.net.Uri

/**
 * One-shot handoff from Downloads to Smart Studio. The URI is kept only in
 * process memory; Studio still requests a real persisted/readable Android URI.
 */
object StudioBridge {
    var pendingUri: Uri? = null
}
