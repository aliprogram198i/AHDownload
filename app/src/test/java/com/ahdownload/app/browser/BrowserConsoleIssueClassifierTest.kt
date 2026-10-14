package com.ahdownload.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrowserConsoleIssueClassifierTest {
    @Test
    fun thirdPartyResourceBlockedBySiteCspIsNotReportedAsAnAppFailure() {
        val issue = BrowserConsoleIssueClassifier.classify(
            rawMessage = "Loading the script 'https://third-party.test/tag.js' violates the following Content Security Policy directive: \"default-src 'self'\".",
            pageHost = "www.example.test",
            sourceHost = "third-party.test",
        )

        assertEquals("PAGE_RESOURCE_BLOCKED_BY_SITE_CSP", issue.type)
        assertEquals("INFO", issue.severity)
        assertEquals("site_csp_resource_block", issue.category)
        assertEquals("third_party_resource", issue.scope)
    }

    @Test
    fun firstPartyResourceBlockedByCspRemainsVisibleAsWarning() {
        val issue = BrowserConsoleIssueClassifier.classify(
            rawMessage = "Refused to load script because it violates the Content Security Policy.",
            pageHost = "www.example.test",
            sourceHost = "static.example.test",
        )

        assertEquals("PAGE_RESOURCE_BLOCKED_BY_SITE_CSP", issue.type)
        assertEquals("WARNING", issue.severity)
        assertEquals("page_origin_or_unknown_resource", issue.scope)
    }

    @Test
    fun externalRuntimeErrorIsDistinguishedFromPageOriginError() {
        val external = BrowserConsoleIssueClassifier.classify(
            rawMessage = "Uncaught Error: external script failed",
            pageHost = "amp.example.test",
            sourceHost = "cdn.vendor.test",
        )
        val firstParty = BrowserConsoleIssueClassifier.classify(
            rawMessage = "Uncaught TypeError: page script failed",
            pageHost = "www.example.test",
            sourceHost = "static.example.test",
        )

        assertEquals("PAGE_EXTERNAL_SCRIPT_ERROR", external.type)
        assertEquals("third_party_script", external.scope)
        assertEquals("PAGE_CONSOLE_ERROR", firstParty.type)
        assertEquals("page_origin_or_unknown_script", firstParty.scope)
    }

    @Test
    fun relatedSubdomainsAreNotClassifiedAsThirdParty() {
        val issue = BrowserConsoleIssueClassifier.classify(
            rawMessage = "Uncaught TypeError: cannot read property",
            pageHost = "www.example.test",
            sourceHost = "cdn.example.test",
        )

        assertEquals("PAGE_CONSOLE_ERROR", issue.type)
        assertEquals("WARNING", issue.severity)
    }

    @Test
    fun compoundPublicSuffixesDoNotMergeUnrelatedSites() {
        val sameSite = BrowserConsoleIssueClassifier.classify(
            rawMessage = "Uncaught TypeError: same site runtime error",
            pageHost = "www.example.co.uk",
            sourceHost = "static.example.co.uk",
        )
        val otherSite = BrowserConsoleIssueClassifier.classify(
            rawMessage = "Uncaught Error: unrelated runtime error",
            pageHost = "www.example.co.uk",
            sourceHost = "cdn.other.co.uk",
        )

        assertEquals("PAGE_CONSOLE_ERROR", sameSite.type)
        assertEquals("PAGE_EXTERNAL_SCRIPT_ERROR", otherSite.type)
    }

    @Test
    fun duplicateKeyIgnoresCommonUncaughtErrorPrefixAndCase() {
        val first = BrowserConsoleIssueClassifier.deduplicationKey(
            type = "PAGE_EXTERNAL_SCRIPT_ERROR",
            sourceHost = "CDN.VENDOR.TEST",
            line = 2,
            sanitizedMessage = "Uncaught Error: The first argument to CLIENT_ID is required",
        )
        val repeated = BrowserConsoleIssueClassifier.deduplicationKey(
            type = "PAGE_EXTERNAL_SCRIPT_ERROR",
            sourceHost = "cdn.vendor.test",
            line = 2,
            sanitizedMessage = "the first argument to client_id is required\u200B\u200B",
        )
        val otherLine = BrowserConsoleIssueClassifier.deduplicationKey(
            type = "PAGE_EXTERNAL_SCRIPT_ERROR",
            sourceHost = "cdn.vendor.test",
            line = 3,
            sanitizedMessage = "the first argument to client_id is required",
        )

        assertEquals(first, repeated)
        assertNotEquals(first, otherLine)
    }
}
