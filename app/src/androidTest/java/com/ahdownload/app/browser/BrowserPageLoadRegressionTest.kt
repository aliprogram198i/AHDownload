package com.ahdownload.app.browser

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahdownload.app.MainActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserPageLoadRegressionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 90_000)
    fun browserRouteLoadsHttpPageAndRetainsWebViewAfterNavigationStateChanges() {
        val title = "AHDownload Browser Regression OK"
        val html = "<!doctype html><html><head><title>$title</title></head>" +
            "<body><h1>browser-loaded-ok</h1></body></html>"
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val server = ServerSocket(0).apply { soTimeout = 20_000 }
        val serverFailure = AtomicReference<Throwable?>(null)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    BufferedReader(
                        InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII),
                    ).use { reader ->
                        var line = reader.readLine()
                        while (line != null && line.isNotEmpty()) line = reader.readLine()
                    }
                    val headers = (
                        "HTTP/1.1 200 OK\\r\\n" +
                            "Content-Type: text/html; charset=utf-8\\r\\n" +
                            "Content-Length: ${body.size}\\r\\n" +
                            "Connection: close\\r\\n\\r\\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    socket.getOutputStream().apply {
                        write(headers)
                        write(body)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            } finally {
                runCatching { server.close() }
            }
        }.apply {
            name = "ahdownload-browser-local-http-test"
            isDaemon = true
            start()
        }

        try {
            composeRule.onNodeWithText("ابدأ الآن").performClick()
            composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
            composeRule.onNode(hasSetTextAction()).performTextReplacement(
                "http://127.0.0.1:${server.localPort}/page",
            )
            composeRule.onNode(hasSetTextAction()).performImeAction()

            composeRule.waitUntil(timeoutMillis = 25_000) {
                val observedTitle = AtomicReference<String?>(null)
                composeRule.runOnUiThread {
                    observedTitle.set(findWebView(composeRule.activity.window.decorView)?.title)
                }
                observedTitle.get() == title
            }

            val finalTitle = AtomicReference<String?>(null)
            composeRule.runOnUiThread {
                finalTitle.set(findWebView(composeRule.activity.window.decorView)?.title)
            }
            assertEquals("WebView must finish loading the local HTTP page", title, finalTitle.get())
            assertEquals("The local endpoint should return a successful response", null, serverFailure.get())
        } finally {
            runCatching { server.close() }
            serverThread.join(2_000)
        }
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
