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
import android.webkit.ValueCallback
import android.net.http.SslError
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.testTag
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
    var pendingFileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    fun finishFileChooserResult(result: Array<Uri>?) {
        pendingFileChooserCallback?.onReceiveValue(result)
        pendingFileChooserCallback = null
    }

    val singleDocumentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        finishFileChooserResult(uri?.let { arrayOf(it) })
    }
    val multipleDocumentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        finishFileChooserResult(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
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
    var consoleDuplicateIssueCount by remember { mutableStateOf(0) }
    val consoleIssueFingerprints = remember { linkedSetOf<String>() }
    var lastPageFinishedEventKey by remember { mutableStateOf("") }
    var lastRenderSnapshotFingerprint by remember { mutableStateOf("") }
    var lastRenderRecheckScheduledNavigationId by remember { mutableStateOf("") }
    var lastVisualStateReadyNavigationId by remember { mutableStateOf("") }
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
        // Persist the requested destination before touching WebView. The AndroidView
        // factory may not have assigned webView yet; in that case the factory must
        // still receive this URL instead of silently losing the user's navigation.
        currentUrl = normalized
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
            // WebView may be destroyed while the Android document picker is open.
            // Complete the pending callback before releasing the view.
            finishFileChooserResult(null)
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
                    modifier = Modifier.weight(1f).testTag("browser-address-field"),
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
                                consoleDuplicateIssueCount = 0
                                consoleIssueFingerprints.clear()
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
                                val pageFinishedEventKey = "$currentNavigationId|$url"
                                if (lastPageFinishedEventKey != pageFinishedEventKey) {
                                    lastPageFinishedEventKey = pageFinishedEventKey
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
                                }
                                view.evaluateJavascript(
                                    """
                                    (function() {
                                      try {
                                        const d=document, b=d.body, e=d.documentElement;
                                        const text=(b&&b.innerText?b.innerText:'').trim();
                                        const title=d.title||'';
                                        const viewportWidth=window.innerWidth||0, viewportHeight=window.innerHeight||0;
                                        const devicePixelRatio=window.devicePixelRatio||1;
                                        const intersectsViewport=r=>r.width>0&&r.height>0&&r.right>0&&r.bottom>0&&r.left<viewportWidth&&r.top<viewportHeight;
                                        const hasVisibleStyle=(i,r)=>{
                                          const c=getComputedStyle(i);
                                          if(c.display==='none'||c.visibility==='hidden'||Number(c.opacity||1)<=0.05||!intersectsViewport(r)) return false;
                                          for(let p=i.parentElement;p;p=p.parentElement){
                                            if(Number(getComputedStyle(p).opacity||1)<=0.05) return false;
                                          }
                                          return true;
                                        };
                                        const parseRgb = value => {
                                          const m=String(value||'').match(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?\s*\)/i);
                                          if(!m) return null;
                                          return {r:Number(m[1]),g:Number(m[2]),b:Number(m[3]),a:m[4]===undefined?1:Number(m[4])};
                                        };
                                        const luminance = color => {
                                          const channel=v=>{v=v/255;return v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4);};
                                          return 0.2126*channel(color.r)+0.7152*channel(color.g)+0.0722*channel(color.b);
                                        };
                                        const hasReadableContrast = element => {
                                          const foreground=parseRgb(getComputedStyle(element).color);
                                          if(!foreground || foreground.a<0.35) return false;
                                          if(foreground.a<0.98) return true;
                                          let background=null;
                                          for(let p=element;p;p=p.parentElement){
                                            const style=getComputedStyle(p);
                                            if(style.backgroundImage&&style.backgroundImage!=='none') return true;
                                            const candidate=parseRgb(style.backgroundColor);
                                            if(candidate&&candidate.a>=0.95){background=candidate;break;}
                                          }
                                          if(!background) background={r:255,g:255,b:255,a:1};
                                          const a=luminance(foreground), z=luminance(background);
                                          return (Math.max(a,z)+0.05)/(Math.min(a,z)+0.05)>=1.4;
                                        };
                                        const unobscuredAt = (element,rect) => {
                                          const left=Math.max(0,rect.left), right=Math.min(viewportWidth,rect.right);
                                          const topEdge=Math.max(0,rect.top), bottomEdge=Math.min(viewportHeight,rect.bottom);
                                          if(right<=left||bottomEdge<=topEdge) return false;
                                          const x=(left+right)/2, y=(topEdge+bottomEdge)/2;
                                          const top=d.elementFromPoint(x,y);
                                          if(!top) return true;
                                          // The hit target may be the actual element, a descendant, or an
                                          // ancestor such as an <a> wrapping an image with pointer-events:none.
                                          for(let p=top;p;p=p.parentElement) if(p===element) return true;
                                          for(let p=element;p;p=p.parentElement) if(p===top) return true;
                                          return false;
                                        };
                                        let visibleImageCount=0, visibleImageArea=0, visibleLargeImageCount=0;
                                        let viewportIntersectingImageCount=0, hitTestRejectedImageCount=0;
                                        [...d.images].forEach(i=>{
                                          const r=i.getBoundingClientRect();
                                          if(!i.complete||i.naturalWidth<=0||!hasVisibleStyle(i,r)) return;
                                          const width=Math.max(0,Math.min(viewportWidth,r.right)-Math.max(0,r.left));
                                          const height=Math.max(0,Math.min(viewportHeight,r.bottom)-Math.max(0,r.top));
                                          if(width<=0||height<=0) return;
                                          viewportIntersectingImageCount++;
                                          if(!unobscuredAt(i,r)) {
                                            hitTestRejectedImageCount++;
                                            return;
                                          }
                                          visibleImageCount++;
                                          visibleImageArea+=width*height;
                                          if(width>=48&&height>=36) visibleLargeImageCount++;
                                        });
                                        const visibleImageAreaRatio=viewportWidth>0&&viewportHeight>0
                                          ? Math.min(1,visibleImageArea/(viewportWidth*viewportHeight)) : 0;
                                        let visibleTextChars=0, readableVisibleTextChars=0, scannedTextNodes=0;
                                        let viewportIntersectingTextNodeCount=0, hitTestRejectedTextNodeCount=0;
                                        if(b){
                                          const walker=d.createTreeWalker(b,NodeFilter.SHOW_TEXT);
                                          let node;
                                          while((node=walker.nextNode())&&scannedTextNodes<1500&&visibleTextChars<10000){
                                            scannedTextNodes++;
                                            const value=(node.nodeValue||'').replace(/\s+/g,' ').trim();
                                            if(!value) continue;
                                            const parent=node.parentElement;
                                            if(!parent) continue;
                                            const parentRect=parent.getBoundingClientRect();
                                            if(!hasVisibleStyle(parent,parentRect)) continue;
                                            const range=d.createRange();
                                            range.selectNodeContents(node);
                                            const viewportRects=Array.from(range.getClientRects()).filter(r=>intersectsViewport(r));
                                            if(viewportRects.length===0) continue;
                                            viewportIntersectingTextNodeCount++;
                                            const visibleText=viewportRects.some(r=>unobscuredAt(parent,r));
                                            if(!visibleText) {
                                              hitTestRejectedTextNodeCount++;
                                              continue;
                                            }
                                            visibleTextChars=Math.min(10000,visibleTextChars+value.length);
                                            if(hasReadableContrast(parent)) readableVisibleTextChars=Math.min(10000,readableVisibleTextChars+value.length);
                                          }
                                        }
                                        const visibleMediaElementCount=[...d.querySelectorAll('video,canvas,iframe')].filter(i=>{
                                          const r=i.getBoundingClientRect();
                                          if(!hasVisibleStyle(i,r)||r.width<32||r.height<18||!unobscuredAt(i,r)) return false;
                                          if(i.tagName==='VIDEO') return i.videoWidth>0&&i.videoHeight>0&&i.readyState>=2;
                                          if(i.tagName==='CANVAS') return i.width>0&&i.height>0;
                                          return true;
                                        }).length;
                                        const visibleContentElements=[...d.querySelectorAll('main,article,video,audio,canvas,iframe,[role="main"]')].filter(i=>{
                                          const r=i.getBoundingClientRect();
                                          if(!hasVisibleStyle(i,r)) return false;
                                          if(['VIDEO','CANVAS','IFRAME'].includes(i.tagName)) return visibleMediaElementCount>0;
                                          if(i.tagName==='AUDIO') return false;
                                          return readableVisibleTextChars>=12;
                                        }).length;
                                        const challenge=/verify you are human|checking your browser|just a moment|access denied|captcha|unusual traffic|confirm you are not a robot|enable javascript and cookies/i
                                          .test((title+' '+text.slice(0,5000)).toLowerCase());
                                        return JSON.stringify({
                                          readyState:d.readyState||'unknown',
                                          title:title,
                                          bodyTextChars:text.length,
                                          bodyChildCount:b?b.children.length:0,
                                          documentHeight:e?e.scrollHeight:0,
                                          viewportWidth:viewportWidth,
                                          viewportHeight:viewportHeight,
                                          devicePixelRatio:devicePixelRatio,
                                          visibleTextChars:visibleTextChars,
                                          visibleMediaElementCount:visibleMediaElementCount,
                                          videoCount:d.querySelectorAll('video').length,
                                          audioCount:d.querySelectorAll('audio').length,
                                          iframeCount:d.querySelectorAll('iframe').length,
                                          imageCount:d.images.length,
                                          visibleImageCount:visibleImageCount,
                                          viewportIntersectingImageCount:viewportIntersectingImageCount,
                                          hitTestRejectedImageCount:hitTestRejectedImageCount,
                                          visibleImageAreaRatio:visibleImageAreaRatio,
                                          visibleLargeImageCount:visibleLargeImageCount,
                                          viewportIntersectingTextNodeCount:viewportIntersectingTextNodeCount,
                                          hitTestRejectedTextNodeCount:hitTestRejectedTextNodeCount,
                                          readableVisibleTextChars:readableVisibleTextChars,
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
                                    val viewportIntersectingImageCount = snapshot.optInt("viewportIntersectingImageCount", 0).coerceAtLeast(0)
                                    val hitTestRejectedImageCount = snapshot.optInt("hitTestRejectedImageCount", 0).coerceAtLeast(0)
                                    val visibleImageAreaRatio = snapshot.optDouble("visibleImageAreaRatio", 0.0).coerceIn(0.0, 1.0)
                                    val visibleLargeImageCount = snapshot.optInt("visibleLargeImageCount", 0).coerceAtLeast(0)
                                    val visibleTextChars = snapshot.optInt("visibleTextChars", 0).coerceAtLeast(0)
                                    val viewportIntersectingTextNodeCount = snapshot.optInt("viewportIntersectingTextNodeCount", 0).coerceAtLeast(0)
                                    val hitTestRejectedTextNodeCount = snapshot.optInt("hitTestRejectedTextNodeCount", 0).coerceAtLeast(0)
                                    val readableVisibleTextChars = snapshot.optInt("readableVisibleTextChars", 0).coerceAtLeast(0)
                                    val visibleMediaElementCount = snapshot.optInt("visibleMediaElementCount", 0).coerceAtLeast(0)
                                    val visibleContentElementCount = snapshot.optInt("visibleContentElementCount", 0).coerceAtLeast(0)
                                    val iframeCount = snapshot.optInt("iframeCount", 0).coerceAtLeast(0)
                                    val challengeDetected = snapshot.optBoolean("challengeDetected", false)
                                    val hasVisibleContentSignals = visibleTextChars >= 12 ||
                                        visibleImageCount > 0 || visibleMediaElementCount > 0
                                    val hasPrimaryVisibleContent = readableVisibleTextChars >= 12 ||
                                        visibleImageAreaRatio >= 0.025 || visibleMediaElementCount > 0
                                    val hasHitTestRejectedContent = (
                                        viewportIntersectingTextNodeCount > 0 && hitTestRejectedTextNodeCount == viewportIntersectingTextNodeCount
                                    ) || (
                                        viewportIntersectingImageCount > 0 && hitTestRejectedImageCount == viewportIntersectingImageCount
                                    )
                                    val hasAnyDomContentSignals = bodyTextChars >= 12 ||
                                        videoCount > 0 || audioCount > 0 || imageCount > 0 || bodyChildCount > 0
                                    pageRenderStatus = BrowserRenderStatusClassifier.classify(
                                        challengeDetected = challengeDetected,
                                        readyState = readyState,
                                        hasVisibleContentSignals = hasVisibleContentSignals,
                                        hasAnyDomContentSignals = hasAnyDomContentSignals,
                                        hasPrimaryVisibleContent = hasPrimaryVisibleContent,
                                        hasOccludedViewportContent = hasHitTestRejectedContent,
                                    )
                                    val renderMessage = when (pageRenderStatus) {
                                        "POSSIBLE_ACCESS_CHALLENGE" -> "اكتمل التحميل تقنيًا، لكن ظهرت مؤشرات تحقق أو حظر محتملة"
                                        "CONTENT_SIGNALS_DETECTED" -> "اكتمل التحميل وتأكد وجود نص مقروء أو محتوى أساسي ظاهر داخل نافذة العرض"
                                        "CONTENT_VISIBILITY_UNCONFIRMED" -> "اكتُشفت إشارات سطحية، لكن لم يتأكد ظهور المحتوى الأساسي"
                                        "CONTENT_OCCLUDED_OR_HIT_TEST_MISMATCH" -> "عُثر على عناصر داخل نافذة العرض لكن اختبار الطبقة العلوية لم يؤكد ظهورها؛ قد توجد طبقة تغطي المحتوى أو اختلاف في hit-test"
                                        "CONTENT_NOT_VISIBLE_IN_VIEWPORT" -> "اكتمل المستند، لكن لم يتأكد ظهور محتوى داخل نافذة العرض؛ قد يكون المحتوى مخفيًا أو خارجها"
                                        "POSSIBLY_EMPTY_PAGE" -> "اكتمل التحميل لكن لم تُرصد مؤشرات محتوى كافية؛ قد تكون الصفحة فارغة أو محجوبة"
                                        else -> "لم تصل مؤشرات DOM إلى حالة جاهزة"
                                    }
                                    val renderSnapshotFingerprint = "$currentNavigationId|$url|${snapshot}"
                                    if (lastRenderSnapshotFingerprint != renderSnapshotFingerprint) {
                                        lastRenderSnapshotFingerprint = renderSnapshotFingerprint
                                        recordBrowserEvent(
                                            "PAGE_RENDER_CHECK",
                                            renderMessage,
                                            "render",
                                            url,
                                            severity = if (pageRenderStatus == "CONTENT_SIGNALS_DETECTED") "INFO" else "WARNING",
                                            details = mapOf(
                                                "render_status" to pageRenderStatus,
                                                "ready_state" to readyState,
                                                "body_text_chars" to bodyTextChars.toString(),
                                                "body_child_count" to bodyChildCount.toString(),
                                                "document_height" to documentHeight.toString(),
                                                "document_height_css_px" to documentHeight.toString(),
                                                "viewport" to "${snapshot.optInt("viewportWidth", 0)}x${snapshot.optInt("viewportHeight", 0)}",
                                                "viewport_css_px" to "${snapshot.optInt("viewportWidth", 0)}x${snapshot.optInt("viewportHeight", 0)}",
                                                "device_pixel_ratio" to snapshot.optDouble("devicePixelRatio", 1.0).toString(),
                                                "webview_width_px" to view.width.toString(),
                                                "webview_height_px" to view.height.toString(),
                                                "video_count" to videoCount.toString(),
                                                "audio_count" to audioCount.toString(),
                                                "image_count" to imageCount.toString(),
                                                "visible_image_count" to visibleImageCount.toString(),
                                                "viewport_intersecting_image_count" to viewportIntersectingImageCount.toString(),
                                                "hit_test_rejected_image_count" to hitTestRejectedImageCount.toString(),
                                                "visible_large_image_count" to visibleLargeImageCount.toString(),
                                                "visible_image_area_ratio" to "%.4f".format(java.util.Locale.ROOT, visibleImageAreaRatio),
                                                "visible_text_chars" to visibleTextChars.toString(),
                                                "viewport_intersecting_text_node_count" to viewportIntersectingTextNodeCount.toString(),
                                                "hit_test_rejected_text_node_count" to hitTestRejectedTextNodeCount.toString(),
                                                "readable_visible_text_chars" to readableVisibleTextChars.toString(),
                                                "visible_media_element_count" to visibleMediaElementCount.toString(),
                                                "visible_content_element_count" to visibleContentElementCount.toString(),
                                                "has_visible_content_signals" to hasVisibleContentSignals.toString(),
                                                "visible_content_signal_sources" to listOfNotNull(
                                                    "visible_text".takeIf { visibleTextChars >= 12 },
                                                    "visible_images".takeIf { visibleImageCount > 0 },
                                                    "visible_media".takeIf { visibleMediaElementCount > 0 },
                                                ).joinToString(",").ifBlank { "none" },
                                                "visible_content_element_scope" to "visible_semantic_containers_and_renderable_media_only",
                                                "content_visibility_basis" to "viewport_intersection_visible_style_contrast_and_hit_test",
                                                "iframe_count" to iframeCount.toString(),
                                                "background_color" to snapshot.optString("backgroundColor", "unknown"),
                                                "challenge_detected" to challengeDetected.toString(),
                                                "elapsed_ms" to (System.currentTimeMillis() - navigationStartedAtMs).coerceAtLeast(0).toString(),
                                                "http_resource_errors" to resourceHttpErrorCount.toString(),
                                                "console_issue_events_recorded" to consoleErrorCount.toString(),
                                                "console_duplicate_issues_suppressed" to consoleDuplicateIssueCount.toString(),
                                            ),
                                            pageTitle = currentPageTitle.takeIf { it.isNotBlank() },
                                        )
                                    }
                                    val visualStateNavigationId = currentNavigationId
                                    val visualStateRequestId = System.currentTimeMillis().coerceAtLeast(1L)
                                    runCatching {
                                        view.postVisualStateCallback(
                                            visualStateRequestId,
                                            object : WebView.VisualStateCallback() {
                                                override fun onComplete(requestId: Long) {
                                                    if (currentNavigationId == visualStateNavigationId &&
                                                        lastVisualStateReadyNavigationId != visualStateNavigationId
                                                    ) {
                                                        lastVisualStateReadyNavigationId = visualStateNavigationId
                                                        recordBrowserEvent(
                                                            "PAGE_VISUAL_STATE_READY",
                                                            "أصبح تحديث الرسم الحالي جاهزًا للعرض؛ لا يؤكد ذلك وحده صحة جميع العناصر بصريًا",
                                                            "render",
                                                            url,
                                                            severity = "INFO",
                                                            details = mapOf(
                                                                "visual_state_ready" to "true",
                                                                "visual_state_request_id" to requestId.toString(),
                                                                "webview_width_px" to view.width.toString(),
                                                                "webview_height_px" to view.height.toString(),
                                                                "navigation_elapsed_ms" to (System.currentTimeMillis() - navigationStartedAtMs).coerceAtLeast(0).toString(),
                                                            ),
                                                            pageTitle = currentPageTitle.takeIf { it.isNotBlank() },
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }.onFailure { error ->
                                        recordBrowserEvent(
                                            "PAGE_VISUAL_STATE_CALLBACK_FAILED",
                                            "تعذر تأكيد جاهزية حالة الرسم في WebView",
                                            "render",
                                            url,
                                            code = error.javaClass.simpleName,
                                            severity = "WARNING",
                                            details = mapOf(
                                                "webview_width_px" to view.width.toString(),
                                                "webview_height_px" to view.height.toString(),
                                            ),
                                        )
                                    }
                                    inspectMessage = when (pageRenderStatus) {
                                        "POSSIBLE_ACCESS_CHALLENGE" ->
                                            "قد يعرض الموقع صفحة تحقق أو حظر. أكمل أي تحقق يدويًا داخل الصفحة ثم أعد الفحص."
                                        "CONTENT_VISIBILITY_UNCONFIRMED" ->
                                            "اكتُشفت إشارات للصفحة، لكن لم يتأكد ظهور محتواها الأساسي. سيعيد المتصفح فحصها بعد استقرار العرض."
                                        "CONTENT_OCCLUDED_OR_HIT_TEST_MISMATCH" ->
                                            "رُصدت عناصر داخل نافذة العرض لكن اختبار الرؤية لم يؤكدها؛ قد تغطيها طبقة أخرى أو يكون فحص اللمس غير مطابق."
                                        "CONTENT_NOT_VISIBLE_IN_VIEWPORT" ->
                                            "لم يتأكد وجود محتوى مرئي داخل نافذة العرض. راجع أبعاد WebView ومؤشرات سجل أحداث المتصفح."
                                        "POSSIBLY_EMPTY_PAGE" ->
                                            "انتهى تحميل الصفحة لكن لم يُرصد محتوى كافٍ. افتح سجل أحداث المتصفح لمعرفة مؤشرات العرض والأخطاء."
                                        "DOM_NOT_READY", "DOM_DIAGNOSTIC_FAILED" ->
                                            "تعذر تأكيد ظهور محتوى الصفحة. راجع سجل أحداث المتصفح للتفاصيل."
                                        else -> null
                                    }
                                    val renderRecheckNavigationId = currentNavigationId
                                    if (lastRenderRecheckScheduledNavigationId != renderRecheckNavigationId) {
                                        lastRenderRecheckScheduledNavigationId = renderRecheckNavigationId
                                        view.postDelayed({
                                        if (view.isAttachedToWindow &&
                                            currentNavigationId == renderRecheckNavigationId &&
                                            view.url == url &&
                                            !isLoading
                                        ) {
                                            view.evaluateJavascript(PAGE_RENDER_RECHECK_SCRIPT) { recheckRaw ->
                                                val recheckJson = runCatching {
                                                    JSONTokener(recheckRaw.orEmpty()).nextValue() as? String
                                                }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
                                                if (recheckJson != null &&
                                                    !recheckJson.has("diagnosticError") &&
                                                    view.isAttachedToWindow &&
                                                    currentNavigationId == renderRecheckNavigationId &&
                                                    view.url == url
                                                ) {
                                                    val recheckReadyState = recheckJson.optString("readyState", "unknown")
                                                    val recheckBodyChars = recheckJson.optInt("bodyTextChars", 0).coerceAtLeast(0)
                                                    val recheckChildCount = recheckJson.optInt("bodyChildCount", 0).coerceAtLeast(0)
                                                    val recheckHeight = recheckJson.optInt("documentHeight", 0).coerceAtLeast(0)
                                                    val recheckVideoCount = recheckJson.optInt("videoCount", 0).coerceAtLeast(0)
                                                    val recheckAudioCount = recheckJson.optInt("audioCount", 0).coerceAtLeast(0)
                                                    val recheckImageCount = recheckJson.optInt("imageCount", 0).coerceAtLeast(0)
                                                    val recheckVisibleImages = recheckJson.optInt("visibleImageCount", 0).coerceAtLeast(0)
                                                    val recheckIntersectingImages = recheckJson.optInt("viewportIntersectingImageCount", 0).coerceAtLeast(0)
                                                    val recheckRejectedImages = recheckJson.optInt("hitTestRejectedImageCount", 0).coerceAtLeast(0)
                                                    val recheckVisibleImageArea = recheckJson.optDouble("visibleImageAreaRatio", 0.0).coerceIn(0.0, 1.0)
                                                    val recheckVisibleText = recheckJson.optInt("visibleTextChars", 0).coerceAtLeast(0)
                                                    val recheckIntersectingTextNodes = recheckJson.optInt("viewportIntersectingTextNodeCount", 0).coerceAtLeast(0)
                                                    val recheckRejectedTextNodes = recheckJson.optInt("hitTestRejectedTextNodeCount", 0).coerceAtLeast(0)
                                                    val recheckReadableText = recheckJson.optInt("readableVisibleTextChars", 0).coerceAtLeast(0)
                                                    val recheckVisibleMedia = recheckJson.optInt("visibleMediaElementCount", 0).coerceAtLeast(0)
                                                    val recheckVisibleSignals = recheckVisibleText >= 12 ||
                                                        recheckVisibleImages > 0 || recheckVisibleMedia > 0
                                                    val recheckPrimaryContent = recheckReadableText >= 12 ||
                                                        recheckVisibleImageArea >= 0.025 || recheckVisibleMedia > 0
                                                    val recheckHasHitTestRejectedContent = (
                                                        recheckIntersectingTextNodes > 0 &&
                                                        recheckRejectedTextNodes == recheckIntersectingTextNodes
                                                    ) || (
                                                        recheckIntersectingImages > 0 &&
                                                        recheckRejectedImages == recheckIntersectingImages
                                                    )
                                                    val recheckAnyDom = recheckBodyChars >= 12 ||
                                                        recheckVideoCount > 0 || recheckAudioCount > 0 ||
                                                        recheckImageCount > 0 || recheckChildCount > 0
                                                    val recheckStatus = BrowserRenderStatusClassifier.classify(
                                                        challengeDetected = recheckJson.optBoolean("challengeDetected", false),
                                                        readyState = recheckReadyState,
                                                        hasVisibleContentSignals = recheckVisibleSignals,
                                                        hasAnyDomContentSignals = recheckAnyDom,
                                                        hasPrimaryVisibleContent = recheckPrimaryContent,
                                                        hasOccludedViewportContent = recheckHasHitTestRejectedContent,
                                                    )
                                                    pageRenderStatus = recheckStatus
                                                    if (!inspecting && !showResults && !actionInProgress && pageLoadError == null) {
                                                        inspectMessage = when (recheckStatus) {
                                                            "POSSIBLE_ACCESS_CHALLENGE" ->
                                                                "قد يعرض الموقع صفحة تحقق أو حظر. أكمل التحقق يدويًا ثم أعد الفحص."
                                                            "CONTENT_VISIBILITY_UNCONFIRMED" ->
                                                                "ظهرت إشارات محدودة فقط؛ لم يتأكد عرض المحتوى الأساسي. جرّب تحديث الصفحة."
                                                            "CONTENT_OCCLUDED_OR_HIT_TEST_MISMATCH" ->
                                                                "توجد عناصر ضمن نافذة العرض لكن اختبار الرؤية لم يؤكدها؛ قد تكون مغطاة أو لم يُطابق الفاحص العنصر الصحيح."
                                                            "CONTENT_NOT_VISIBLE_IN_VIEWPORT" ->
                                                                "اكتمل المستند لكن المحتوى غير ظاهر داخل نافذة العرض. راجع سجل المتصفح."
                                                            "POSSIBLY_EMPTY_PAGE" ->
                                                                "انتهى التحميل دون محتوى كافٍ. راجع سجل أحداث المتصفح."
                                                            "DOM_NOT_READY", "DOM_DIAGNOSTIC_FAILED" ->
                                                                "تعذر تأكيد ظهور المحتوى. راجع سجل أحداث المتصفح."
                                                            else -> null
                                                        }
                                                    }
                                                    recordBrowserEvent(
                                                        "PAGE_RENDER_RECHECK",
                                                        when (recheckStatus) {
                                                            "CONTENT_SIGNALS_DETECTED" -> "أكد الفحص المؤجل وجود نص مقروء أو محتوى أساسي ظاهر"
                                                            "CONTENT_VISIBILITY_UNCONFIRMED" -> "ظل ظهور المحتوى الأساسي غير مؤكد بعد مهلة الاستقرار"
                                                            "CONTENT_NOT_VISIBLE_IN_VIEWPORT" -> "ما زالت إشارات DOM غير ظاهرة داخل نافذة العرض"
                                                            "CONTENT_OCCLUDED_OR_HIT_TEST_MISMATCH" -> "رُصدت عناصر ضمن نافذة العرض لكن اختبار الطبقة العلوية لم يؤكدها"
                                                            "POSSIBLE_ACCESS_CHALLENGE" -> "ظهرت مؤشرات تحقق أو حظر أثناء إعادة الفحص"
                                                            "POSSIBLY_EMPTY_PAGE" -> "لم يُرصد محتوى كافٍ بعد إعادة الفحص"
                                                            else -> "لم تكتمل مؤشرات ظهور المحتوى بعد إعادة الفحص"
                                                        },
                                                        "render",
                                                        url,
                                                        severity = if (recheckStatus == "CONTENT_SIGNALS_DETECTED") "INFO" else "WARNING",
                                                        details = mapOf(
                                                            "render_status" to recheckStatus,
                                                            "recheck_delay_ms" to "1400",
                                                            "ready_state" to recheckReadyState,
                                                            "body_text_chars" to recheckBodyChars.toString(),
                                                            "body_child_count" to recheckChildCount.toString(),
                                                            "document_height_css_px" to recheckHeight.toString(),
                                                            "video_count" to recheckVideoCount.toString(),
                                                            "audio_count" to recheckAudioCount.toString(),
                                                            "image_count" to recheckImageCount.toString(),
                                                            "visible_image_count" to recheckVisibleImages.toString(),
                                                            "viewport_intersecting_image_count" to recheckIntersectingImages.toString(),
                                                            "hit_test_rejected_image_count" to recheckRejectedImages.toString(),
                                                            "visible_image_area_ratio" to "%.4f".format(java.util.Locale.ROOT, recheckVisibleImageArea),
                                                            "visible_text_chars" to recheckVisibleText.toString(),
                                                            "viewport_intersecting_text_node_count" to recheckIntersectingTextNodes.toString(),
                                                            "hit_test_rejected_text_node_count" to recheckRejectedTextNodes.toString(),
                                                            "readable_visible_text_chars" to recheckReadableText.toString(),
                                                            "visible_media_element_count" to recheckVisibleMedia.toString(),
                                                            "navigation_id" to renderRecheckNavigationId,
                                                            "console_issue_events_recorded" to consoleErrorCount.toString(),
                                                            "console_duplicate_issues_suppressed" to consoleDuplicateIssueCount.toString(),
                                                        ),
                                                        pageTitle = currentPageTitle.takeIf { it.isNotBlank() },
                                                    )
                                                } else if (view.isAttachedToWindow &&
                                                    currentNavigationId == renderRecheckNavigationId &&
                                                    view.url == url
                                                ) {
                                                    recordBrowserEvent(
                                                        "PAGE_RENDER_RECHECK_FAILED",
                                                        "تعذر قراءة مؤشرات DOM أثناء الفحص المؤجل",
                                                        "render",
                                                        url,
                                                        code = "snapshot_unavailable",
                                                        severity = "WARNING",
                                                        details = mapOf(
                                                            "navigation_id" to renderRecheckNavigationId,
                                                            "recheck_delay_ms" to "1400",
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                        }, 1400L)
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
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?,
                            ): Boolean {
                                if (filePathCallback == null || fileChooserParams == null) return false

                                // A new chooser request replaces an older pending request deterministically.
                                finishFileChooserResult(null)
                                pendingFileChooserCallback = filePathCallback
                                val acceptedTypes = fileChooserParams.acceptTypes
                                    .orEmpty()
                                    .flatMap { it.orEmpty().split(',', ';') }
                                    .map { it.trim().lowercase(java.util.Locale.ROOT) }
                                    .filter { it.matches(Regex("""^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+*-]+$""")) }
                                    .distinct()
                                    .ifEmpty { listOf("*/*") }
                                    .toTypedArray()

                                return try {
                                    recordBrowserEvent(
                                        "FILE_CHOOSER_REQUESTED",
                                        "طلبت الصفحة اختيار ملف من المستخدم",
                                        "file_chooser",
                                        webView?.url ?: currentUrl,
                                        severity = "INFO",
                                        details = mapOf(
                                            "selection_mode" to if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) "multiple" else "single",
                                            "accepted_mime_type_count" to acceptedTypes.size.toString(),
                                            "capture_requested" to fileChooserParams.isCaptureEnabled.toString(),
                                            "picker" to "android_document_picker",
                                        ),
                                    )
                                    if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                                        multipleDocumentPicker.launch(acceptedTypes)
                                    } else {
                                        singleDocumentPicker.launch(acceptedTypes)
                                    }
                                    true
                                } catch (error: Exception) {
                                    finishFileChooserResult(null)
                                    recordBrowserEvent(
                                        "FILE_CHOOSER_LAUNCH_FAILED",
                                        "تعذر فتح منتقي الملفات للنموذج الحالي",
                                        "file_chooser",
                                        webView?.url ?: currentUrl,
                                        code = error.javaClass.simpleName,
                                        severity = "ERROR",
                                        details = mapOf(
                                            "exception_type" to error.javaClass.simpleName,
                                            "selection_mode" to if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) "multiple" else "single",
                                        ),
                                    )
                                    true
                                }
                            }

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
                                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                    val pageUrl = viewUrlOrCurrent(webView?.url, currentUrl)
                                    val sourceHost = safeHost(consoleMessage.sourceId())
                                    val sanitizedMessage = BrowserConsoleMessageSanitizer.sanitize(consoleMessage.message())
                                    val issue = BrowserConsoleIssueClassifier.classify(
                                        rawMessage = consoleMessage.message(),
                                        pageHost = safeHost(pageUrl),
                                        sourceHost = sourceHost,
                                    )
                                    val fingerprint = BrowserConsoleIssueClassifier.deduplicationKey(
                                        type = issue.type,
                                        sourceHost = sourceHost,
                                        line = consoleMessage.lineNumber(),
                                        sanitizedMessage = sanitizedMessage,
                                    )
                                    if (!consoleIssueFingerprints.add(fingerprint)) {
                                        consoleDuplicateIssueCount = (consoleDuplicateIssueCount + 1).coerceAtMost(999)
                                        return false
                                    }
                                    if (consoleIssueFingerprints.size > 64) {
                                        val iterator = consoleIssueFingerprints.iterator()
                                        if (iterator.hasNext()) {
                                            iterator.next()
                                            iterator.remove()
                                        }
                                    }
                                    // Keep console diagnostics bounded: the first eight distinct issues
                                    // are retained, while duplicate messages above are counted separately.
                                    if (consoleErrorCount < 8) {
                                        consoleErrorCount++
                                        recordBrowserEvent(
                                            issue.type,
                                            issue.summary,
                                            "javascript",
                                            pageUrl,
                                            code = consoleMessage.lineNumber().toString(),
                                            severity = issue.severity,
                                            details = mapOf(
                                                "console_line" to consoleMessage.lineNumber().toString(),
                                                "console_source_host" to sourceHost,
                                                "console_message_level" to consoleMessage.messageLevel().name,
                                                "console_message" to sanitizedMessage,
                                                "console_error_index" to consoleErrorCount.toString(),
                                                "console_issue_category" to issue.category,
                                                "console_issue_scope" to issue.scope,
                                                "console_deduplication" to "navigation_source_line_category_message",
                                            ),
                                        )
                                    }
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

private val PAGE_RENDER_RECHECK_SCRIPT = """
(function() {
  try {
    const d=document,b=d.body,e=d.documentElement;
    const vw=window.innerWidth||0,vh=window.innerHeight||0;
    const intersects=r=>r.width>0&&r.height>0&&r.right>0&&r.bottom>0&&r.left<vw&&r.top<vh;
    const visible=(el,r)=>{
      const style=getComputedStyle(el);
      if(style.display==='none'||style.visibility==='hidden'||Number(style.opacity||1)<=0.05||!intersects(r)) return false;
      for(let p=el.parentElement;p;p=p.parentElement) if(Number(getComputedStyle(p).opacity||1)<=0.05) return false;
      return true;
    };
    const rgb=value=>{
      const m=String(value||'').match(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?\s*\)/i);
      return m?{r:Number(m[1]),g:Number(m[2]),b:Number(m[3]),a:m[4]===undefined?1:Number(m[4])}:null;
    };
    const lum=c=>{const f=v=>{v=v/255;return v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4);};return 0.2126*f(c.r)+0.7152*f(c.g)+0.0722*f(c.b);};
    const readable=el=>{
      const fg=rgb(getComputedStyle(el).color);
      if(!fg||fg.a<0.35) return false;
      if(fg.a<0.98) return true;
      let bg=null;
      for(let p=el;p;p=p.parentElement){
        const s=getComputedStyle(p);
        if(s.backgroundImage&&s.backgroundImage!=='none') return true;
        const c=rgb(s.backgroundColor);
        if(c&&c.a>=0.95){bg=c;break;}
      }
      if(!bg) bg={r:255,g:255,b:255,a:1};
      const a=lum(fg),z=lum(bg);
      return (Math.max(a,z)+0.05)/(Math.min(a,z)+0.05)>=1.4;
    };
    const unobscured=(el,r)=>{
      const left=Math.max(0,r.left),right=Math.min(vw,r.right),topEdge=Math.max(0,r.top),bottomEdge=Math.min(vh,r.bottom);
      if(right<=left||bottomEdge<=topEdge) return false;
      const top=d.elementFromPoint((left+right)/2,(topEdge+bottomEdge)/2);
      if(!top) return true;
      // elementFromPoint can return a wrapper ancestor (for example <a> around
      // an image with pointer-events disabled), not only the painted child.
      for(let p=top;p;p=p.parentElement) if(p===el) return true;
      for(let p=el;p;p=p.parentElement) if(p===top) return true;
      return false;
    };
    let imageCount=0,imageArea=0,visibleText=0,readableText=0,nodes=0;
    let intersectingImages=0,rejectedImages=0,intersectingTextNodes=0,rejectedTextNodes=0;
    const images=[...d.images];
    images.forEach(i=>{
      const r=i.getBoundingClientRect();
      if(!i.complete||i.naturalWidth<=0||!visible(i,r)) return;
      const w=Math.max(0,Math.min(vw,r.right)-Math.max(0,r.left));
      const h=Math.max(0,Math.min(vh,r.bottom)-Math.max(0,r.top));
      if(w<=0||h<=0) return;
      intersectingImages++;
      if(!unobscured(i,r)){rejectedImages++;return;}
      imageCount++;imageArea+=w*h;
    });
    if(b){
      const walker=d.createTreeWalker(b,NodeFilter.SHOW_TEXT);
      let n;
      while((n=walker.nextNode())&&nodes<1000&&visibleText<10000){
        nodes++;
        const value=(n.nodeValue||'').replace(/\s+/g,' ').trim();
        const el=n.parentElement;
        if(!value||!el||!visible(el,el.getBoundingClientRect())) continue;
        const range=d.createRange();range.selectNodeContents(n);
        const rects=[...range.getClientRects()].filter(r=>intersects(r));
        if(rects.length===0) continue;
        intersectingTextNodes++;
        if(!rects.some(r=>unobscured(el,r))){rejectedTextNodes++;continue;}
        visibleText=Math.min(10000,visibleText+value.length);
        if(readable(el)) readableText=Math.min(10000,readableText+value.length);
      }
    }
    const visibleMedia=[...d.querySelectorAll('video,canvas,iframe')].filter(el=>{
      const r=el.getBoundingClientRect();
      if(!visible(el,r)||r.width<32||r.height<18||!unobscured(el,r)) return false;
      if(el.tagName==='VIDEO') return el.videoWidth>0&&el.videoHeight>0&&el.readyState>=2;
      if(el.tagName==='CANVAS') return el.width>0&&el.height>0;
      return true;
    }).length;
    const videoCount=d.querySelectorAll('video').length;
    const audioCount=d.querySelectorAll('audio').length;
    const bodyTextChars=(b&&b.innerText?b.innerText:'').trim().length;
    const imageAreaRatio=vw>0&&vh>0?Math.min(1,imageArea/(vw*vh)):0;
    const text=(d.title+' '+(b&&b.innerText?b.innerText:'').slice(0,5000)).toLowerCase();
    const challenge=/verify you are human|checking your browser|just a moment|access denied|captcha|unusual traffic|confirm you are not a robot|enable javascript and cookies/i.test(text);
    return JSON.stringify({
      readyState:d.readyState||'unknown',bodyTextChars:bodyTextChars,bodyChildCount:b?b.children.length:0,
      documentHeight:e?e.scrollHeight:0,videoCount:videoCount,audioCount:audioCount,imageCount:images.length,
      visibleImageCount:imageCount,viewportIntersectingImageCount:intersectingImages,
      hitTestRejectedImageCount:rejectedImages,visibleImageAreaRatio:imageAreaRatio,
      viewportIntersectingTextNodeCount:intersectingTextNodes,hitTestRejectedTextNodeCount:rejectedTextNodes,
      visibleTextChars:visibleText,readableVisibleTextChars:readableText,
      visibleMediaElementCount:visibleMedia,challengeDetected:challenge
    });
  } catch(e) { return JSON.stringify({diagnosticError:'recheck_failed'}); }
})()
""".trimIndent()

