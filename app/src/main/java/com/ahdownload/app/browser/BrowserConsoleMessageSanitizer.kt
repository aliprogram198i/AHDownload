package com.ahdownload.app.browser

/**
 * Sanitizes untrusted WebView console messages before they enter browser diagnostics.
 * URLs and credential-like values are removed while retaining useful error text.
 */
internal object BrowserConsoleMessageSanitizer {
    private const val MAX_MESSAGE_LENGTH = 220

    private val urlPattern = Regex("""(?i)https?://[^\s<>"']+""")
    private val authorizationSchemePattern = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val secretAssignmentPattern = Regex(
        """(?i)\b(cookie|set-cookie|authorization|proxy-authorization|access[_-]?token|refresh[_-]?token|id[_-]?token|token|password|passwd|secret|session(?:id)?|csrf(?:token)?|signature|sig|jwt)\b\s*[:=]\s*(?:(?:bearer|basic)\s+)?(?:"[^"]*"|'[^']*'|[^\s,;&]+)""",
    )

    fun sanitize(rawMessage: String): String {
        val sanitized = rawMessage
            .replace(urlPattern, "[url]")
            .replace(authorizationSchemePattern) { match ->
                "${match.groupValues[1]} [redacted]"
            }
            .replace(secretAssignmentPattern) { match ->
                "${match.groupValues[1]}=[redacted]"
            }
            .filter { it == '\n' || it == '\t' || it >= ' ' }
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_MESSAGE_LENGTH)

        return sanitized.ifBlank { "Console error message unavailable" }
    }
}
