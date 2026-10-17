package com.ahdownload.app.browser

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahdownload.app.MainActivity
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
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
    fun browserRouteRendersDeterministicFixtureAndRetainsWebViewAfterNavigationStateChanges() {
        val baseUrl = "https://example.com/"
        val expectedTitle = "AH Browser Render Fixture"

        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
        composeRule.onNodeWithText("AHDownload Browser").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("لا يوجد موقع مفتوح").assertIsDisplayed()
        composeRule.onNodeWithText("عنوان الموقع").assertIsDisplayed()
        org.junit.Assert.assertEquals(
            "The editable browser address must start empty",
            "",
            composeRule.onNodeWithTag("browser-address-field")
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text.toString(),
        )
        waitForBlankBrowser()
        val browserStore = BrowserErrorLogStore(composeRule.activity.applicationContext)
        browserStore.clear()

        val fixtureHtml = """
            <!doctype html>
            <html lang="en">
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>AH Browser Render Fixture</title>
                <style>
                  body { margin: 0; background: #fff; color: #111; font: 16px sans-serif; }
                  header, main { padding: 16px; }
                  nav { display: flex; gap: 12px; align-items: center; }
                  button, input, a { min-height: 32px; }
                </style>
              </head>
              <body>
                <header><nav aria-label="Primary navigation">
                  <button id="fixture-menu" aria-expanded="false" aria-controls="fixture-panel">Menu</button>
                  <a href="#main-content">Home</a>
                </nav></header>
                <main id="main-content">
                  <h1>AH Browser Render Fixture</h1>
                  <p>Visible deterministic text confirms that the WebView content occupies the page viewport.</p>
                  <a href="#fixture-link">Visible fixture link</a>
                  <input type="search" aria-label="Fixture search" placeholder="Search fixture">
                  <section id="fixture-panel" hidden>Fixture navigation panel</section>
                </main>
              </body>
            </html>
        """.trimIndent()

        val webViewRef = AtomicReference<WebView?>(null)
        composeRule.runOnUiThread {
            val view = findWebView(composeRule.activity.window.decorView)
            webViewRef.set(view)
            view?.loadDataWithBaseURL(baseUrl, fixtureHtml, "text/html", "UTF-8", baseUrl)
        }
        assertNotNull("Browser must retain a WebView instance", webViewRef.get())

        val fixtureSnapshotScript = """
            (function() {
              const main = document.getElementById("main-content");
              return JSON.stringify({
                readyState: document.readyState,
                title: document.title,
                heading: main ? (main.querySelector("h1")?.textContent || "") : "",
                text: main ? (main.innerText || "") : ""
              });
            })()
        """.trimIndent()
        val fixtureDeadline = SystemClock.elapsedRealtime() + 15_000L
        var renderedSnapshot: JSONObject? = null
        while (renderedSnapshot == null && SystemClock.elapsedRealtime() < fixtureDeadline) {
            val evaluated = AtomicReference<JSONObject?>(null)
            composeRule.runOnUiThread {
                webViewRef.get()?.evaluateJavascript(fixtureSnapshotScript) { raw ->
                    val decoded = runCatching {
                        JSONTokener(raw.orEmpty()).nextValue() as? String
                    }.getOrNull()
                    evaluated.set(decoded?.let { runCatching { JSONObject(it) }.getOrNull() })
                }
            }
            val callbackDeadline = minOf(fixtureDeadline, SystemClock.elapsedRealtime() + 500L)
            while (evaluated.get() == null && SystemClock.elapsedRealtime() < callbackDeadline) {
                SystemClock.sleep(25L)
            }
            val candidate = evaluated.get()
            if (candidate != null &&
                candidate.optString("readyState") == "complete" &&
                candidate.optString("title") == expectedTitle &&
                candidate.optString("heading") == expectedTitle &&
                candidate.optString("text").contains("Visible deterministic text confirms")
            ) {
                renderedSnapshot = candidate
            } else {
                SystemClock.sleep(100L)
            }
        }
        assertNotNull(
            "The WebView DOM must contain the rendered deterministic fixture (title, heading and body text)",
            renderedSnapshot,
        )
        assertEquals(expectedTitle, renderedSnapshot!!.optString("title"))
        assertEquals(expectedTitle, renderedSnapshot!!.optString("heading"))

        val retainedWebView = AtomicReference<WebView?>(null)
        composeRule.runOnUiThread {
            retainedWebView.set(findWebView(composeRule.activity.window.decorView))
        }
        assertTrue(
            "The same WebView instance must remain mounted after fixture rendering",
            retainedWebView.get() === webViewRef.get(),
        )

        composeRule.waitUntil(timeoutMillis = 15_000) {
            browserStore.list().any { it.type == "PAGE_RENDER_CHECK" && it.host == "example.com" }
        }
        val renderEvent = browserStore.list().first { it.type == "PAGE_RENDER_CHECK" && it.host == "example.com" }
        assertEquals("CONTENT_SIGNALS_DETECTED", renderEvent.details["render_status"])
        assertTrue((renderEvent.details["body_text_chars"]?.toIntOrNull() ?: 0) > 0)
        assertTrue(
            "The diagnostic must count text actually intersecting the viewport",
            (renderEvent.details["visible_text_chars"]?.toIntOrNull() ?: 0) > 0,
        )
        assertTrue(
            "Visible text must contribute at least one visible text-host element",
            (renderEvent.details["visible_text_host_element_count"]?.toIntOrNull() ?: 0) > 0,
        )
        assertTrue(
            "A visible main/article fixture must contribute a semantic-container element",
            (renderEvent.details["visible_semantic_container_count"]?.toIntOrNull() ?: 0) > 0,
        )
        assertTrue(
            "The overall visible-content count must include visible text hosts, not only semantic containers",
            (renderEvent.details["visible_content_element_count"]?.toIntOrNull() ?: 0) > 0,
        )
        assertEquals(
            "unique_visible_text_hosts_semantic_containers_and_renderable_media",
            renderEvent.details["visible_content_element_scope"],
        )
        assertEquals("false", renderEvent.details["challenge_detected"])
        assertEquals(
            "viewport_intersection_visible_style_contrast_and_hit_test",
            renderEvent.details["content_visibility_basis"],
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
        assertTrue((recheckEvent.details["visible_text_host_element_count"]?.toIntOrNull() ?: 0) > 0)
        assertTrue((recheckEvent.details["visible_semantic_container_count"]?.toIntOrNull() ?: 0) > 0)
        assertTrue((recheckEvent.details["visible_content_element_count"]?.toIntOrNull() ?: 0) > 0)
        assertEquals(
            "unique_visible_text_hosts_semantic_containers_and_renderable_media",
            recheckEvent.details["visible_content_element_scope"],
        )
        val recheckEvents = browserStore.list().filter {
            it.type == "PAGE_RENDER_RECHECK" && it.host == "example.com"
        }
        assertTrue(
            "Each navigation may emit at most one delayed render recheck",
            recheckEvents.groupBy { it.details["navigation_id"] }
                .values.all { eventsForNavigation -> eventsForNavigation.size == 1 },
        )

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

    @Test(timeout = 45_000)
    fun hitTestAcceptsTheAncestorWrappingAnImageWhenChildIsNotHitTarget() {
        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
        composeRule.onNodeWithText("AHDownload Browser").assertIsDisplayed()

        waitForBlankBrowser()
        val targetUrl = "https://example.com/"
        composeRule.onNodeWithTag("browser-address-field").performTextReplacement(targetUrl)
        composeRule.onNodeWithContentDescription("فتح العنوان").performClick()

        val pageLoaded = AtomicReference(false)
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.runOnUiThread {
                val view = findWebView(composeRule.activity.window.decorView)
                pageLoaded.set(view?.url?.startsWith("https://example.com") == true &&
                    view?.title == "Example Domain")
            }
            pageLoaded.get()
        }

        val result = AtomicReference<String?>(null)
        val script = """
            (function() {
              const anchor = document.createElement('a');
              anchor.href = '#hit-test-fixture';
              anchor.style.cssText = 'position:fixed;left:8px;top:8px;width:80px;height:60px;display:block;z-index:2147483647;pointer-events:auto;background:transparent;';
              const image = document.createElement('img');
              image.alt = 'hit-test-fixture';
              image.style.cssText = 'display:block;width:80px;height:60px;pointer-events:none;background:#f00;';
              anchor.appendChild(image);
              document.body.appendChild(anchor);
              const rect = image.getBoundingClientRect();
              const top = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
              let legacyMatched = false;
              for (let p = top; p; p = p.parentElement) if (p === image) legacyMatched = true;
              let ancestorMatched = false;
              for (let p = image; p; p = p.parentElement) if (p === top) ancestorMatched = true;
              anchor.remove();
              return JSON.stringify({
                hitTargetIsWrapper: top === anchor,
                legacyMatched: legacyMatched,
                ancestorMatched: ancestorMatched,
                correctedMatched: legacyMatched || ancestorMatched
              });
            })()
        """.trimIndent()

        composeRule.runOnUiThread {
            findWebView(composeRule.activity.window.decorView)?.evaluateJavascript(script) { raw ->
                val decoded = runCatching {
                    JSONTokener(raw.orEmpty()).nextValue() as? String
                }.getOrNull()
                result.set(decoded)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { result.get() != null }
        val snapshot = JSONObject(result.get().orEmpty())
        assertTrue("Fixture must make the wrapper the hit target", snapshot.optBoolean("hitTargetIsWrapper"))
        assertTrue("Legacy target-to-hit walk misses an ancestor wrapper", !snapshot.optBoolean("legacyMatched"))
        assertTrue("Reverse ancestor walk must accept the wrapper", snapshot.optBoolean("ancestorMatched"))
        assertTrue(snapshot.optBoolean("correctedMatched"))
    }

    @Test(timeout = 45_000)
    fun hitTestCountsVisibleTextWhenWebKitReturnsItsClickableAncestor() {
        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
        composeRule.onNodeWithText("AHDownload Browser").assertIsDisplayed()
        waitForBlankBrowser()

        val browserStore = BrowserErrorLogStore(composeRule.activity.applicationContext)
        browserStore.clear()
        val targetUrl = "https://example.com/"
        val fixtureHtml = """
            <!doctype html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>AH Text Hit Test Fixture</title>
                <style>
                  body { margin: 0; background: #fff; color: #111; }
                  a { display: block; position: absolute; left: 8px; top: 8px;
                      width: 340px; min-height: 80px; color: #111;
                      background: #fff; text-decoration: none; font: 24px sans-serif; }
                  span { display: block; padding: 16px; pointer-events: none; }
                </style>
              </head>
              <body><a href="#fixture"><span>Visible browser text</span></a></body>
            </html>
        """.trimIndent()

        composeRule.runOnUiThread {
            findWebView(composeRule.activity.window.decorView)?.loadDataWithBaseURL(
                targetUrl,
                fixtureHtml,
                "text/html",
                "UTF-8",
                targetUrl,
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            browserStore.list().any {
                it.type == "PAGE_RENDER_CHECK" &&
                    it.host == "example.com" &&
                    it.pageTitle == "AH Text Hit Test Fixture"
            }
        }
        val renderEvent = browserStore.list().first {
            it.type == "PAGE_RENDER_CHECK" &&
                it.host == "example.com" &&
                it.pageTitle == "AH Text Hit Test Fixture"
        }
        assertEquals("CONTENT_SIGNALS_DETECTED", renderEvent.details["render_status"])
        assertTrue((renderEvent.details["visible_text_chars"]?.toIntOrNull() ?: 0) >= 12)
        assertEquals("0", renderEvent.details["hit_test_rejected_text_node_count"])

        composeRule.waitUntil(timeoutMillis = 15_000) {
            browserStore.list().any {
                it.type == "PAGE_RENDER_RECHECK" &&
                    it.host == "example.com" &&
                    it.pageTitle == "AH Text Hit Test Fixture"
            }
        }
        val recheckEvent = browserStore.list().first {
            it.type == "PAGE_RENDER_RECHECK" &&
                it.host == "example.com" &&
                it.pageTitle == "AH Text Hit Test Fixture"
        }
        assertEquals("CONTENT_SIGNALS_DETECTED", recheckEvent.details["render_status"])
        assertTrue((recheckEvent.details["visible_text_chars"]?.toIntOrNull() ?: 0) >= 12)
        assertEquals("0", recheckEvent.details["hit_test_rejected_text_node_count"])
    }


    @Test(timeout = 45_000)
    fun hitTestSamplesVisibleTextAwayFromCoveredCenterPoint() {
        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
        composeRule.onNodeWithText("AHDownload Browser").assertIsDisplayed()
        waitForBlankBrowser()

        val browserStore = BrowserErrorLogStore(composeRule.activity.applicationContext)
        browserStore.clear()
        val targetUrl = "https://example.com/"
        val fixtureHtml = """
            <!doctype html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>AH Center Overlay Fixture</title>
                <style>
                  html, body { margin: 0; width: 100%; min-height: 100%; background: #fff; color: #111; }
                  a { display: block; position: fixed; left: 0; top: 0; width: 100vw;
                      min-height: 80px; color: #111; background: #fff; text-decoration: none;
                      font: 24px sans-serif; text-align: center; }
                  span { display: block; padding: 16px 0; pointer-events: none; }
                  #center-hit-blocker { position: fixed; left: 49%; top: 0; bottom: 0; width: 2%;
                      z-index: 2147483647; pointer-events: auto; background: rgba(0, 0, 0, 0.2); }
                </style>
              </head>
              <body>
                <a href="#fixture"><span>Visible browser text</span></a>
                <div id="center-hit-blocker"></div>
              </body>
            </html>
        """.trimIndent()

        composeRule.runOnUiThread {
            findWebView(composeRule.activity.window.decorView)?.loadDataWithBaseURL(
                targetUrl,
                fixtureHtml,
                "text/html",
                "UTF-8",
                targetUrl,
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            browserStore.list().any {
                it.type == "PAGE_RENDER_CHECK" &&
                    it.host == "example.com" &&
                    it.pageTitle == "AH Center Overlay Fixture"
            }
        }
        val renderEvent = browserStore.list().first {
            it.type == "PAGE_RENDER_CHECK" &&
                it.host == "example.com" &&
                it.pageTitle == "AH Center Overlay Fixture"
        }
        assertEquals("CONTENT_SIGNALS_DETECTED", renderEvent.details["render_status"])
        assertTrue((renderEvent.details["visible_text_chars"]?.toIntOrNull() ?: 0) >= 12)
        assertEquals("0", renderEvent.details["hit_test_rejected_text_node_count"])

        composeRule.waitUntil(timeoutMillis = 15_000) {
            browserStore.list().any {
                it.type == "PAGE_RENDER_RECHECK" &&
                    it.host == "example.com" &&
                    it.pageTitle == "AH Center Overlay Fixture"
            }
        }
        val recheckEvent = browserStore.list().first {
            it.type == "PAGE_RENDER_RECHECK" &&
                it.host == "example.com" &&
                it.pageTitle == "AH Center Overlay Fixture"
        }
        assertEquals("CONTENT_SIGNALS_DETECTED", recheckEvent.details["render_status"])
        assertTrue((recheckEvent.details["visible_text_chars"]?.toIntOrNull() ?: 0) >= 12)
        assertEquals("0", recheckEvent.details["hit_test_rejected_text_node_count"])
    }

    @Test(timeout = 45_000)
    fun pointerEventsNoneTextIsNotMisclassifiedAsConfirmedOcclusion() {
        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
        composeRule.onNodeWithText("AHDownload Browser").assertIsDisplayed()
        waitForBlankBrowser()

        val browserStore = BrowserErrorLogStore(composeRule.activity.applicationContext)
        browserStore.clear()
        val fixtureUrl = "https://example.com/"
        val fixtureHtml = """
            <!doctype html>
            <html lang="en">
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>AH Pointer Events Visibility Fixture</title>
                <style>
                  html, body { margin: 0; min-height: 100%; background: #fff; color: #111; }
                  main { pointer-events: none; padding: 16px; }
                  h1, p { color: #111; background: #fff; font: 18px sans-serif; }
                </style>
              </head>
              <body>
                <main>
                  <h1>Pointer Events Visibility Fixture</h1>
                  <p>Non-interactive content can still be painted and readable in the viewport.</p>
                </main>
              </body>
            </html>
        """.trimIndent()

        composeRule.runOnUiThread {
            findWebView(composeRule.activity.window.decorView)?.loadDataWithBaseURL(
                fixtureUrl,
                fixtureHtml,
                "text/html",
                "UTF-8",
                fixtureUrl,
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            browserStore.list().any {
                it.type == "PAGE_RENDER_CHECK" &&
                    it.host == "example.com" &&
                    it.pageTitle == "AH Pointer Events Visibility Fixture"
            }
        }
        val renderEvent = browserStore.list().first {
            it.type == "PAGE_RENDER_CHECK" &&
                it.host == "example.com" &&
                it.pageTitle == "AH Pointer Events Visibility Fixture"
        }
        assertEquals("CONTENT_VISIBILITY_UNCONFIRMED", renderEvent.details["render_status"])
        assertTrue((renderEvent.details["viewport_intersecting_text_node_count"]?.toIntOrNull() ?: 0) > 0)
        assertTrue((renderEvent.details["hit_test_rejected_text_node_count"]?.toIntOrNull() ?: 0) > 0)
        assertTrue(
            "The render diagnostic should include sanitized hit-test target metadata",
            !renderEvent.details["hit_test_blocker_summary"].isNullOrBlank(),
        )

        composeRule.waitUntil(timeoutMillis = 15_000) {
            browserStore.list().any {
                it.type == "PAGE_RENDER_RECHECK" &&
                    it.host == "example.com" &&
                    it.pageTitle == "AH Pointer Events Visibility Fixture"
            }
        }
        val recheckEvent = browserStore.list().first {
            it.type == "PAGE_RENDER_RECHECK" &&
                it.host == "example.com" &&
                it.pageTitle == "AH Pointer Events Visibility Fixture"
        }
        assertEquals("CONTENT_VISIBILITY_UNCONFIRMED", recheckEvent.details["render_status"])
        assertTrue(!recheckEvent.details["hit_test_blocker_summary"].isNullOrBlank())
    }

    private fun waitForBlankBrowser(timeoutMillis: Long = 10_000L) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            val blankState = AtomicReference(false)
            composeRule.runOnUiThread {
                val view = findWebView(composeRule.activity.window.decorView)
                val url = view?.url
                blankState.set(view != null && (url.isNullOrBlank() || url == "about:blank"))
            }
            blankState.get()
        }
    }

    @Test(timeout = 30_000)
    fun browserStartsWithBlankAddressAndDoesNotLoadHomeAutomatically() {
        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
        composeRule.onNodeWithText("AHDownload Browser").assertIsDisplayed()

        waitForBlankBrowser()
        org.junit.Assert.assertEquals(
            "The editable browser address must start empty",
            "",
            composeRule.onNodeWithTag("browser-address-field")
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text.toString(),
        )
        SystemClock.sleep(500L)

        val loadedUrl = AtomicReference<String?>(null)
        composeRule.runOnUiThread {
            loadedUrl.set(findWebView(composeRule.activity.window.decorView)?.url)
        }
        assertTrue(
            "The browser must stay blank instead of opening Google automatically",
            loadedUrl.get().isNullOrBlank() || loadedUrl.get() == "about:blank",
        )
    }

    @Test(timeout = 45_000)
    fun blankPageButtonClearsAddressAndHistoryInsteadOfOpeningAHomepage() {
        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
        waitForBlankBrowser()

        val targetUrl = "https://example.com/"
        composeRule.onNodeWithTag("browser-address-field").performTextReplacement(targetUrl)
        composeRule.onNodeWithContentDescription("فتح العنوان").performClick()

        composeRule.waitUntil(timeoutMillis = 25_000) {
            val loaded = AtomicReference(false)
            composeRule.runOnUiThread {
                val view = findWebView(composeRule.activity.window.decorView)
                loaded.set(view?.url?.startsWith(targetUrl) == true && view?.title == "Example Domain")
            }
            loaded.get()
        }

        composeRule.onNodeWithContentDescription("صفحة فارغة").performClick()
        waitForBlankBrowser()
        org.junit.Assert.assertEquals(
            "The editable browser address must start empty",
            "",
            composeRule.onNodeWithTag("browser-address-field")
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text.toString(),
        )
        composeRule.onNodeWithContentDescription("لا يوجد موقع مفتوح").assertIsDisplayed()
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
