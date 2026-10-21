package com.ahdownload.app.browser

import android.net.Uri
import com.ahdownload.domain.validation.ValidationFailure
import java.net.URI
import java.util.Locale

/**
 * Pure URL and display helpers kept outside the browser composable to make its navigation
 * and error-handling rules independently reviewable and testable.
 */
internal fun normalizeBrowserUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    if ("://" !in trimmed && (trimmed.any(Char::isWhitespace) || '.' !in trimmed)) {
        return "https://www.google.com/search?q=" + Uri.encode(trimmed)
    }
    val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    return uri.toASCIIString()
}

internal fun viewUrlOrCurrent(viewUrl: String?, fallback: String): String =
    viewUrl?.takeIf { it.isNotBlank() } ?: fallback

internal fun safeHost(url: String?): String =
    runCatching { URI(url.orEmpty()).host?.lowercase(Locale.ROOT) }
        .getOrNull()?.take(120) ?: "unknown"

internal fun ValidationFailure.toBrowserMessage(): String = when (this) {
    ValidationFailure.InvalidUrl -> "رابط المصدر غير صالح."
    ValidationFailure.ProbeFailed -> "تعذر التحقق من المصدر."
    is ValidationFailure.HttpStatus -> "رفض المصدر الطلب (HTTP $code)."
    ValidationFailure.HtmlResponse -> "المصدر أعاد صفحة ويب بدل ملف وسائط."
    ValidationFailure.ContentTypeMismatch -> "نوع الملف لا يطابق الوسائط المطلوبة."
}

internal fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.1f GB".format(Locale.ROOT, value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> "%.1f MB".format(Locale.ROOT, value / (1024.0 * 1024.0))
    value >= 1024L -> "%.0f KB".format(Locale.ROOT, value / 1024.0)
    else -> "$value B"
}
