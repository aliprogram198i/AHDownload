package com.ahdownload.app.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.download.DownloadEnqueueResult
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.validation.CandidateValidationResult
import com.ahdownload.domain.validation.ValidationFailure
import com.ahdownload.feature.home.AndroidBrowserMediaSessionProvider
import com.ahdownload.feature.home.HomeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserRoute(
    logger: DiagnosticLogger,
    errorStore: BrowserErrorLogStore,
    onBack: () -> Unit,
    onDownloadRequested: suspend (MediaCandidate, String?, String?, String?) -> DownloadEnqueueResult,
    onAudioOnlyRequested: suspend (MediaCandidate, AudioOutputFormat, String?, String?, String?) -> DownloadEnqueueResult,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analyzer = remember { LinkAnalyzer() }
    val resolver = remember(context, logger) {
        HomeResolver(
            logger = logger,
            browserMediaSessionProvider = AndroidBrowserMediaSessionProvider(context.applicationContext),
        )
    }
    var address by remember { mutableStateOf("https://www.google.com") }
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var pageProgress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var resultTitle by remember { mutableStateOf<String?>(null) }
    var resultThumbnail by remember { mutableStateOf<String?>(null) }
    var resultSourceUrl by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<MediaCandidate>>(emptyList()) }
    var inspectMessage by remember { mutableStateOf<String?>(null) }
    var pageLoadError by remember { mutableStateOf<String?>(null) }
    var currentPageTitle by remember { mutableStateOf("") }
    var pageRenderStatus by remember { mutableStateOf("NOT_CHECKED") }
    var currentNavigationId by remember { mutableStateOf("") }
    var navigationStartedAtMs by remember { mutableStateOf(0L) }
    var nextNavigationSource by remember { mutableStateOf("browser_entry") }
    var lastProgressBucket by remember { mutableStateOf(0) }
    var resourceHttpErrorCount by remember { mutableStateOf(0) }
    var consoleErrorCount by remember { mutableStateOf(0) }
    var showResults by remember { mutableStateOf(false) }
    var selectedAudioCandidateId by remember { mutableStateOf<String?>(null) }
    var selectedAudioFormat by remember { mutableStateOf<AudioOutputFormat?>(null) }
    var actionInProgress by remember { mutableStateOf(false) }

    fun recordBrowserEvent(
        type: String,
        message: String,
        stage: String,
        url: String?,
        code: String? = null,
        severity: String = "INFO",
        details: Map<String, String> = emptyMap(),
        pageTitle: String? = currentPageTitle.takeIf { it.isNotBlank() },
    ) {
        val enrichedDetails = buildMap {
            putAll(details)
            currentNavigationId.takeIf { it.isNotBlank() }?.let { put("navigation_id", it) }
            pageRenderStatus.takeIf { it != "NOT_CHECKED" }?.let { put("page_render_status_at_event", it) }
        }
        errorStore.record(
            type = type,
            message = message,
            stage = stage,
            url = url,
            code = code,
            severity = severity,
            pageTitle = pageTitle,
            details = enrichedDetails,
        )
        logger.log(
            if (severity == "ERROR" || severity == "WARNING") DiagnosticLevel.WARNING else DiagnosticLevel.INFO,
            "BROWSER_$type",
            message,
            "browser.$stage",
            buildMap {
                put("host", safeHost(url))
                put("severity", severity)
                code?.let { put("code", it.take(60)) }
                currentNavigationId.takeIf { it.isNotBlank() }?.let { put("navigation_id", it) }
            },
            null,
        )
    }

    fun recordError(type: String, message: String, stage: String, url: String?, code: String? = null) {
        recordBrowserEvent(type, message, stage, url, code, severity = "ERROR")
    }

    fun loadAddress() {
        val normalized = normalizeBrowserUrl(address)
        if (normalized == null) {
            inspectMessage = "أدخل عنوان موقع صالحًا يبدأ بـ https:// أو http://."
            recordError("INVALID_ADDRESS", "عنوان الموقع غير صالح", "navigation", address)
            return
        }
        nextNavigationSource = "address_bar"
        address = normalized
        inspectMessage = null
        pageLoadError = null
        pageProgress = 0
        isLoading = true
        showResults = false
        webView?.loadUrl(normalized)
    }

    BackHandler {
        if (showResults) {
            showResults = false
        } else if (canGoBack) {
            nextNavigationSource = "system_back"
            webView?.goBack()
        } else {
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                runCatching { view.stopLoading() }
                (view.parent as? ViewGroup)?.removeView(view)
                runCatching { view.destroy() }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("AHDownload Browser")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        nextNavigationSource = "home_button"
                        val homeUrl = "https://www.google.com"
                        address = homeUrl
                        currentUrl = homeUrl
                        pageLoadError = null
                        inspectMessage = null
                        pageProgress = 0
                        isLoading = true
                        showResults = false
                        webView?.loadUrl(homeUrl)
                    }) {
                        Icon(Icons.Rounded.Home, contentDescription = "الصفحة الرئيسية")
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (inspectMessage == null) Arrangement.End else Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (inspectMessage != null) {
                        Text(
                            inspectMessage.orEmpty(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                        )
                    }
                    Button(
                        enabled = !inspecting && !isLoading && pageLoadError == null && currentUrl.isNotBlank(),
                        onClick = {
                            val target = webView?.url ?: currentUrl
                            val link = analyzer.analyze(target)
                            if (link == null) {
                                inspectMessage = "تعذر تحليل العنوان. استخدم رابط HTTP أو HTTPS صالحًا بعد اكتمال تحميل الصفحة."
                                recordBrowserEvent(
                                    "OPTIONS_INSPECTION_REJECTED",
                                    "رفض محلل الروابط عنوان الصفحة قبل بدء اكتشاف الوسائط",
                                    "inspection",
                                    target,
                                    severity = "ERROR",
                                    details = mapOf(
                                        "reason" to "unsupported_scheme_or_invalid_url",
                                        "page_progress_percent" to pageProgress.toString(),
                                        "page_render_status" to pageRenderStatus,
                                    ),
                                )
                            } else {
                                inspecting = true
                                inspectMessage = null
                                candidates = emptyList()
                                selectedAudioCandidateId = null
                                selectedAudioFormat = null
                                showResults = false
                                val operationId = "browser-" + UUID.randomUUID().toString()
                                val inspectionStartedAtMs = System.currentTimeMillis()
                                recordBrowserEvent(
                                    "OPTIONS_INSPECTION_STARTED",
                                    "بدأ فحص خيارات التنزيل للصفحة الحالية",
                                    "inspection",
                                    target,
                                    severity = "INFO",
                                    details = mapOf(
                                        "operation_id" to operationId,
                                        "page_progress_percent" to pageProgress.toString(),
                                        "page_render_status" to pageRenderStatus,
                                        "page_title_known" to currentPageTitle.isNotBlank().toString(),
                                    ),
                                )
                                scope.launch {
                                    try {
                                        when (val result = resolver.resolve(link, operationId)) {
                                            is ResolverResult.Success -> {
                                                candidates = result.candidates
                                                resultTitle = result.title
                                                resultThumbnail = result.thumbnailUrl
                                                resultSourceUrl = target
                                                showResults = result.candidates.isNotEmpty()
                                                val videoOptionCount = result.candidates.count {
                                                    it.format.kind == MediaKind.Video && it.format.hasVideo
                                                }
                                                val audioSourceCount = result.candidates.count {
                                                    it.format.hasAudio || it.format.kind == MediaKind.Audio
                                                }
                                                val elapsedMs = (System.currentTimeMillis() - inspectionStartedAtMs).coerceAtLeast(0)
                                                recordBrowserEvent(
                                                    "OPTIONS_INSPECTION_COMPLETED",
                                                    if (result.candidates.isEmpty()) "اكتمل الفحص دون اكتشاف خيارات تنزيل صالحة" else "اكتمل فحص خيارات التنزيل",
                                                    "inspection",
                                                    target,
                                                    code = "candidates_${result.candidates.size}",
                                                    severity = if (result.candidates.isEmpty()) "WARNING" else "SUCCESS",
                                                    details = mapOf(
                                                        "operation_id" to operationId,
                                                        "result" to if (result.candidates.isEmpty()) "no_candidates" else "candidates_found",
                                                        "candidate_count" to result.candidates.size.toString(),
                                                        "video_option_count" to videoOptionCount.toString(),
                                                        "audio_source_count" to audioSourceCount.toString(),
                                                        "elapsed_ms" to elapsedMs.toString(),
                                                        "resolver_outcome" to "success",
                                                    ),
                                                    pageTitle = result.title ?: currentPageTitle,
                                                )
                                                if (result.candidates.isEmpty()) {
                                                    recordBrowserEvent(
                                                        "OPTIONS_RESULTS_EMPTY",
                                                        "لم تظهر بطاقة نتائج لأن الفحص لم يُرجع أي مصدر",
                                                        "inspection_results",
                                                        target,
                                                        severity = "WARNING",
                                                        details = mapOf(
                                                            "candidate_count" to "0",
                                                            "reason" to "resolver_returned_no_candidates",
                                                            "operation_id" to operationId,
                                                        ),
                                                        pageTitle = result.title ?: currentPageTitle,
                                                    )
                                                } else {
                                                    recordBrowserEvent(
                                                        "OPTIONS_RESULTS_DISPLAYED",
                                                        "أُظهرت قائمة خيارات التنزيل للمستخدم",
                                                        "inspection_results",
                                                        target,
                                                        severity = "SUCCESS",
                                                        details = mapOf(
                                                            "candidate_count" to result.candidates.size.toString(),
                                                            "video_option_count" to videoOptionCount.toString(),
                                                            "audio_source_count" to audioSourceCount.toString(),
                                                            "operation_id" to operationId,
                                                        ),
                                                        pageTitle = result.title ?: currentPageTitle,
                                                    )
                                                }
                                                inspectMessage = if (result.candidates.isEmpty()) {
                                                    "لم يُعثر على مصدر وسائط صالح. قد يكون المشغّل غير مدعوم أو يتطلب الموقع جلسة صالحة."
                                                } else {
                                                    "تم العثور على ${result.candidates.size} خيارًا."
                                                }
                                            }
                                            is ResolverResult.Failure -> {
                                                inspectMessage = result.message ?: "تعذر فحص هذه الصفحة."
                                                recordBrowserEvent(
                                                    "OPTIONS_INSPECTION_FAILED",
                                                    inspectMessage.orEmpty(),
                                                    "inspection",
                                                    target,
                                                    code = result.code.name,
                                                    severity = "ERROR",
                                                    details = mapOf(
                                                        "operation_id" to operationId,
                                                        "resolver_outcome" to "failure",
                                                        "failure_code" to result.code.name,
                                                        "elapsed_ms" to (System.currentTimeMillis() - inspectionStartedAtMs).coerceAtLeast(0).toString(),
                                                    ),
                                                )
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        inspectMessage = "حدث خطأ أثناء فحص الصفحة."
                                        recordBrowserEvent(
                                            "OPTIONS_INSPECTION_EXCEPTION",
                                            "وقع استثناء أثناء فحص الخيارات",
                                            "inspection",
                                            target,
                                            code = error.javaClass.simpleName,
                                            severity = "ERROR",
                                            details = mapOf(
                                                "operation_id" to operationId,
                                                "exception_type" to error.javaClass.simpleName,
                                                "elapsed_ms" to (System.currentTimeMillis() - inspectionStartedAtMs).coerceAtLeast(0).toString(),
                                            ),
                                        )
                                    } finally {
                                        inspecting = false
                                    }
                                }
                            }
                        },
                    ) {
                        if (inspecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            when {
                                inspecting -> "جارٍ فحص الخيارات..."
                                isLoading -> "جارٍ تحميل الصفحة..."
                                else -> "فحص الخيارات"
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = "اتصال آمن", tint = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("عنوان الموقع") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { loadAddress() }),
                )
                IconButton(onClick = { loadAddress() }) {
                    Icon(Icons.Rounded.Search, contentDescription = "فتح العنوان")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(enabled = canGoBack, onClick = { nextNavigationSource = "back_button"; webView?.goBack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "السابق")
                }
                IconButton(enabled = canGoForward, onClick = { nextNavigationSource = "forward_button"; webView?.goForward() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "التالي")
                }
                IconButton(onClick = { nextNavigationSource = "reload_button"; pageLoadError = null; isLoading = true; pageProgress = 0; webView?.reload() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "تحديث")
                }
                IconButton(onClick = { showResults = false }) {
                    Icon(Icons.Rounded.Close, contentDescription = "إغلاق النتائج")
                }
            }
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { pageProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                    WebView(viewContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme?.lowercase()
                                if (request.isForMainFrame && (scheme == "https" || scheme == "http")) {
                                    recordBrowserEvent(
                                        if (request.isRedirect) "NAVIGATION_REDIRECT_REQUEST" else "NAVIGATION_LINK_REQUEST",
                                        if (request.isRedirect) "طلب الموقع الانتقال إلى عنوان آخر" else "طلب المتصفح فتح رابط",
                                        "navigation",
                                        request.url.toString(),
                                        severity = "INFO",
                                        details = mapOf(
                                            "is_redirect" to request.isRedirect.toString(),
                                            "http_method" to request.method,
                                            "initiator" to "page_navigation",
                                        ),
                                    )
                                    return false
                                }
                                if (scheme == "https" || scheme == "http") return false
                                recordError("BLOCKED_SCHEME", "تم منع مخطط عنوان غير مدعوم", "navigation", request.url.toString(), scheme)
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                currentUrl = url
                                address = url
                                isLoading = true
                                pageProgress = 0
                                pageLoadError = null
                                inspectMessage = null
                                currentPageTitle = ""
                                currentNavigationId = UUID.randomUUID().toString()
                                navigationStartedAtMs = System.currentTimeMillis()
                                lastProgressBucket = 0
                                resourceHttpErrorCount = 0
                                consoleErrorCount = 0
                                pageRenderStatus = "LOADING"
                                recordBrowserEvent(
                                    "NAVIGATION_STARTED",
                                    "بدأ تحميل الصفحة داخل المتصفح",
                                    "navigation",
                                    url,
                                    severity = "INFO",
                                    details = mapOf(
                                        "navigation_source" to nextNavigationSource,
                                        "progress_percent" to "0",
                                    ),
                                    pageTitle = null,
                                )
                                nextNavigationSource = "page_navigation"
                            }

                            override fun onPageCommitVisible(view: WebView, url: String) {
                                recordBrowserEvent(
                                    "PAGE_COMMITTED_VISIBLE",
                                    "بدأ WebView بإظهار محتوى الصفحة",
                                    "render",
                                    url,
                                    severity = "SUCCESS",
                                    details = mapOf("progress_percent" to view.progress.toString()),
                                )
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                currentUrl = url
                                address = url
                                currentPageTitle = view.title.orEmpty().take(160)
                                pageProgress = 100
                                isLoading = false
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                                recordBrowserEvent(
                                    "PAGE_LOAD_FINISHED",
                                    "انتهت callbacks تحميل الصفحة؛ سيجري فحص DOM لتقدير ظهور المحتوى",
                                    "navigation",
                                    url,
                                    code = "100",
                                    severity = "INFO",
                                    details = mapOf(
                                        "progress_percent" to "100",
                                        "elapsed_ms" to (System.currentTimeMillis() - navigationStartedAtMs).coerceAtLeast(0).toString(),
                                        "http_resource_errors" to resourceHttpErrorCount.toString(),
                                    ),
                                    pageTitle = currentPageTitle.takeIf { it.isNotBlank() },
                                )
                                view.evaluateJavascript(
                                    """
                                    (function() {
                                      try {
                                        const d=document, b=d.body, e=d.documentElement;
                                        const text=(b&&b.innerText?b.innerText:'').trim();
                                        const title=d.title||'';
                                        const visibleImages=[...d.images].filter(i=>i.complete&&i.naturalWidth>0&&i.getBoundingClientRect().width>0).length;
                                        const visibleContentElements=[...d.querySelectorAll('main,article,video,audio,canvas,iframe,[role="main"]')].filter(i=>{
                                          const r=i.getBoundingClientRect(), c=getComputedStyle(i);
                                          return r.width>0&&r.height>0&&c.display!=='none'&&c.visibility!=='hidden';
                                        }).length;
                                        const challenge=/verify you are human|checking your browser|just a moment|access denied|captcha|unusual traffic|confirm you are not a robot|enable javascript and cookies/i
                                          .test((title+' '+text.slice(0,5000)).toLowerCase());
                                        return JSON.stringify({
                                          readyState:d.readyState||'unknown',
                                          title:title,
                                          bodyTextChars:text.length,
                                          bodyChildCount:b?b.children.length:0,
                                          documentHeight:e?e.scrollHeight:0,
                                          viewportWidth:window.innerWidth||0,
                                          viewportHeight:window.innerHeight||0,
                                          videoCount:d.querySelectorAll('video').length,
                                          audioCount:d.querySelectorAll('audio').length,
                                          iframeCount:d.querySelectorAll('iframe').length,
                                          imageCount:d.images.length,
                                          visibleImageCount:visibleImages,
                                          visibleContentElementCount:visibleContentElements,
                                          backgroundColor:b?getComputedStyle(b).backgroundColor:'unknown',
                                          challengeDetected:challenge
                                        });
                                      } catch(e) {
                                        return JSON.stringify({diagnosticError:'dom_snapshot_failed'});
                                      }
                                    })();
                                    """.trimIndent(),
                                ) { raw ->
                                    val decoded = runCatching {
                                        JSONTokener(raw.orEmpty()).nextValue() as? String
                                    }.getOrNull()
                                    val snapshot = decoded?.let { runCatching { JSONObject(it) }.getOrNull() }
                                    if (snapshot == null) {
                                        pageRenderStatus = "DOM_DIAGNOSTIC_FAILED"
                                        recordBrowserEvent(
                                            "PAGE_RENDER_DIAGNOSTIC_FAILED",
                                            "اكتمل تحميل الصفحة لكن تعذر قراءة مؤشرات DOM",
                                            "render",
                                            url,
                                            severity = "WARNING",
                                            details = mapOf(
                                                "progress_percent" to "100",
                                                "elapsed_ms" to (System.currentTimeMillis() - navigationStartedAtMs).coerceAtLeast(0).toString(),
                                            ),
                                        )
                                        return@evaluateJavascript
                                    }
                                    currentPageTitle = snapshot.optString("title", view.title.orEmpty()).take(160)
                                    val readyState = snapshot.optString("readyState", "unknown")
                                    val bodyTextChars = snapshot.optInt("bodyTextChars", 0).coerceAtLeast(0)
                                    val bodyChildCount = snapshot.optInt("bodyChildCount", 0).coerceAtLeast(0)
                                    val documentHeight = snapshot.optInt("documentHeight", 0).coerceAtLeast(0)
                                    val videoCount = snapshot.optInt("videoCount", 0).coerceAtLeast(0)
                                    val audioCount = snapshot.optInt("audioCount", 0).coerceAtLeast(0)
                                    val imageCount = snapshot.optInt("imageCount", 0).coerceAtLeast(0)
                                    val visibleImageCount = snapshot.optInt("visibleImageCount", 0).coerceAtLeast(0)
                                    val visibleContentElementCount = snapshot.optInt("visibleContentElementCount", 0).coerceAtLeast(0)
                                    val iframeCount = snapshot.optInt("iframeCount", 0).coerceAtLeast(0)
                                    val challengeDetected = snapshot.optBoolean("challengeDetected", false)
                                    val hasContentSignals = bodyTextChars >= 12 || videoCount > 0 || audioCount > 0 ||
                                        visibleImageCount > 0 || visibleContentElementCount > 0
                                    pageRenderStatus = when {
                                        challengeDetected -> "POSSIBLE_ACCESS_CHALLENGE"
                                        hasContentSignals -> "CONTENT_SIGNALS_DETECTED"
                                        readyState == "complete" -> "POSSIBLY_EMPTY_PAGE"
                                        else -> "DOM_NOT_READY"
                                    }
                                    val renderMessage = when (pageRenderStatus) {
                                        "POSSIBLE_ACCESS_CHALLENGE" -> "اكتمل التحميل تقنيًا، لكن ظهرت مؤشرات تحقق أو حظر محتملة"
                                        "CONTENT_SIGNALS_DETECTED" -> "اكتمل التحميل ورُصد محتوى في DOM؛ هذا لا يضمن وحده صحة العرض المرئي"
                                        "POSSIBLY_EMPTY_PAGE" -> "اكتمل التحميل لكن لم تُرصد مؤشرات محتوى كافية؛ قد تكون الصفحة فارغة أو محجوبة"
                                        else -> "لم تصل مؤشرات DOM إلى حالة جاهزة"
                                    }
                                    recordBrowserEvent(
                                        "PAGE_RENDER_CHECK",
                                        renderMessage,
                                        "render",
                                        url,
                                        severity = if (pageRenderStatus == "CONTENT_SIGNALS_DETECTED") "SUCCESS" else "WARNING",
                                        details = mapOf(
                                            "render_status" to pageRenderStatus,
                                            "ready_state" to readyState,
                                            "body_text_chars" to bodyTextChars.toString(),
                                            "body_child_count" to bodyChildCount.toString(),
                                            "document_height" to documentHeight.toString(),
                                            "viewport" to "${snapshot.optInt("viewportWidth", 0)}x${snapshot.optInt("viewportHeight", 0)}",
                                            "video_count" to videoCount.toString(),
                                            "audio_count" to audioCount.toString(),
                                            "image_count" to imageCount.toString(),
                                            "visible_image_count" to visibleImageCount.toString(),
                                            "visible_content_element_count" to visibleContentElementCount.toString(),
                                            "iframe_count" to iframeCount.toString(),
                                            "background_color" to snapshot.optString("backgroundColor", "unknown"),
                                            "challenge_detected" to challengeDetected.toString(),
                                            "elapsed_ms" to (System.currentTimeMillis() - navigationStartedAtMs).coerceAtLeast(0).toString(),
                                            "http_resource_errors" to resourceHttpErrorCount.toString(),
                                        ),
                                        pageTitle = currentPageTitle.takeIf { it.isNotBlank() },
                                    )
                                    inspectMessage = when (pageRenderStatus) {
                                        "POSSIBLE_ACCESS_CHALLENGE" ->
                                            "قد يعرض الموقع صفحة تحقق أو حظر. أكمل أي تحقق يدويًا داخل الصفحة ثم أعد الفحص."
                                        "POSSIBLY_EMPTY_PAGE" ->
                                            "انتهى تحميل الصفحة لكن لم يُرصد محتوى كافٍ. افتح سجل أحداث المتصفح لمعرفة مؤشرات العرض والأخطاء."
                                        "DOM_NOT_READY", "DOM_DIAGNOSTIC_FAILED" ->
                                            "تعذر تأكيد ظهور محتوى الصفحة. راجع سجل أحداث المتصفح للتفاصيل."
                                        else -> null
                                    }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    isLoading = false
                                    val loadErrorMessage = when (error.errorCode) {
                                        android.webkit.WebViewClient.ERROR_HOST_LOOKUP ->
                                            "تعذر الوصول إلى الموقع. تحقق من اتصال الإنترنت أو إعدادات DNS."
                                        android.webkit.WebViewClient.ERROR_CONNECT,
                                        android.webkit.WebViewClient.ERROR_TIMEOUT ->
                                            "انتهت مهلة الاتصال بالموقع. حاول التحديث."
                                        android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ->
                                            "تعذر إنشاء اتصال آمن بالموقع."
                                        android.webkit.WebViewClient.ERROR_UNSUPPORTED_SCHEME ->
                                            "عنوان الموقع يستخدم بروتوكولًا غير مدعوم."
                                        else -> "لم يتم تحميل الصفحة (رمز الخطأ " + error.errorCode + "). حاول التحديث أو فتح موقع آخر."
                                    }
                                    inspectMessage = loadErrorMessage
                                    pageLoadError = loadErrorMessage
                                    pageProgress = 0
                                    recordError(
                                        "PAGE_LOAD_FAILED",
                                        loadErrorMessage,
                                        "navigation",
                                        request.url.toString(),
                                        error.errorCode.toString(),
                                    )
                                    recordBrowserEvent(
                                        "PAGE_LOAD_ERROR_DETAIL",
                                        "تعذر تحميل إطار الصفحة الرئيسي",
                                        "navigation",
                                        request.url.toString(),
                                        code = error.errorCode.toString(),
                                        severity = "ERROR",
                                        details = mapOf(
                                            "webview_error_description" to error.description.toString(),
                                            "error_code" to error.errorCode.toString(),
                                            "is_main_frame" to request.isForMainFrame.toString(),
                                            "elapsed_ms" to (System.currentTimeMillis() - navigationStartedAtMs).coerceAtLeast(0).toString(),
                                        ),
                                    )
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: android.webkit.WebResourceResponse,
                            ) {
                                val status = errorResponse.statusCode
                                if (status < 400) return
                                val mainFrame = request.isForMainFrame
                                if (mainFrame) {
                                    inspectMessage = "استجابة HTTP $status من الموقع. قد يكون المحتوى مقيدًا أو يتطلب جلسة صالحة."
                                } else {
                                    resourceHttpErrorCount++
                                    if (resourceHttpErrorCount > 8) return
                                }
                                recordBrowserEvent(
                                    if (mainFrame) "MAIN_FRAME_HTTP_ERROR" else "RESOURCE_HTTP_ERROR",
                                    if (mainFrame) "أعاد الموقع استجابة HTTP غير ناجحة" else "أعاد مورد فرعي استجابة HTTP غير ناجحة",
                                    if (mainFrame) "navigation" else "resource",
                                    request.url.toString(),
                                    code = status.toString(),
                                    severity = "WARNING",
                                    details = mapOf(
                                        "http_status" to status.toString(),
                                        "is_main_frame" to mainFrame.toString(),
                                        "request_method" to request.method,
                                        "resource_error_index" to resourceHttpErrorCount.toString(),
                                    ),
                                )
                            }

                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel()
                                isLoading = false
                                pageProgress = 0
                                pageLoadError = "تم إيقاف الصفحة بسبب مشكلة في شهادة الأمان. لم يتم تجاوز تحذير الشهادة."
                                inspectMessage = pageLoadError
                                recordError("TLS_CERTIFICATE_ERROR", "تعذر التحقق من شهادة أمان الصفحة؛ أُوقف التحميل حفاظًا على الأمان", "security", error.url, error.primaryError.toString())
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                pageProgress = newProgress
                                isLoading = pageLoadError == null && newProgress < 100
                                val bucket = newProgress.coerceIn(0, 100) / 25
                                if (bucket > lastProgressBucket && bucket > 0) {
                                    lastProgressBucket = bucket
                                    recordBrowserEvent(
                                        "PAGE_PROGRESS",
                                        "تقدم تحميل الصفحة",
                                        "navigation",
                                        view.url ?: currentUrl,
                                        code = newProgress.toString(),
                                        severity = "INFO",
                                        details = mapOf("progress_percent" to newProgress.toString()),
                                    )
                                }
                            }

                            override fun onReceivedTitle(view: WebView, title: String?) {
                                val value = title.orEmpty().take(160)
                                if (value.isNotBlank() && value != currentPageTitle) {
                                    currentPageTitle = value
                                    recordBrowserEvent(
                                        "PAGE_TITLE_UPDATED",
                                        "استقبل المتصفح عنوان الصفحة",
                                        "render",
                                        view.url ?: currentUrl,
                                        severity = "INFO",
                                        pageTitle = value,
                                    )
                                }
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR && consoleErrorCount < 8) {
                                    consoleErrorCount++
                                    recordBrowserEvent(
                                        "PAGE_CONSOLE_ERROR",
                                        "سجل JavaScript خطأ أثناء عرض الصفحة",
                                        "javascript",
                                        viewUrlOrCurrent(webView?.url, currentUrl),
                                        code = consoleMessage.lineNumber().toString(),
                                        severity = "WARNING",
                                        details = mapOf(
                                            "console_line" to consoleMessage.lineNumber().toString(),
                                            "console_source_host" to safeHost(consoleMessage.sourceId()),
                                            "console_error_index" to consoleErrorCount.toString(),
                                        ),
                                    )
                                }
                                return false
                            }
                        }
                        webView = this
                        if (currentUrl.isNotBlank()) loadUrl(currentUrl)
                    }
                    },
                    update = { },
                )
                pageLoadError?.let { message ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("تعذر تحميل الصفحة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { loadAddress() }, enabled = !isLoading) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text("إعادة المحاولة")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showResults) {
        ModalBottomSheet(onDismissRequest = { showResults = false }) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("خيارات التنزيل", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        resultTitle ?: "مصادر الصفحة",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { Text("الفيديو", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                val videoCandidates = candidates.filter { it.format.kind == MediaKind.Video && it.format.hasVideo }
                if (videoCandidates.isEmpty()) {
                    item { Text("لم يتم العثور على خيار فيديو.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(videoCandidates, key = { "video-${it.id}" }) { candidate ->
                        CandidateOption(
                            candidate = candidate,
                            title = resultTitle,
                            sourceUrl = resultSourceUrl,
                            thumbnailUrl = resultThumbnail,
                            busy = actionInProgress,
                            onDownload = {
                                actionInProgress = true
                                scope.launch {
                                    try {
                                        when (val validation = resolver.validate(candidate, "browser-download-${UUID.randomUUID()}")) {
                                            is CandidateValidationResult.Valid -> {
                                                val outcome = onDownloadRequested(validation.candidate, resultTitle, resultSourceUrl, resultThumbnail)
                                                inspectMessage = when (outcome) {
                                                    DownloadEnqueueResult.QUEUED -> "أُضيف التنزيل إلى قائمة الانتظار."
                                                    DownloadEnqueueResult.DUPLICATE -> "هذا الملف موجود بالفعل في قائمة التنزيلات."
                                                    DownloadEnqueueResult.INVALID_CUSTOM_LOCATION -> "مجلد الحفظ غير صالح؛ راجع الإعدادات."
                                                    DownloadEnqueueResult.STORAGE_UNAVAILABLE -> "مساحة التخزين أو المجلد غير متاح."
                                                    DownloadEnqueueResult.REJECTED -> "لم يقبل التطبيق طلب التنزيل."
                                                }
                                                recordBrowserEvent(
                                                    if (outcome == DownloadEnqueueResult.QUEUED) "VIDEO_DOWNLOAD_QUEUED" else "VIDEO_DOWNLOAD_NOT_QUEUED",
                                                    inspectMessage.orEmpty(),
                                                    "download",
                                                    resultSourceUrl,
                                                    code = outcome.name,
                                                    severity = if (outcome == DownloadEnqueueResult.QUEUED) "SUCCESS" else "WARNING",
                                                    details = mapOf(
                                                        "candidate_id" to validation.candidate.id,
                                                        "format" to validation.candidate.format.container.name,
                                                        "has_audio" to validation.candidate.format.hasAudio.toString(),
                                                        "has_video" to validation.candidate.format.hasVideo.toString(),
                                                        "enqueue_outcome" to outcome.name,
                                                    ),
                                                )
                                            }
                                            is CandidateValidationResult.Invalid -> {
                                                inspectMessage = validation.failure.toBrowserMessage()
                                                recordBrowserEvent(
                                                    "VIDEO_CANDIDATE_VALIDATION_FAILED",
                                                    inspectMessage.orEmpty(),
                                                    "validation",
                                                    candidate.sourceUrl,
                                                    severity = "ERROR",
                                                    details = mapOf(
                                                        "candidate_id" to candidate.id,
                                                        "validation_failure" to validation.failure.javaClass.simpleName,
                                                    ),
                                                )
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        inspectMessage = "تعذر بدء التنزيل."
                                        recordBrowserEvent(
                                            "VIDEO_DOWNLOAD_EXCEPTION",
                                            "حدث استثناء أثناء تجهيز تنزيل الفيديو",
                                            "download",
                                            resultSourceUrl,
                                            code = error.javaClass.simpleName,
                                            severity = "ERROR",
                                            details = mapOf("exception_type" to error.javaClass.simpleName),
                                        )
                                    } finally {
                                        actionInProgress = false
                                    }
                                }
                            },
                        )
                    }
                }
                item { Text("استخراج الصوت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                val audioSources = candidates.filter {
                    it.format.hasAudio || it.format.kind == MediaKind.Audio
                }
                if (audioSources.isEmpty()) {
                    item { Text("لم يتم العثور على مصدر صوت قابل للتحقق.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    item {
                        Text("اختر المصدر أولًا، ثم صيغة الصوت.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            audioSources.forEach { candidate ->
                                FilterChip(
                                    selected = selectedAudioCandidateId == candidate.id,
                                    onClick = { selectedAudioCandidateId = candidate.id },
                                    label = { Text(candidate.format.height?.let { "${it}p" } ?: candidate.format.container.name) },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AudioOutputFormat.entries.forEach { format ->
                                FilterChip(
                                    selected = selectedAudioFormat == format,
                                    onClick = { selectedAudioFormat = format },
                                    label = { Text(format.label) },
                                )
                            }
                        }
                        Button(
                            enabled = selectedAudioCandidateId != null && selectedAudioFormat != null && !actionInProgress,
                            onClick = {
                                val candidate = audioSources.firstOrNull { it.id == selectedAudioCandidateId } ?: return@Button
                                val format = selectedAudioFormat ?: return@Button
                                actionInProgress = true
                                scope.launch {
                                    try {
                                        when (val validation = resolver.validate(candidate, "browser-audio-${UUID.randomUUID()}")) {
                                            is CandidateValidationResult.Valid -> {
                                                val outcome = onAudioOnlyRequested(validation.candidate, format, resultTitle, resultSourceUrl, resultThumbnail)
                                                inspectMessage = if (outcome == DownloadEnqueueResult.QUEUED) {
                                                    "أُضيف استخراج الصوت إلى قائمة الانتظار."
                                                } else {
                                                    "تعذر إضافة استخراج الصوت: ${outcome.name}."
                                                }
                                                recordBrowserEvent(
                                                    if (outcome == DownloadEnqueueResult.QUEUED) "AUDIO_EXTRACTION_QUEUED" else "AUDIO_EXTRACTION_NOT_QUEUED",
                                                    inspectMessage.orEmpty(),
                                                    "audio",
                                                    resultSourceUrl,
                                                    code = outcome.name,
                                                    severity = if (outcome == DownloadEnqueueResult.QUEUED) "SUCCESS" else "WARNING",
                                                    details = mapOf(
                                                        "candidate_id" to validation.candidate.id,
                                                        "output_format" to format.name,
                                                        "enqueue_outcome" to outcome.name,
                                                    ),
                                                )
                                            }
                                            is CandidateValidationResult.Invalid -> {
                                                inspectMessage = validation.failure.toBrowserMessage()
                                                recordBrowserEvent(
                                                    "AUDIO_CANDIDATE_VALIDATION_FAILED",
                                                    inspectMessage.orEmpty(),
                                                    "validation",
                                                    candidate.sourceUrl,
                                                    severity = "ERROR",
                                                    details = mapOf(
                                                        "candidate_id" to candidate.id,
                                                        "validation_failure" to validation.failure.javaClass.simpleName,
                                                    ),
                                                )
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        inspectMessage = "تعذر تجهيز استخراج الصوت."
                                        recordBrowserEvent(
                                            "AUDIO_EXTRACTION_EXCEPTION",
                                            "حدث استثناء أثناء تجهيز استخراج الصوت",
                                            "audio",
                                            resultSourceUrl,
                                            code = error.javaClass.simpleName,
                                            severity = "ERROR",
                                            details = mapOf("exception_type" to error.javaClass.simpleName),
                                        )
                                    } finally {
                                        actionInProgress = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (actionInProgress) "جارٍ التجهيز…" else "استخراج الصوت المحدد")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateOption(
    candidate: MediaCandidate,
    title: String?,
    sourceUrl: String?,
    thumbnailUrl: String?,
    busy: Boolean,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    candidate.format.height?.let { "${it}p video" } ?: candidate.format.container.name,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append(candidate.format.container.name)
                        if (candidate.format.hasAudio) append(" • صوت مضمّن")
                        else append(" • مسار الصوت غير مؤكد")
                        candidate.format.fileSizeBytes?.takeIf { it > 0L }?.let { append(" • ").append(formatBytes(it)) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(enabled = !busy, onClick = onDownload) { Text("تنزيل") }
        }
    }
}

private fun normalizeBrowserUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    if ("://" !in trimmed && (trimmed.any(Char::isWhitespace) || '.' !in trimmed)) {
        return "https://www.google.com/search?q=" + Uri.encode(trimmed)
    }
    val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    return uri.toASCIIString()
}

private fun viewUrlOrCurrent(viewUrl: String?, fallback: String): String = viewUrl?.takeIf { it.isNotBlank() } ?: fallback

private fun safeHost(url: String?): String =
    runCatching { URI(url.orEmpty()).host?.lowercase() }.getOrNull()?.take(120) ?: "unknown"

private fun ValidationFailure.toBrowserMessage(): String = when (this) {
    ValidationFailure.InvalidUrl -> "رابط المصدر غير صالح."
    ValidationFailure.ProbeFailed -> "تعذر التحقق من المصدر."
    is ValidationFailure.HttpStatus -> "رفض المصدر الطلب (HTTP $code)."
    ValidationFailure.HtmlResponse -> "المصدر أعاد صفحة ويب بدل ملف وسائط."
    ValidationFailure.ContentTypeMismatch -> "نوع الملف لا يطابق الوسائط المطلوبة."
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.1f GB".format(value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> "%.1f MB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.0f KB".format(value / 1024.0)
    else -> "$value B"
}
