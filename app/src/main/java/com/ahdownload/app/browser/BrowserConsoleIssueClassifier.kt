package com.ahdownload.app.browser

import java.util.Locale

internal data class BrowserConsoleIssue(
    val type: String,
    val severity: String,
    val category: String,
    val scope: String,
    val summary: String,
)

/**
 * Separates page-owned script failures from resources rejected by a page's own CSP.
 * These events are evidence about untrusted site content; they are not automatically
 * failures of AHDownload or of the WebView renderer.
 */
internal object BrowserConsoleIssueClassifier {
    private val contentSecurityPolicyBlock = Regex(
        """(?is)(violates.{0,100}content security policy|content security policy.{0,100}(?:blocked|refused|violates)|refused to (?:load|execute).{0,180}content security policy)""",
    )

    fun classify(
        rawMessage: String,
        pageHost: String?,
        sourceHost: String?,
    ): BrowserConsoleIssue {
        val normalizedPageHost = normalizeHost(pageHost)
        val normalizedSourceHost = normalizeHost(sourceHost)
        val externalSource = normalizedPageHost.isNotBlank() &&
            normalizedSourceHost.isNotBlank() &&
            !belongsToSameHostFamily(normalizedPageHost, normalizedSourceHost)

        if (contentSecurityPolicyBlock.containsMatchIn(rawMessage)) {
            return if (externalSource) {
                BrowserConsoleIssue(
                    type = "PAGE_RESOURCE_BLOCKED_BY_SITE_CSP",
                    severity = "INFO",
                    category = "site_csp_resource_block",
                    scope = "third_party_resource",
                    summary = "منعت سياسة أمان المحتوى الخاصة بالموقع تحميل مورد خارجي؛ لا يعني ذلك وحده تعطل الصفحة.",
                )
            } else {
                BrowserConsoleIssue(
                    type = "PAGE_RESOURCE_BLOCKED_BY_SITE_CSP",
                    severity = "WARNING",
                    category = "site_csp_resource_block",
                    scope = "page_origin_or_unknown_resource",
                    summary = "منعت سياسة أمان المحتوى الخاصة بالصفحة تحميل مورد؛ تحقق من حالة عرض الصفحة قبل اعتباره عطلًا.",
                )
            }
        }

        return if (externalSource) {
            BrowserConsoleIssue(
                type = "PAGE_EXTERNAL_SCRIPT_ERROR",
                severity = "WARNING",
                category = "external_script_runtime_error",
                scope = "third_party_script",
                summary = "سجّل نص JavaScript خارجي تابع للموقع خطأ أثناء التشغيل.",
            )
        } else {
            BrowserConsoleIssue(
                type = "PAGE_CONSOLE_ERROR",
                severity = "WARNING",
                category = "page_script_runtime_error",
                scope = "page_origin_or_unknown_script",
                summary = "سجّل JavaScript الصفحة خطأ أثناء التشغيل.",
            )
        }
    }

    /**
     * Build a navigation-local key without retaining a raw URL or a credential-bearing value.
     * Common browser prefixes are removed so the same runtime error is not logged repeatedly
     * simply because one message includes an "Uncaught Error" prefix.
     */
    fun deduplicationKey(
        type: String,
        sourceHost: String?,
        line: Int,
        sanitizedMessage: String,
    ): String {
        val normalizedMessage = sanitizedMessage
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\u200B-\u200D\uFEFF]"""), "")
            .replace(
                Regex("""^\s*(?:uncaught\s+)?(?:error|typeerror|referenceerror|syntaxerror|rangeerror):\s*"""),
                "",
            )
            .replace(Regex("""\s+"""), " ")
            .trim()
        return listOf(
            type.take(80),
            normalizeHost(sourceHost),
            line.coerceAtLeast(0).toString(),
            normalizedMessage.take(220),
        ).joinToString("|")
    }

    private fun normalizeHost(value: String?): String =
        value.orEmpty().trim().trimEnd('.').lowercase(Locale.ROOT).take(253)
            .takeIf { it.isNotBlank() && it != "unknown" } ?: ""

    private fun belongsToSameHostFamily(first: String, second: String): Boolean =
        first == second || first.endsWith(".$second") || second.endsWith(".$first")
}
