package com.ahdownload.app.browser

import android.net.Uri
import android.os.SystemClock
import android.util.Log
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
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live browser acceptance probe for the three requested sites.
 * Only counts and state are logged; page text, title, form values, query strings,
 * cookies, and link destinations are never emitted.
 */
@RunWith(AndroidJUnit4::class)
class BrowserPublicSitesAcceptanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 480_000L)
    fun googleShahid4uAndXnxxArabicRenderAndExposeNavigationUi() {
        val store = BrowserErrorLogStore(composeRule.activity.applicationContext)
        openBrowser()
        val results = mutableListOf<SiteResult>()

        for (site in SITES) {
            store.clear()
            val startedAt = SystemClock.elapsedRealtime()
            composeRule.onNode(hasSetTextAction()).performTextReplacement(site.url)
            composeRule.onNodeWithContentDescription("فتح العنوان").performClick()

            val pageFinished = waitForPageFinish(store, site.timeoutMs)
            // Let the page's scripts, lazy controls and the browser's delayed render check settle.
            SystemClock.sleep(1_650L)
            val firstSnapshot = evaluateSnapshot() ?: JSONObject()
            val firstSummary = readSummary(firstSnapshot)
            val menuAttempt = clickVisibleMenuToggle()
            if (menuAttempt.optBoolean("attempted", false)) SystemClock.sleep(800L)
            val summary = readSummary(
                if (menuAttempt.optBoolean("attempted", false)) evaluateSnapshot() ?: firstSnapshot
                else firstSnapshot,
            )
            val finalUrl = currentWebViewUrl()
            val finalHost = safeHost(finalUrl)
            val renderEvent = store.list().lastOrNull {
                it.type == "PAGE_RENDER_CHECK" || it.type == "PAGE_RENDER_RECHECK"
            }
            val classification = when {
                !pageFinished -> "LOAD_TIMEOUT_OR_NO_FINISH"
                summary.challengeDetected -> "REMOTE_CHALLENGE_OR_ACCESS_GATE"
                summary.visibleTextChars >= 12 ||
                    summary.visibleImageAreaRatio >= 0.025 ||
                    summary.visibleMediaCount > 0 -> "VISIBLE_CONTENT"
                summary.bodyTextChars >= 80 &&
                    (summary.viewportIntersectingTextNodes > 0 || summary.viewportIntersectingImages > 0) ->
                    "DOM_PRESENT_BUT_NOT_CONFIRMED_VISIBLE"
                summary.bodyTextChars > 0 || summary.bodyChildCount > 0 ->
                    "DOM_PRESENT_WITHOUT_STRONG_VISIBLE_SIGNALS"
                else -> "NO_RENDERABLE_DOM_CONTENT"
            }

            val result = SiteResult(
                id = site.id,
                classification = classification,
                pageFinished = pageFinished,
                finalHost = finalHost,
                summary = summary,
                menuPanelsBefore = menuAttempt.optInt("menuPanelCountBefore", firstSummary.menuPanelCount),
                menuPanelsAfter = summary.menuPanelCount,
                menuToggleAttempted = menuAttempt.optBoolean("attempted", false),
                menuOpened = menuAttempt.optBoolean("attempted", false) &&
                    (summary.menuPanelCount > menuAttempt.optInt("menuPanelCountBefore", firstSummary.menuPanelCount) ||
                        summary.expandedControlCount > menuAttempt.optInt("expandedControlCountBefore", firstSummary.expandedControlCount)),
                expandedBefore = menuAttempt.optInt("expandedControlCountBefore", firstSummary.expandedControlCount),
                expandedAfter = summary.expandedControlCount,
                renderEventStatus = renderEvent?.details?.get("render_status").orEmpty(),
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
            results += result
            Log.i(TAG, result.toSafeLogLine())
        }

        assertEquals("All three requested sites must produce a bounded probe result", SITES.size, results.size)
        assertTrue(
            "Every requested URL must finish navigation or report a detectable page failure. Results: " +
                results.joinToString(" || ") { it.toSafeLogLine() },
            results.all { it.pageFinished },
        )
        val provenVisibilityFailures = results.filter { result ->
            result.pageFinished &&
                !result.summary.challengeDetected &&
                result.summary.bodyTextChars >= 80 &&
                result.summary.visibleTextChars == 0 &&
                result.summary.visibleImageCount == 0 &&
                result.summary.visibleMediaCount == 0 &&
                result.summary.visibleLinkCount == 0 &&
                result.summary.visibleButtonCount == 0 &&
                result.summary.visibleInputCount == 0 &&
                result.summary.visibleNavigationCount == 0
        }
        assertTrue(
            "A completed non-challenge site has substantial DOM and viewport geometry but no confirmed visible content. " +
                "Inspect count-only SITE_ACCEPTANCE log lines.",
            provenVisibilityFailures.isEmpty(),
        )
        val browserStillMounted = AtomicReference(false)
        composeRule.runOnUiThread {
            browserStillMounted.set(findWebView(composeRule.activity.window.decorView) != null)
        }
        assertTrue("The browser route must remain mounted after all target checks", browserStillMounted.get())
    }

    private fun openBrowser() {
        composeRule.onNodeWithText("ابدأ الآن").performClick()
        composeRule.onNodeWithContentDescription("المتصفح الذكي").performClick()
        composeRule.onNodeWithText("AHDownload Browser").assertIsDisplayed()
        composeRule.onNodeWithText("عنوان الموقع").assertIsDisplayed()
    }

    private fun waitForPageFinish(store: BrowserErrorLogStore, timeoutMs: Long): Boolean {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            val state = AtomicReference<Pair<Int, String?>?>(null)
            composeRule.runOnUiThread {
                val view = findWebView(composeRule.activity.window.decorView)
                state.set((view?.progress ?: 0) to view?.url)
            }
            val events = store.list()
            val navigationStarted = events.any { it.type == "NAVIGATION_STARTED" }
            val pageFinished = events.any { it.type == "PAGE_LOAD_FINISHED" }
            val mainFrameError = events.any {
                it.type == "MAIN_FRAME_LOAD_ERROR" || it.type == "PAGE_LOAD_ERROR" ||
                    it.type == "NAVIGATION_ERROR" || it.type == "RESOURCE_HTTP_ERROR" && it.details["is_main_frame"] == "true"
            }
            if (navigationStarted && pageFinished && state.get()?.first == 100) return true
            if (navigationStarted && mainFrameError) return false
            SystemClock.sleep(200L)
        }
        return false
    }

    private fun currentWebViewUrl(): String? {
        val result = AtomicReference<String?>()
        composeRule.runOnUiThread { result.set(findWebView(composeRule.activity.window.decorView)?.url) }
        return result.get()
    }

    private fun evaluateSnapshot(): JSONObject? {
        val rawResult = AtomicReference<String?>()
        composeRule.runOnUiThread {
            findWebView(composeRule.activity.window.decorView)?.evaluateJavascript(SNAPSHOT_SCRIPT) { raw ->
                rawResult.set(runCatching { JSONTokener(raw.orEmpty()).nextValue() as? String }.getOrNull())
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 7_000L
        while (rawResult.get() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L)
        return rawResult.get()?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun clickVisibleMenuToggle(): JSONObject {
        val rawResult = AtomicReference<String?>()
        composeRule.runOnUiThread {
            findWebView(composeRule.activity.window.decorView)?.evaluateJavascript(MENU_PROBE_SCRIPT) { raw ->
                rawResult.set(runCatching { JSONTokener(raw.orEmpty()).nextValue() as? String }.getOrNull())
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (rawResult.get() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L)
        return rawResult.get()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    }

    private fun readSummary(s: JSONObject) = SiteSummary(
        readyState = s.optString("readyState", "unknown"),
        bodyTextChars = s.optInt("bodyTextChars", 0).coerceAtLeast(0),
        bodyChildCount = s.optInt("bodyChildCount", 0).coerceAtLeast(0),
        documentHeight = s.optInt("documentHeight", 0).coerceAtLeast(0),
        viewportWidth = s.optInt("viewportWidth", 0).coerceAtLeast(0),
        viewportHeight = s.optInt("viewportHeight", 0).coerceAtLeast(0),
        visibleTextChars = s.optInt("visibleTextChars", 0).coerceAtLeast(0),
        readableTextChars = s.optInt("readableVisibleTextChars", 0).coerceAtLeast(0),
        imageCount = s.optInt("imageCount", 0).coerceAtLeast(0),
        visibleImageCount = s.optInt("visibleImageCount", 0).coerceAtLeast(0),
        viewportIntersectingImages = s.optInt("viewportIntersectingImageCount", 0).coerceAtLeast(0),
        hitTestRejectedImages = s.optInt("hitTestRejectedImageCount", 0).coerceAtLeast(0),
        visibleImageAreaRatio = s.optDouble("visibleImageAreaRatio", 0.0).coerceIn(0.0, 1.0),
        viewportIntersectingTextNodes = s.optInt("viewportIntersectingTextNodeCount", 0).coerceAtLeast(0),
        hitTestRejectedTextNodes = s.optInt("hitTestRejectedTextNodeCount", 0).coerceAtLeast(0),
        visibleMediaCount = s.optInt("visibleMediaElementCount", 0).coerceAtLeast(0),
        visibleLinkCount = s.optInt("visibleLinkCount", 0).coerceAtLeast(0),
        visibleButtonCount = s.optInt("visibleButtonCount", 0).coerceAtLeast(0),
        visibleInputCount = s.optInt("visibleInputCount", 0).coerceAtLeast(0),
        visibleNavigationCount = s.optInt("visibleNavigationCount", 0).coerceAtLeast(0),
        menuPanelCount = s.optInt("menuPanelCount", 0).coerceAtLeast(0),
        visibleMenuTriggerCount = s.optInt("visibleMenuTriggerCount", 0).coerceAtLeast(0),
        expandedControlCount = s.optInt("expandedControlCount", 0).coerceAtLeast(0),
        challengeDetected = s.optBoolean("challengeDetected", false),
    )

    private fun safeHost(url: String?): String {
        val host = runCatching { Uri.parse(url.orEmpty()).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host == "google.com" || host.endsWith(".google.com") -> "google.com"
            host == "shahid4u.courses" || host.endsWith(".shahid4u.courses") -> "shahid4u.courses"
            host.contains("xnxx") -> "xnxx-site"
            host.isBlank() -> "unknown"
            else -> "redirected-or-other-host"
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private data class Site(val id: String, val url: String, val timeoutMs: Long = 45_000L)

    private data class SiteSummary(
        val readyState: String,
        val bodyTextChars: Int,
        val bodyChildCount: Int,
        val documentHeight: Int,
        val viewportWidth: Int,
        val viewportHeight: Int,
        val visibleTextChars: Int,
        val readableTextChars: Int,
        val imageCount: Int,
        val visibleImageCount: Int,
        val viewportIntersectingImages: Int,
        val hitTestRejectedImages: Int,
        val visibleImageAreaRatio: Double,
        val viewportIntersectingTextNodes: Int,
        val hitTestRejectedTextNodes: Int,
        val visibleMediaCount: Int,
        val visibleLinkCount: Int,
        val visibleButtonCount: Int,
        val visibleInputCount: Int,
        val visibleNavigationCount: Int,
        val menuPanelCount: Int,
        val visibleMenuTriggerCount: Int,
        val expandedControlCount: Int,
        val challengeDetected: Boolean,
    )

    private data class SiteResult(
        val id: String,
        val classification: String,
        val pageFinished: Boolean,
        val finalHost: String,
        val summary: SiteSummary,
        val menuPanelsBefore: Int,
        val menuPanelsAfter: Int,
        val menuToggleAttempted: Boolean,
        val menuOpened: Boolean,
        val expandedBefore: Int,
        val expandedAfter: Int,
        val renderEventStatus: String,
        val elapsedMs: Long,
    ) {
        fun toSafeLogLine(): String =
            "$TAG|site=$id|result=$classification|page_finished=$pageFinished|host=$finalHost|" +
                "ready=${summary.readyState}|body_chars=${summary.bodyTextChars}|body_children=${summary.bodyChildCount}|" +
                "viewport=${summary.viewportWidth}x${summary.viewportHeight}|document_height=${summary.documentHeight}|" +
                "visible_text=${summary.visibleTextChars}|readable_text=${summary.readableTextChars}|" +
                "images=${summary.visibleImageCount}/${summary.imageCount}|viewport_images=${summary.viewportIntersectingImages}|" +
                "rejected_images=${summary.hitTestRejectedImages}|image_area=${"%.4f".format(java.util.Locale.ROOT, summary.visibleImageAreaRatio)}|" +
                "viewport_text_nodes=${summary.viewportIntersectingTextNodes}|rejected_text_nodes=${summary.hitTestRejectedTextNodes}|" +
                "links=${summary.visibleLinkCount}|buttons=${summary.visibleButtonCount}|inputs=${summary.visibleInputCount}|" +
                "nav_regions=${summary.visibleNavigationCount}|menu_panels_before=$menuPanelsBefore|menu_panels_after=$menuPanelsAfter|" +
                "menu_toggle_candidates=${summary.visibleMenuTriggerCount}|menu_toggle_attempted=$menuToggleAttempted|menu_opened=$menuOpened|" +
                "expanded_before=$expandedBefore|expanded_after=$expandedAfter|media=${summary.visibleMediaCount}|" +
                "challenge=${summary.challengeDetected}|render_event=${renderEventStatus.ifBlank { "unknown" }}|elapsed_ms=$elapsedMs"
    }

    private companion object {
        const val TAG = "AH_BROWSER_SITE_ACCEPTANCE"
        val SITES = listOf(
            Site("google", "https://www.google.com/"),
            Site("shahid4u", "https://shahid4u.courses/"),
            Site("xnxx_arabic", "https://www.xnxx-arabic.com/"),
        )

        val SNAPSHOT_SCRIPT = """
          (function(){try{
            const d=document,b=d.body,e=d.documentElement,vw=window.innerWidth||0,vh=window.innerHeight||0;
            const intersects=r=>r.width>0&&r.height>0&&r.right>0&&r.bottom>0&&r.left<vw&&r.top<vh;
            const shown=(el,r)=>{if(!el||!r||r.width<=0||r.height<=0||!intersects(r))return false;
              const s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden'||Number(s.opacity||1)<=0.05)return false;
              for(let p=el.parentElement;p;p=p.parentElement){const q=getComputedStyle(p);if(q.display==='none'||q.visibility==='hidden'||Number(q.opacity||1)<=0.05)return false;}return true;};
            const unobscured=(el,r)=>{const l=Math.max(0,r.left),rr=Math.min(vw,r.right),t=Math.max(0,r.top),bb=Math.min(vh,r.bottom);
              if(rr<=l||bb<=t)return false;const top=d.elementFromPoint((l+rr)/2,(t+bb)/2);if(!top)return true;
              for(let p=top;p;p=p.parentElement)if(p===el)return true;
              for(let p=el;p;p=p.parentElement)if(p===top)return true;return false;};
            const readable=el=>{const m=String(getComputedStyle(el).color||'').match(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?\s*\)/i);
              if(!m)return false;const f={r:+m[1],g:+m[2],b:+m[3],a:m[4]===undefined?1:+m[4]};if(f.a<0.35)return false;if(f.a<0.98)return true;
              let bg=null;for(let p=el;p;p=p.parentElement){const q=String(getComputedStyle(p).backgroundColor||'').match(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?\s*\)/i);
                if(q&&(q[4]===undefined?1:+q[4])>=0.95){bg={r:+q[1],g:+q[2],b:+q[3]};break;}}
              if(!bg)bg={r:255,g:255,b:255};const lum=c=>{const f=v=>{v=v/255;return v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4);};return .2126*f(c.r)+.7152*f(c.g)+.0722*f(c.b);};
              const a=lum(f),z=lum(bg);return (Math.max(a,z)+.05)/(Math.min(a,z)+.05)>=1.4;};
            let visibleText=0,readableText=0,viewportTextNodes=0,rejectedTextNodes=0;
            if(b){const w=d.createTreeWalker(b,NodeFilter.SHOW_TEXT);let n,k=0;while((n=w.nextNode())&&k++<1200&&visibleText<10000){
              const v=(n.nodeValue||'').replace(/\s+/g,' ').trim(),el=n.parentElement;if(!v||!el||!shown(el,el.getBoundingClientRect()))continue;
              const r=d.createRange();r.selectNodeContents(n);const rects=Array.from(r.getClientRects()).filter(intersects);if(!rects.length)continue;viewportTextNodes++;
              if(!rects.some(x=>unobscured(el,x))){rejectedTextNodes++;continue;}visibleText=Math.min(10000,visibleText+v.length);if(readable(el))readableText=Math.min(10000,readableText+v.length);}}
            let visibleImages=0,viewportImages=0,rejectedImages=0,imageArea=0;
            Array.from(d.images).forEach(img=>{const r=img.getBoundingClientRect();if(!img.complete||img.naturalWidth<=0||!shown(img,r))return;
              const w=Math.max(0,Math.min(vw,r.right)-Math.max(0,r.left)),h=Math.max(0,Math.min(vh,r.bottom)-Math.max(0,r.top));if(w<=0||h<=0)return;
              viewportImages++;if(!unobscured(img,r)){rejectedImages++;return;}visibleImages++;imageArea+=w*h;});
            const count=s=>Array.from(d.querySelectorAll(s)).filter(x=>shown(x,x.getBoundingClientRect())).length;
            const panels=count('nav,[role="navigation"],[role="menu"],[role="menubar"],[aria-expanded="true"],[class*="menu" i],[id*="menu" i],[class*="dropdown" i],[id*="dropdown" i]');
            const toggles=Array.from(d.querySelectorAll('button,[role="button"],[aria-expanded],[aria-controls]')).filter(x=>{
              if(!shown(x,x.getBoundingClientRect()))return false;
              const label=[x.getAttribute('aria-label'),x.getAttribute('title'),x.id,typeof x.className==='string'?x.className:'',x.getAttribute('data-testid')].join(' ').toLowerCase();
              return /menu|navigation|hamburger|nav-toggle|toggle-menu|open-menu|القائمة|المنيو|التنقل/.test(label)||x.hasAttribute('aria-expanded')||x.hasAttribute('aria-controls');
            }).length;
            const controls=s=>Array.from(d.querySelectorAll(s)).filter(x=>shown(x,x.getBoundingClientRect())&&unobscured(x,x.getBoundingClientRect())).length;
            const media=Array.from(d.querySelectorAll('video,canvas,iframe')).filter(x=>{const r=x.getBoundingClientRect();if(!shown(x,r)||r.width<32||r.height<18||!unobscured(x,r))return false;
              if(x.tagName==='VIDEO')return x.videoWidth>0&&x.videoHeight>0&&x.readyState>=2;if(x.tagName==='CANVAS')return x.width>0&&x.height>0;return true;}).length;
            const text=((d.title||'')+' '+(b&&b.innerText?b.innerText:'').slice(0,5000)).toLowerCase();
            const challenge=/verify you are human|checking your browser|just a moment|access denied|captcha|unusual traffic|confirm you are not a robot|enable javascript and cookies|cloudflare ray id|verify to continue/i.test(text);
            return JSON.stringify({readyState:d.readyState||'unknown',bodyTextChars:(b&&b.innerText?b.innerText:'').trim().length,bodyChildCount:b?b.children.length:0,
              documentHeight:e?e.scrollHeight:0,viewportWidth:vw,viewportHeight:vh,visibleTextChars:visibleText,readableVisibleTextChars:readableText,
              imageCount:d.images.length,visibleImageCount:visibleImages,viewportIntersectingImageCount:viewportImages,hitTestRejectedImageCount:rejectedImages,
              visibleImageAreaRatio:vw>0&&vh>0?Math.min(1,imageArea/(vw*vh)):0,viewportIntersectingTextNodeCount:viewportTextNodes,hitTestRejectedTextNodeCount:rejectedTextNodes,
              visibleMediaElementCount:media,visibleLinkCount:controls('a[href]'),visibleButtonCount:controls('button,[role="button"],input[type="button"],input[type="submit"]'),
              visibleInputCount:controls('input:not([type="hidden"]),textarea,select'),visibleNavigationCount:count('nav,header,[role="navigation"],[role="menubar"]'),
              menuPanelCount:panels,visibleMenuTriggerCount:toggles,expandedControlCount:d.querySelectorAll('[aria-expanded="true"]').length,
              videoCount:d.querySelectorAll('video').length,challengeDetected:challenge});
          }catch(e){return JSON.stringify({diagnosticError:'site_probe_failed'});}})()
        """.trimIndent()

        val MENU_PROBE_SCRIPT = """
          (function(){try{const d=document,vw=innerWidth||0,vh=innerHeight||0;
            const shown=el=>{const r=el.getBoundingClientRect(),s=getComputedStyle(el);if(r.width<=0||r.height<=0||r.right<=0||r.bottom<=0||r.left>=vw||r.top>=vh||s.display==='none'||s.visibility==='hidden'||Number(s.opacity||1)<=.05)return false;
              for(let p=el.parentElement;p;p=p.parentElement){const q=getComputedStyle(p);if(q.display==='none'||q.visibility==='hidden'||Number(q.opacity||1)<=.05)return false;}return true;};
            const panels=()=>Array.from(d.querySelectorAll('nav,[role="navigation"],[role="menu"],[role="menubar"],[aria-expanded="true"],[class*="menu" i],[id*="menu" i],[class*="dropdown" i],[id*="dropdown" i]')).filter(shown).length;
            const expanded=()=>d.querySelectorAll('[aria-expanded="true"]').length;
            const candidates=Array.from(d.querySelectorAll('button,[role="button"],[aria-expanded],[aria-controls]')).filter(shown).map(el=>{
              const s=[el.getAttribute('aria-label'),el.getAttribute('title'),el.id,typeof el.className==='string'?el.className:'',el.getAttribute('data-testid')].join(' ').toLowerCase();
              const controlId=(el.getAttribute('aria-controls')||'').trim();
              const controlled=controlId?d.getElementById(controlId):null;
              const targetLabel=controlled?[controlled.id,typeof controlled.className==='string'?controlled.className:'',controlled.getAttribute('role')||'',controlled.tagName].join(' ').toLowerCase():'';
              const explicitMenu=/menu|navigation|hamburger|nav-toggle|toggle-menu|open-menu|القائمة|المنيو|التنقل/.test(s);
              const explicitTarget=/menu|navigation|dropdown|nav|القائمة|المنيو|التنقل/.test(targetLabel);
              let score=explicitMenu?10:(explicitTarget?6:0);
              if(controlled&&['NAV','MENU'].includes(controlled.tagName))score=Math.max(score,7);
              if(el.hasAttribute('aria-expanded'))score+=1;
              if(/close|dismiss|cookie|consent|advert/.test(s))score-=20;
              return {el,score};
            }).filter(x=>x.score>=6).sort((a,b)=>b.score-a.score);
            const p=panels(),e=expanded();if(!candidates.length)return JSON.stringify({attempted:false,menuPanelCountBefore:p,expandedControlCountBefore:e});
            candidates[0].el.click();return JSON.stringify({attempted:true,menuPanelCountBefore:p,expandedControlCountBefore:e});
          }catch(e){return JSON.stringify({attempted:false,probeError:true});}})()
        """.trimIndent()
    }
}
