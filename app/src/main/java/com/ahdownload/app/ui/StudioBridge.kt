package com.ahdownload.app.ui

import android.net.Uri

/**
 * One-shot in-process handoff from Downloads to Smart Studio.
 */
object StudioBridge {
    var pendingUri: Uri? = null
}
