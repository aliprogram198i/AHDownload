package com.ahdownload.app.browser

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahdownload.app.MainActivity
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserPageLoadRegressionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 90_000)
    fun browserRouteLoadsPublicHttpsPageAndRetainsWebViewAfterNavigationStateChanges() {
        val targetUrl = "https://example.com/"
        val expectedTitle = "Example Domain"

        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
        composeRule.onNodeWithText("AHDownload Browser").assertIsDisplayed()
        composeRule.onNodeWithText("عنوان الموقع").assertIsDisplayed()
        val browserStore = BrowserErrorLogStore(composeRule.activity.applicationContext)
        browserStore.clear()

        composeRule.onNode(hasSetTextAction()).performTextReplacement(targetUrl)
        composeRule.onNodeWithContentDescription("فتح العنوان").performClick()

        val loadedState = AtomicReference<Pair<String?, String?>?>(null)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.runOnUiThread {
                val webView = findWebView(composeRule.activity.window.decorView)
                loadedState.set(webView?.url to webView?.title)
            }
            loadedState.get()?.let { (url, title) ->
                url?.startsWith("https://example.com") == true && title == expectedTitle
            } == true
        }

        val finalState = AtomicReference<Pair<String?, String?>?>(null)
        composeRule.runOnUiThread {
            val webView = findWebView(composeRule.activity.window.decorView)
            finalState.set(webView?.url to webView?.title)
        }
        assertNotNull("The browser must retain its WebView after navigation", finalState.get())
        assertEquals(
            "WebView must finish loading the public HTTPS page",
            targetUrl to expectedTitle,
            finalState.get(),
        )

        composeRule.waitUntil(timeoutMillis = 15_000) {
            browserStore.list().any { it.type == "PAGE_RENDER_CHECK" && it.host == "example.com" }
        }
        val renderEvent = browserStore.list().first { it.type == "PAGE_RENDER_CHECK" && it.host == "example.com" }
        assertEquals("CONTENT_SIGNALS_DETECTED", renderEvent.details["render_status"])
        assertEquals("complete", renderEvent.details["ready_state"])
        assertTrue((renderEvent.details["body_text_chars"]?.toIntOrNull() ?: 0) > 0)
        assertTrue(
            "The diagnostic must count text actually intersecting the viewport",
            (renderEvent.details["visible_text_chars"]?.toIntOrNull() ?: 0) > 0,
        )
        assertEquals(
            "viewport_intersection_visible_style",
            renderEvent.details["content_visibility_basis"].substringBefore("_contrast_and_hit_test"),
        )

        composeRule.waitUntil(timeoutMillis = 15_000) {
            browserStore.list().any { it.type == "PAGE_RENDER_RECHECK" && it.host == "example.com" }
        }
        val recheckEvent = browserStore.list().first {
            it.type == "PAGE_RENDER_RECHECK" && it.host == "example.com"
        }
        assertEquals("CONTENT_SIGNALS_DETECTED", recheckEvent.details["render_status"])
        assertEquals("1400", recheckEvent.details["recheck_delay_ms"])
        assertTrue((recheckEvent.details["readable_visible_text_chars"]?.toIntOrNull() ?: 0) >= 12)

        composeRule.onNodeWithText("فحص الخيارات").performClick()
        composeRule.waitUntil(timeoutMillis = 40_000) {
            browserStore.list().any {
                it.type == "OPTIONS_INSPECTION_COMPLETED" ||
                    it.type == "OPTIONS_INSPECTION_FAILED" ||
                    it.type == "OPTIONS_INSPECTION_EXCEPTION"
            }
        }
        val inspectionEvent = browserStore.list().first {
            it.type == "OPTIONS_INSPECTION_COMPLETED" ||
                it.type == "OPTIONS_INSPECTION_FAILED" ||
                it.type == "OPTIONS_INSPECTION_EXCEPTION"
        }
        assertEquals("example.com", inspectionEvent.host)
        assertTrue(inspectionEvent.details["operation_id"]?.startsWith("browser-") == true)

    }

    @Test(timeout = 30_000)
    fun browserStartsWithConfiguredHomeUrlInsteadOfBlankWebView() {
        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
        composeRule.onNodeWithText("AHDownload Browser").assertIsDisplayed()

        val loadedUrl = AtomicReference<String?>(null)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.runOnUiThread {
                loadedUrl.set(findWebView(composeRule.activity.window.decorView)?.url)
            }
            loadedUrl.get()?.startsWith("https://") == true
        }
        assertNotNull("Browser must initiate its configured home navigation on entry", loadedUrl.get())
    }


    @Test(timeout = 30_000)
    fun browserErrorLogRedactsQueryStringsAndCredentialLikeValues() {
        val browserStore = BrowserErrorLogStore(composeRule.activity.applicationContext)
        browserStore.clear()
        browserStore.record(
            type = "REDACTION_TEST",
            message = "Request failed with access_token=sample_secret",
            stage = "test",
            url = "https://example.com/path?token=sample_secret#private",
            severity = "WARNING",
            pageTitle = "Sample page",
            details = mapOf("authorization" to "Bearer sample_secret", "result" to "failure"),
        )
        val entry = browserStore.list().first()
        assertEquals("https://example.com/path", entry.safeUrl)
        val export = browserStore.exportText()
        assertTrue(export.contains("authorization=[redacted]"))
        assertTrue(!export.contains("sample_secret"))
        assertTrue(!export.contains("?token="))
        browserStore.clear()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            val found = findWebView(view.getChildAt(index))
            if (found != null) return found
        }
        return null
    }
}
