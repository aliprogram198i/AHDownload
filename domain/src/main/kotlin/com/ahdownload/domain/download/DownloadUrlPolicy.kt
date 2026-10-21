package com.ahdownload.domain.download

import java.net.URI
import java.util.Locale

/**
 * Transport policy for media transfers.
 *
 * HTTP browser navigation is a separate feature. Media sources handed to the download
 * engine must use HTTPS and may not embed user-info credentials in the URL.
 */
object DownloadUrlPolicy {
    fun isSecureHttpsUrl(rawUrl: String): Boolean {
        val candidate = rawUrl.trim()
        if (candidate.isEmpty()) return false
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        return uri.scheme?.lowercase(Locale.ROOT) == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.port in -1..65535
    }
}
