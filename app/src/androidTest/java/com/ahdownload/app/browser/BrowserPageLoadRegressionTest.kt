package com.ahdownload.app.browser

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahdownload.app.MainActivity
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
