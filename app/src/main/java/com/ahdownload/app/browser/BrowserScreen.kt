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
import androidx.compose.foundation.layout.defaultMinSize
import com.ahdownload.core.designsystem.AHCard
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.analyzer.LinkAnalyzer
import com.ahdownload.domain.download.AudioOutputFormat
import com.ahdownload.domain.download.isTrustedYouTubePlaybackUrl
import com.ahdownload.domain.download.DownloadEnqueueResult
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaSourceContext
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
    initialUrl: String? = null,
    onInitialUrlConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
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
    var address by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf("") }
    var pageProgress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var navigationManuallyStopped by remember { mutableStateOf(false) }
    var blankNavigationPending by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var activeInspectionId by remember { mutableStateOf<String?>(null) }
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

    fun invalidateInspectionForNavigation() {
        // Resolver work may continue in the background, but no result from a previous
        // document may replace the current page's result card.
        activeInspectionId = null
        inspecting = false
        candidates = emptyList()
        showResults = false
        selectedAudioCandidateId = null
        selectedAudioFormat = null
        resultTitle = null
        resultThumbnail = null
        resultSourceUrl = null
        inspectMessage = null
    }

    fun handleDirectBrowserDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val sourcePageUrl = webView?.url ?: currentUrl
        val mediaLink = BrowserDirectMediaDownload.mediaLink(
            url = url,
            mimeType = mimeType,
            contentDisposition = contentDisposition,
            analyzer = analyzer,
        )
        if (mediaLink == null) {
            inspectMessage = "رصد المتصفح رابط تنزيل، لكن لم يمكن تأكيد أنه ملف صوت أو فيديو مدعوم."
            recordBrowserEvent(
                "DIRECT_DOWNLOAD_IGNORED",
                "تم تجاهل رابط تنزيل لا يملك نوع وسائط مدعومًا",
                "direct_download",
                url,
                severity = "WARNING",
                details = mapOf(
                    "reason" to "unsupported_or_unknown_media_type",
                    "mime_type" to BrowserDirectMediaDownload.safeMimeType(mimeType),
                ),
            )
            return
        }
        if (actionInProgress) {
            inspectMessage = "أكمل إجراء التنزيل الجاري قبل تجهيز رابط آخر."
            recordBrowserEvent(
                "DIRECT_DOWNLOAD_DEFERRED",
                "أُجّل تجهيز رابط مباشر أثناء تنفيذ إجراء تنزيل",
                "direct_download",
                url,
                severity = "WARNING",
                details = mapOf("reason" to "download_action_in_progress"),
            )
            return
        }

        val operationId = "browser-direct-" + UUID.randomUUID().toString()
        activeInspectionId = operationId
        inspecting = true
        candidates = emptyList()
        selectedAudioCandidateId = null
        selectedAudioFormat = null
        showResults = false
        inspectMessage = "تم اكتشاف رابط وسائط مباشر؛ يجري تجهيز الخيار للتحقق قبل التنزيل."
        recordBrowserEvent(
            "DIRECT_DOWNLOAD_DETECTED",
            "اكتشف WebView رابط تنزيل مباشرًا لصوت أو فيديو",
            "direct_download",
            url,
            severity = "INFO",
            details = mapOf(
                "operation_id" to operationId,
                "mime_type" to BrowserDirectMediaDownload.safeMimeType(mimeType),
                "content_length_known" to (contentLength > 0L).toString(),
            ),
        )

        scope.launch {
            try {
                val result = resolver.resolve(mediaLink, operationId)
                if (activeInspectionId != operationId) {
                    recordBrowserEvent(
                        "DIRECT_DOWNLOAD_RESULT_DISCARDED",
                        "أُهملت نتيجة رابط مباشر بعد تغيّر الطلب النشط",
                        "direct_download",
                        url,
                        severity = "INFO",
                        details = mapOf(
                            "operation_id" to operationId,
                            "reason" to "superseded_by_navigation_or_new_request",
                        ),
                    )
                    return@launch
                }

                when (result) {
                    is ResolverResult.Success -> {
                        val transientHeaders = BrowserDirectMediaDownload.requestHeaders(
                            referer = sourcePageUrl,
                            userAgent = userAgent,
                        )
                        val pageCookieHost = BrowserDirectMediaDownload.sessionCookieHost(sourcePageUrl)
                        val container = BrowserDirectMediaDownload.containerFor(url, mimeType, contentDisposition)
                        val observedSize = contentLength.takeIf { it > 0L }
                        val directCandidates = result.candidates.map { candidate ->
                            val sourceExtension = runCatching {
                                URI(url).path.orEmpty().substringAfterLast('.', "").lowercase()
                            }.getOrDefault("")
                            val streamingManifest = candidate.streamingManifest ||
                                BrowserDirectMediaDownload.isStreamingManifest(mimeType) ||
                                sourceExtension in setOf("m3u8", "mpd")
                            candidate.copy(
                                requestHeaders = candidate.requestHeaders + transientHeaders,
                                sessionCookieHost = pageCookieHost,
                                sourceContext = MediaSourceContext.BROWSER_OBSERVED,
                                streamingManifest = streamingManifest,
                                format = candidate.format.copy(
                                    container = container ?: candidate.format.container,
                                    fileSizeBytes = if (streamingManifest) null else observedSize ?: candidate.format.fileSizeBytes,
                                ),
                            )
                        }
                        candidates = directCandidates
                        resultTitle = BrowserDirectMediaDownload.displayName(url, contentDisposition)
                            ?: result.title
                            ?: currentPageTitle.takeIf { it.isNotBlank() }
                        resultThumbnail = result.thumbnailUrl
                        resultSourceUrl = sourcePageUrl
                        selectedAudioCandidateId = null
                        selectedAudioFormat = null
                        showResults = directCandidates.isNotEmpty()
                        val videoCount = BrowserMediaOptionDeduplicator.videoOptions(directCandidates).size
                        val audioCount = BrowserMediaOptionDeduplicator.audioSources(directCandidates).size
                        inspectMessage = if (directCandidates.isEmpty()) {
                            "رُصد الرابط، لكن لم ينتج محرك التنزيل خيارًا صالحًا."
                        } else {
                            "تم تجهيز رابط مباشر: $videoCount خيار فيديو و$audioCount مصدر صوت. سيُتحقق من المصدر عند اختيار التنزيل."
                        }
                        recordBrowserEvent(
                            "DIRECT_DOWNLOAD_OPTIONS_READY",
                            if (directCandidates.isEmpty()) "لم ينتج الرابط المباشر خيارات قابلة للعرض" else "أصبحت خيارات الرابط المباشر جاهزة للعرض",
                            "direct_download",
                            url,
                            code = "candidates_${directCandidates.size}",
                            severity = if (directCandidates.isEmpty()) "WARNING" else "SUCCESS",
                            details = mapOf(
                                "operation_id" to operationId,
                                "candidate_count" to directCandidates.size.toString(),
                                "video_option_count" to videoCount.toString(),
                                "audio_source_count" to audioCount.toString(),
                                "mime_type" to BrowserDirectMediaDownload.safeMimeType(mimeType),
                                "referer_header_attached" to transientHeaders.keys.any { it.equals("Referer", ignoreCase = true) }.toString(),
                                "user_agent_header_attached" to transientHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }.toString(),
                            ),
                        )
                    }
                    is ResolverResult.Failure -> {
                        inspectMessage = result.message ?: "تعذر تجهيز رابط التنزيل المباشر."
                        recordBrowserEvent(
                            "DIRECT_DOWNLOAD_RESOLUTION_FAILED",
                            "تعذر تجهيز رابط التنزيل المباشر",
                            "direct_download",
                            url,
                            code = result.code.name,
                            severity = "ERROR",
                            details = mapOf(
                                "operation_id" to operationId,
                                "failure_code" to result.code.name,
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activeInspectionId == operationId) {
                    inspectMessage = "تعذر تجهيز رابط التنزيل المباشر."
                    recordBrowserEvent(
                        "DIRECT_DOWNLOAD_EXCEPTION",
                        "حدث استثناء أثناء تجهيز رابط تنزيل مباشر",
                        "direct_download",
                        url,
                        code = error.javaClass.simpleName,
                        severity = "ERROR",
                        details = mapOf(
                            "operation_id" to operationId,
                            "exception_type" to error.javaClass.simpleName,
                        ),
                    )
                }
            } finally {
                if (activeInspectionId == operationId) {
                    activeInspectionId = null
                    inspecting = false
                }
            }
        }
    }

    fun loadAddress(source: String = "address_bar") {
        if (address.isBlank()) {
            inspectMessage = "أدخل عنوان موقع أو عبارة بحث أولًا."
            return
        }
        val normalized = normalizeBrowserUrl(address)
        if (normalized == null) {
            inspectMessage = "أدخل عنوان موقع صالحًا يبدأ بـ https:// أو http://."
            recordError("INVALID_ADDRESS", "عنوان الموقع غير صالح", "navigation", address)
            return
        }
        blankNavigationPending = false
        navigationManuallyStopped = false
        keyboardController?.hide()
        invalidateInspectionForNavigation()
        nextNavigationSource = source
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

    fun openBlankPage() {
        invalidateInspectionForNavigation()
        val activeWebView = webView
        blankNavigationPending = activeWebView != null
        navigationManuallyStopped = false
        currentUrl = ""
        address = ""
        pageProgress = 0
        isLoading = false
        canGoBack = false
        canGoForward = false
        pageLoadError = null
        inspectMessage = null
        currentPageTitle = ""
        pageRenderStatus = "NOT_CHECKED"
        currentNavigationId = UUID.randomUUID().toString()
        navigationStartedAtMs = 0L
        nextNavigationSource = "blank_page"
        lastPageFinishedEventKey = ""
        lastRenderSnapshotFingerprint = ""
        lastRenderRecheckScheduledNavigationId = ""
        lastVisualStateReadyNavigationId = ""
        recordBrowserEvent(
            "BLANK_PAGE_OPENED",
            "تم فتح صفحة فارغة دون تحميل موقع تلقائيًا",
            "navigation",
            null,
            severity = "INFO",
            details = mapOf("navigation_source" to "blank_page"),
            pageTitle = null,
        )
        activeWebView?.let { view ->
            runCatching { view.stopLoading() }
            view.loadUrl("about:blank")
        }
    }

    LaunchedEffect(initialUrl) {
        val requestedUrl = initialUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: return@LaunchedEffect
        if (!isTrustedYouTubePlaybackUrl(requestedUrl)) {
            onInitialUrlConsumed()
            recordError(
                "BLOCKED_INITIAL_URL",
                "تم رفض عنوان مشاهدة غير موثوق",
                "navigation",
                null,
            )
            return@LaunchedEffect
        }
        address = requestedUrl
        loadAddress(source = "youtube_search")
        onInitialUrlConsumed()
    }

    BackHandler {
        if (showResults) {
            showResults = false
        } else if (canGoBack) {
            invalidateInspectionForNavigation()
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
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "العودة إلى التطبيق",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { openBlankPage() }) {
                        Icon(Icons.Rounded.Add, contentDescription = "صفحة فارغة")
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    inspectMessage?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                message.startsWith("أُضيف") ||
                                    message.startsWith("تم العثور") ||
                                    message.startsWith("هذا الملف موجود بالفعل") ->
                                    MaterialTheme.colorScheme.primary
                                message.contains("تعذر") ||
                                    message.contains("خطأ") ||
                                    message.contains("رفض") ||
                                    message.contains("غير صالح") ||
                                    message.startsWith("لم يُعثر") ||
                                    message.startsWith("لم يتم العثور") ->
                                    MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                        )
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
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
                                activeInspectionId = operationId
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
                                        val result = resolver.resolve(link, operationId)
                                        if (activeInspectionId != operationId) {
                                            recordBrowserEvent(
                                                "OPTIONS_INSPECTION_RESULT_DISCARDED",
                                                "أُهملت نتيجة فحص قديم بعد التنقل أو بدء طلب أحدث",
                                                "inspection",
                                                target,
                                                severity = "INFO",
                                                details = mapOf(
                                                    "operation_id" to operationId,
                                                    "reason" to "superseded_by_navigation_or_new_request",
                                                ),
                                            )
                                            return@launch
                                        }
                                        when (result) {
                                            is ResolverResult.Success -> {
                                                candidates = result.candidates
                                                resultTitle = result.title
                                                resultThumbnail = result.thumbnailUrl
                                                resultSourceUrl = target
                                                showResults = result.candidates.isNotEmpty()
                                                val videoOptionCount = BrowserMediaOptionDeduplicator
                                                    .videoOptions(result.candidates).size
                                                val audioSourceCount = BrowserMediaOptionDeduplicator
                                                    .audioSources(result.candidates).size
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
                                                    "تم العثور على ${videoOptionCount} خيار فيديو و${audioSourceCount} مصدر صوت."
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
                                        if (activeInspectionId != operationId) return@launch
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
                                        if (activeInspectionId == operationId) {
                                            activeInspectionId = null
                                            inspecting = false
                                        }
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
                Icon(
                    Icons.Rounded.Shield,
                    contentDescription = when {
                        currentUrl.startsWith("https://", ignoreCase = true) -> "اتصال HTTPS"
                        currentUrl.startsWith("http://", ignoreCase = true) -> "اتصال HTTP غير مشفّر"
                        else -> "لا يوجد موقع مفتوح"
                    },
                    tint = when {
                        currentUrl.startsWith("https://", ignoreCase = true) -> MaterialTheme.colorScheme.primary
                        currentUrl.startsWith("http://", ignoreCase = true) -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f).testTag("browser-address-field"),
                    singleLine = true,
                    label = { Text("عنوان الموقع") },
                    placeholder = { Text("أدخل رابطًا أو عبارة بحث") },
                    trailingIcon = {
                        if (address.isNotEmpty()) {
                            IconButton(
                                modifier = Modifier.testTag("browser-address-clear"),
                                onClick = {
                                    address = ""
                                    inspectMessage = null
                                },
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "مسح نص العنوان")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { loadAddress() }),
                )
                IconButton(
                    enabled = address.isNotBlank(),
                    onClick = { loadAddress() },
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = "فتح العنوان")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(enabled = canGoBack, onClick = { invalidateInspectionForNavigation(); nextNavigationSource = "back_button"; webView?.goBack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "السابق")
                }
                IconButton(enabled = canGoForward, onClick = { invalidateInspectionForNavigation(); nextNavigationSource = "forward_button"; webView?.goForward() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "التالي")
                }
                IconButton(
                    enabled = currentUrl.isNotBlank() && webView != null,
                    onClick = {
                        invalidateInspectionForNavigation()
                        if (isLoading) {
                            val stoppedUrl = webView?.url ?: currentUrl
                            nextNavigationSource = "stop_button"
                            navigationManuallyStopped = true
                            runCatching { webView?.stopLoading() }
                            isLoading = false
                            pageLoadError = null
                            pageProgress = (webView?.progress ?: pageProgress).coerceIn(0, 100)
                            canGoBack = webView?.canGoBack() ?: canGoBack
                            canGoForward = webView?.canGoForward() ?: canGoForward
                            recordBrowserEvent(
                                "NAVIGATION_STOPPED",
                                "أوقف المستخدم تحميل الصفحة يدويًا",
                                "navigation",
                                stoppedUrl,
                                severity = "INFO",
                                details = mapOf(
                                    "progress_percent" to pageProgress.toString(),
                                    "navigation_source" to "stop_button",
                                ),
                            )
                        } else {
                            navigationManuallyStopped = false
                            nextNavigationSource = "reload_button"
                            pageLoadError = null
                            inspectMessage = null
                            isLoading = true
                            pageProgress = 0
                            webView?.reload()
                        }
                    },
                ) {
                    if (isLoading) {
                        Icon(Icons.Rounded.Close, contentDescription = "إيقاف التحميل")
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "تحديث")
                    }
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
                        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                            handleDirectBrowserDownload(
                                url = url,
                                userAgent = userAgent,
                                contentDisposition = contentDisposition,
                                mimeType = mimeType,
                                contentLength = contentLength,
                            )
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme?.lowercase()
                                // about:blank is an intentional internal navigation used by the
                                // blank-page action; it is not an external or unsafe URL scheme.
                                if (
                                    request.url.toString() == "about:blank" &&
                                    (blankNavigationPending || currentUrl.isBlank())
                                ) {
                                    return false
                                }
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
                                // Keep callbacks from a cancelled page from repopulating the address
                                // after the user explicitly requested the blank browser surface.
                                if (blankNavigationPending && url != "about:blank") return
                                if (url == "about:blank") {
                                    if (currentUrl.isNotBlank() && !blankNavigationPending) return
                                    blankNavigationPending = false
                                    currentUrl = ""
                                    address = ""
                                    pageProgress = 0
                                    isLoading = false
                                    canGoBack = false
                                    canGoForward = false
                                    pageLoadError = null
                                    inspectMessage = null
                                    currentPageTitle = ""
                                    pageRenderStatus = "NOT_CHECKED"
                                    nextNavigationSource = "browser_entry"
                                    view.clearHistory()
                                    return
                                }
                                blankNavigationPending = false
                                navigationManuallyStopped = false
                                invalidateInspectionForNavigation()
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

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                if (blankNavigationPending) return
                                if (url == "about:blank") {
                                    if (currentUrl.isBlank()) {
                                        view.clearHistory()
                                        canGoBack = false
                                        canGoForward = false
                                    }
                                    return
                                }
                                if (url != currentUrl) {
                                    invalidateInspectionForNavigation()
                                    currentUrl = url
                                    address = url
                                    canGoBack = view.canGoBack()
                                    canGoForward = view.canGoForward()
                                    recordBrowserEvent(
                                        "NAVIGATION_HISTORY_UPDATED",
                                        "تغير عنوان الصفحة عبر سجل المتصفح",
                                        "navigation",
                                        url,
                                        severity = "INFO",
                                        details = mapOf(
                                            "history_reload" to isReload.toString(),
                                            "navigation_source" to "webview_history_api",
                                        ),
                                    )
                                }
                            }

                            override fun onPageCommitVisible(view: WebView, url: String) {
                                if (!BrowserNavigationCallbackGuard.isCurrent(
                                        callbackNavigationId = currentNavigationId,
                                        activeNavigationId = currentNavigationId,
                                        callbackUrl = url,
                                        activeWebViewUrl = view.url,
                                        activeBrowserUrl = currentUrl,
                                    )
                                ) {
                                    return
                                }
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
                                if (url == "about:blank") {
                                    if (currentUrl.isBlank()) {
                                        view.clearHistory()
                                        address = ""
                                        currentUrl = ""
                                        isLoading = false
                                        pageProgress = 0
                                        canGoBack = false
                                        canGoForward = false
                                    }
                                    return
                                }
                                if (blankNavigationPending) return
                                // WebView callbacks may arrive after a later navigation has begun.
                                // Validate the callback against the active view before changing UI state.
                                val callbackNavigationId = currentNavigationId
                                val callbackWebViewUrl = view.url
                                if (!BrowserNavigationCallbackGuard.isCurrent(
                                        callbackNavigationId = callbackNavigationId,
                                        activeNavigationId = currentNavigationId,
                                        callbackUrl = url,
                                        activeWebViewUrl = callbackWebViewUrl,
                                        activeBrowserUrl = currentUrl,
                                    )
                                ) {
                                    val activeUrl = currentUrl
                                    val observedWebViewUrl = callbackWebViewUrl.orEmpty()
                                    recordBrowserEvent(
                                        "STALE_PAGE_FINISHED_CALLBACK_IGNORED",
                                        "أُهملت callback تحميل لعنوان لم يعد هو العنوان النشط",
                                        "navigation",
                                        activeUrl,
                                        severity = "INFO",
                                        details = mapOf(
                                            "callback_host" to safeHost(url),
                                            "active_host" to safeHost(activeUrl),
                                            "webview_host" to safeHost(observedWebViewUrl),
                                            "reason" to "webview_or_browser_state_url_mismatch",
                                        ),
                                    )
                                    return
                                }
                                val pageNavigationId = callbackNavigationId
                                val pageNavigationStartedAtMs = navigationStartedAtMs
                                currentUrl = url
                                address = url
                                currentPageTitle = view.title.orEmpty().take(160)
                                pageProgress = 100
                                isLoading = false
                                navigationManuallyStopped = false
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                                val pageFinishedEventKey = "$pageNavigationId|$url"
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
                                            "elapsed_ms" to (System.currentTimeMillis() - pageNavigationStartedAtMs).coerceAtLeast(0).toString(),
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
                                        const hitTestBlockers = new Map();
                                        const rememberHitTestTarget = target => {
                                          if(!target || !target.tagName) return;
                                          const style=getComputedStyle(target);
                                          const r=target.getBoundingClientRect();
                                          const safeRole=String(target.getAttribute('role')||'').replace(/[^a-zA-Z0-9_-]/g,'').slice(0,16)||'-';
                                          const safeModal=String(target.getAttribute('aria-modal')||'').replace(/[^a-zA-Z0-9_-]/g,'').slice(0,8)||'-';
                                          const descriptor=target.tagName.toLowerCase()+
                                            '[pos='+style.position+
                                            ',z='+style.zIndex+
                                            ',pe='+style.pointerEvents+
                                            ',op='+Number(style.opacity||1).toFixed(2)+
                                            ',size='+Math.round(r.width)+'x'+Math.round(r.height)+
                                            ',role='+safeRole+
                                            ',modal='+safeModal+']';
                                          hitTestBlockers.set(descriptor,true);
                                        };
                                        const hitTestBlockerSummary = () => [...hitTestBlockers.keys()].slice(0,3).join(';').slice(0,220);
                                        const unobscuredAt = (element,rect) => {
                                          const left=Math.max(0,rect.left), right=Math.min(viewportWidth,rect.right);
                                          const topEdge=Math.max(0,rect.top), bottomEdge=Math.min(viewportHeight,rect.bottom);
                                          if(right<=left||bottomEdge<=topEdge) return false;
                                          const width=right-left, height=bottomEdge-topEdge;
                                          const points=[
                                            [left+width*0.5,topEdge+height*0.5],
                                            [left+width*0.2,topEdge+height*0.5],
                                            [left+width*0.8,topEdge+height*0.5],
                                            [left+width*0.5,topEdge+height*0.2],
                                            [left+width*0.5,topEdge+height*0.8]
                                          ];
                                          const rejectedTargets=[];
                                          for(const [x,y] of points) {
                                            const top=d.elementFromPoint(x,y);
                                            if(!top) continue;
                                            if(top===element || element.contains(top)) return true;
                                            if(top!==b && top!==e && top.contains(element)) return true;
                                            rejectedTargets.push(top);
                                          }
                                          rejectedTargets.forEach(rememberHitTestTarget);
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
                                        const visibleTextHostElements=new Set();
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
                                            visibleTextHostElements.add(parent);
                                            if(hasReadableContrast(parent)) readableVisibleTextChars=Math.min(10000,readableVisibleTextChars+value.length);
                                          }
                                        }
                                        const visibleMediaElements=[...d.querySelectorAll('video,canvas,iframe')].filter(i=>{
                                          const r=i.getBoundingClientRect();
                                          if(!hasVisibleStyle(i,r)||r.width<32||r.height<18||!unobscuredAt(i,r)) return false;
                                          if(i.tagName==='VIDEO') return i.videoWidth>0&&i.videoHeight>0&&i.readyState>=2;
                                          if(i.tagName==='CANVAS') return i.width>0&&i.height>0;
                                          return true;
                                        });
                                        const visibleMediaElementCount=visibleMediaElements.length;
                                        const visibleSemanticContainers=[...d.querySelectorAll('main,article,[role="main"]')].filter(i=>{
                                          const r=i.getBoundingClientRect();
                                          if(!hasVisibleStyle(i,r)) return false;
                                          return [...visibleTextHostElements].some(host=>i===host||i.contains(host));
                                        });
                                        const visibleContentElements=new Set([
                                          ...visibleTextHostElements,
                                          ...visibleSemanticContainers,
                                          ...visibleMediaElements
                                        ]);
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
                                          hitTestBlockerSummary:hitTestBlockerSummary(),
                                          readableVisibleTextChars:readableVisibleTextChars,
                                          visibleTextHostElementCount:visibleTextHostElements.size,
                                          visibleSemanticContainerCount:visibleSemanticContainers.length,
                                          visibleContentElementCount:visibleContentElements.size,
                                          visibleContentElementScope:'unique_visible_text_hosts_semantic_containers_and_renderable_media',
                                          backgroundColor:b?getComputedStyle(b).backgroundColor:'unknown',
                                          challengeDetected:challenge
                                        });
                                      } catch(e) {
                                        return JSON.stringify({diagnosticError:'dom_snapshot_failed'});
                                      }
                                    })();
                                    """.trimIndent(),
                                ) { raw ->
                                    if (!BrowserNavigationCallbackGuard.isCurrent(
                                            callbackNavigationId = pageNavigationId,
                                            activeNavigationId = currentNavigationId,
                                            callbackUrl = url,
                                            activeWebViewUrl = view.url,
                                        activeBrowserUrl = currentUrl,
                                        )
                                    ) {
                                        val activeUrl = currentUrl
                                        val observedWebViewUrl = view.url.orEmpty()
                                        recordBrowserEvent(
                                            "STALE_PAGE_RENDER_CALLBACK_IGNORED",
                                            "أُهملت نتيجة فحص عرض بعد تغيّر الصفحة النشطة",
                                            "render",
                                            activeUrl,
                                            severity = "INFO",
                                            details = mapOf(
                                                "callback_host" to safeHost(url),
                                                "active_host" to safeHost(activeUrl),
                                                "webview_host" to safeHost(observedWebViewUrl),
                                                "callback_navigation_id" to pageNavigationId,
                                                "active_navigation_id" to currentNavigationId,
                                                "reason" to "navigation_superseded_or_url_changed",
                                            ),
                                        )
                                        return@evaluateJavascript
                                    }
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
                                                "elapsed_ms" to (System.currentTimeMillis() - pageNavigationStartedAtMs).coerceAtLeast(0).toString(),
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
                                    val hitTestBlockerSummary = snapshot.optString("hitTestBlockerSummary", "").take(220)
                                    val readableVisibleTextChars = snapshot.optInt("readableVisibleTextChars", 0).coerceAtLeast(0)
                                    val visibleMediaElementCount = snapshot.optInt("visibleMediaElementCount", 0).coerceAtLeast(0)
                                    val visibleTextHostElementCount = snapshot.optInt("visibleTextHostElementCount", 0).coerceAtLeast(0)
                                    val visibleSemanticContainerCount = snapshot.optInt("visibleSemanticContainerCount", 0).coerceAtLeast(0)
                                    val visibleContentElementCount = snapshot.optInt("visibleContentElementCount", 0).coerceAtLeast(0)
                                    val visibleContentElementScope = snapshot.optString(
                                        "visibleContentElementScope",
                                        "unique_visible_text_hosts_semantic_containers_and_renderable_media",
                                    )
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
                                        hasHitTestRejectedContent = hasHitTestRejectedContent,
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
                                    val renderSnapshotFingerprint = "$pageNavigationId|$url|${snapshot}"
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
                                                "hit_test_blocker_summary" to hitTestBlockerSummary,
                                                "readable_visible_text_chars" to readableVisibleTextChars.toString(),
                                                "visible_media_element_count" to visibleMediaElementCount.toString(),
                                                "visible_text_host_element_count" to visibleTextHostElementCount.toString(),
                                                "visible_semantic_container_count" to visibleSemanticContainerCount.toString(),
                                                "visible_content_element_count" to visibleContentElementCount.toString(),
                                                "visible_content_element_scope" to visibleContentElementScope,
                                                "has_visible_content_signals" to hasVisibleContentSignals.toString(),
                                                "visible_content_signal_sources" to listOfNotNull(
                                                    "visible_text".takeIf { visibleTextChars >= 12 },
                                                    "visible_images".takeIf { visibleImageCount > 0 },
                                                    "visible_media".takeIf { visibleMediaElementCount > 0 },
                                                ).joinToString(",").ifBlank { "none" },
                                                "content_visibility_basis" to "viewport_intersection_visible_style_contrast_and_hit_test",
                                                "iframe_count" to iframeCount.toString(),
                                                "background_color" to snapshot.optString("backgroundColor", "unknown"),
                                                "challenge_detected" to challengeDetected.toString(),
                                                "elapsed_ms" to (System.currentTimeMillis() - pageNavigationStartedAtMs).coerceAtLeast(0).toString(),
                                                "http_resource_errors" to resourceHttpErrorCount.toString(),
                                                "console_issue_events_recorded" to consoleErrorCount.toString(),
                                                "console_duplicate_issues_suppressed" to consoleDuplicateIssueCount.toString(),
                                            ),
                                            pageTitle = currentPageTitle.takeIf { it.isNotBlank() },
                                        )
                                    }
                                    val visualStateNavigationId = pageNavigationId
                                    val visualStateRequestId = System.currentTimeMillis().coerceAtLeast(1L)
                                    runCatching {
                                        view.postVisualStateCallback(
                                            visualStateRequestId,
                                            object : WebView.VisualStateCallback() {
                                                override fun onComplete(requestId: Long) {
                                                    if (BrowserNavigationCallbackGuard.isCurrent(
                                                            callbackNavigationId = visualStateNavigationId,
                                                            activeNavigationId = currentNavigationId,
                                                            callbackUrl = url,
                                                            activeWebViewUrl = view.url,
                                                            activeBrowserUrl = currentUrl,
                                                        ) &&
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
                                                                "navigation_elapsed_ms" to (System.currentTimeMillis() - pageNavigationStartedAtMs).coerceAtLeast(0).toString(),
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
                                    val renderRecheckNavigationId = pageNavigationId
                                    if (lastRenderRecheckScheduledNavigationId != renderRecheckNavigationId) {
                                        lastRenderRecheckScheduledNavigationId = renderRecheckNavigationId
                                        view.postDelayed({
                                        if (view.isAttachedToWindow &&
                                            currentNavigationId == renderRecheckNavigationId &&
                                            view.url == url &&
                                            currentUrl == url &&
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
                                                    view.url == url &&
                                                    currentUrl == url
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
                                                    val recheckHitTestBlockerSummary = recheckJson.optString("hitTestBlockerSummary", "").take(220)
                                                    val recheckReadableText = recheckJson.optInt("readableVisibleTextChars", 0).coerceAtLeast(0)
                                                    val recheckVisibleMedia = recheckJson.optInt("visibleMediaElementCount", 0).coerceAtLeast(0)
                                                    val recheckVisibleTextHostElements = recheckJson.optInt("visibleTextHostElementCount", 0).coerceAtLeast(0)
                                                    val recheckVisibleSemanticContainers = recheckJson.optInt("visibleSemanticContainerCount", 0).coerceAtLeast(0)
                                                    val recheckVisibleContentElements = recheckJson.optInt("visibleContentElementCount", 0).coerceAtLeast(0)
                                                    val recheckVisibleContentElementScope = recheckJson.optString(
                                                        "visibleContentElementScope",
                                                        "unique_visible_text_hosts_semantic_containers_and_renderable_media",
                                                    )
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
                                                        hasHitTestRejectedContent = recheckHasHitTestRejectedContent,
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
                                                            "hit_test_blocker_summary" to recheckHitTestBlockerSummary,
                                                            "readable_visible_text_chars" to recheckReadableText.toString(),
                                                            "visible_media_element_count" to recheckVisibleMedia.toString(),
                                                            "visible_text_host_element_count" to recheckVisibleTextHostElements.toString(),
                                                            "visible_semantic_container_count" to recheckVisibleSemanticContainers.toString(),
                                                            "visible_content_element_count" to recheckVisibleContentElements.toString(),
                                                            "visible_content_element_scope" to recheckVisibleContentElementScope,
                                                            "content_visibility_basis" to "viewport_intersection_visible_style_contrast_and_hit_test",
                                                            "navigation_id" to renderRecheckNavigationId,
                                                            "console_issue_events_recorded" to consoleErrorCount.toString(),
                                                            "console_duplicate_issues_suppressed" to consoleDuplicateIssueCount.toString(),
                                                        ),
                                                        pageTitle = currentPageTitle.takeIf { it.isNotBlank() },
                                                    )
                                                } else if (view.isAttachedToWindow &&
                                                    currentNavigationId == renderRecheckNavigationId &&
                                                    view.url == url &&
                                                    currentUrl == url
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
                                    if (navigationManuallyStopped) {
                                        recordBrowserEvent(
                                            "STOPPED_NAVIGATION_ERROR_IGNORED",
                                            "أُهمل خطأ متأخر ناتج عن إيقاف التحميل يدويًا",
                                            "navigation",
                                            currentUrl,
                                            code = error.errorCode.toString(),
                                            severity = "INFO",
                                            details = mapOf("reason" to "manual_stop"),
                                        )
                                        return
                                    }
                                    val failedUrl = request.url.toString()
                                    if (failedUrl != currentUrl) {
                                        recordBrowserEvent(
                                            "STALE_MAIN_FRAME_ERROR_IGNORED",
                                            "أُهملت نتيجة خطأ تحميل لإطار لم يعد هو الصفحة النشطة",
                                            "navigation",
                                            currentUrl,
                                            code = error.errorCode.toString(),
                                            severity = "INFO",
                                            details = mapOf(
                                                "callback_host" to safeHost(failedUrl),
                                                "active_host" to safeHost(currentUrl),
                                                "reason" to "main_frame_url_mismatch",
                                            ),
                                        )
                                        return
                                    }
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
                                if (mainFrame && request.url.toString() != currentUrl) {
                                    recordBrowserEvent(
                                        "STALE_MAIN_FRAME_HTTP_ERROR_IGNORED",
                                        "أُهملت استجابة HTTP لإطار لم يعد هو الصفحة النشطة",
                                        "navigation",
                                        currentUrl,
                                        code = status.toString(),
                                        severity = "INFO",
                                        details = mapOf(
                                            "callback_host" to safeHost(request.url.toString()),
                                            "active_host" to safeHost(currentUrl),
                                            "http_status" to status.toString(),
                                            "reason" to "main_frame_url_mismatch",
                                        ),
                                    )
                                    return
                                }
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
                                fileChooserParams: WebChromeClient.FileChooserParams?,
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
                                            "selection_mode" to if (fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) "multiple" else "single",
                                            "accepted_mime_type_count" to acceptedTypes.size.toString(),
                                            "capture_requested" to fileChooserParams.isCaptureEnabled.toString(),
                                            "picker" to "android_document_picker",
                                        ),
                                    )
                                    if (fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
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
                                            "selection_mode" to if (fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) "multiple" else "single",
                                        ),
                                    )
                                    true
                                }
                            }

                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                val activeViewUrl = view.url
                                if (blankNavigationPending || navigationManuallyStopped || activeViewUrl == "about:blank") return
                                if (!BrowserNavigationCallbackGuard.isCurrent(
                                        callbackNavigationId = currentNavigationId,
                                        activeNavigationId = currentNavigationId,
                                        callbackUrl = activeViewUrl.orEmpty(),
                                        activeWebViewUrl = activeViewUrl,
                                        activeBrowserUrl = currentUrl,
                                    )
                                ) {
                                    return
                                }
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
                                val titlePageUrl = view.url
                                if (!BrowserNavigationCallbackGuard.isCurrent(
                                        callbackNavigationId = currentNavigationId,
                                        activeNavigationId = currentNavigationId,
                                        callbackUrl = titlePageUrl.orEmpty(),
                                        activeWebViewUrl = titlePageUrl,
                                        activeBrowserUrl = currentUrl,
                                    )
                                ) {
                                    val activeUrl = currentUrl
                                    recordBrowserEvent(
                                        "STALE_PAGE_TITLE_CALLBACK_IGNORED",
                                        "أُهملت نتيجة عنوان صفحة بعد تغيّر عنوان المتصفح النشط",
                                        "render",
                                        activeUrl,
                                        severity = "INFO",
                                        details = mapOf(
                                            "callback_host" to safeHost(titlePageUrl.orEmpty()),
                                            "active_host" to safeHost(activeUrl),
                                            "reason" to "webview_or_browser_state_url_mismatch",
                                        ),
                                    )
                                    return
                                }
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
                        AHCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("تعذر تحميل الصفحة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(
                                    onClick = { loadAddress() },
                                    enabled = !isLoading,
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                ) {
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

    val videoCandidates = remember(candidates) {
        BrowserMediaOptionDeduplicator.videoOptions(candidates)
    }
    val audioSources = remember(candidates) {
        BrowserMediaOptionDeduplicator.audioSources(candidates)
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
                        maxLines = 2,
                    )
                    Text(
                        "${videoCandidates.size} خيار فيديو • ${audioSources.size} مصدر صوت • لا يتم اختيار أي مصدر تلقائيًا",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { Text("الفيديو", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
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
                                    label = { Text(BrowserMediaOptionDeduplicator.audioSourceLabel(candidate)) },
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
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
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
    // Capture the cross-module property in a local stable value for Kotlin smart-cast safety.
    val fileSizeBytes = candidate.format.fileSizeBytes
    AHCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    BrowserMediaOptionDeduplicator.videoOptionTitle(candidate),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildList {
                        candidate.format.videoCodec?.takeIf { it.isNotBlank() }?.let { add("Video ${it.uppercase()}") }
                        candidate.format.audioCodec?.takeIf { it.isNotBlank() }?.let { add("Audio ${it.uppercase()}") }
                        candidate.format.fps?.let { add("${it} fps") }
                        candidate.format.bitrateKbps?.takeIf { it > 0 }?.let { add("${it} kbps") }
                        add(
                            when {
                                candidate.format.hasAudio -> "صوت مضمّن"
                                !candidate.companionAudioSourceUrl.isNullOrBlank() -> "صوت منفصل"
                                else -> "مسار الصوت غير مؤكد"
                            },
                        )
                        when {
                            candidate.streamingManifest -> add("حجم الفيديو النهائي يحدده البث أثناء التنزيل")
                            fileSizeBytes != null && fileSizeBytes > 0L -> add(formatBytes(fileSizeBytes))
                            else -> add("الحجم غير معروف؛ سيُحدَّد عند التحقق")
                        }
                    }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                enabled = !busy,
                onClick = onDownload,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text("تنزيل") }
        }
    }
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
        const hitTestBlockers = new Map();
    const rememberHitTestTarget = target => {
      if(!target || !target.tagName) return;
      const style=getComputedStyle(target);
      const rect=target.getBoundingClientRect();
      const safeRole=String(target.getAttribute('role')||'').replace(/[^a-zA-Z0-9_-]/g,'').slice(0,16)||'-';
      const safeModal=String(target.getAttribute('aria-modal')||'').replace(/[^a-zA-Z0-9_-]/g,'').slice(0,8)||'-';
      const descriptor=target.tagName.toLowerCase()+'[pos='+style.position+',z='+style.zIndex+',pe='+style.pointerEvents+',op='+Number(style.opacity||1).toFixed(2)+',size='+Math.round(rect.width)+'x'+Math.round(rect.height)+',role='+safeRole+',modal='+safeModal+']';
      hitTestBlockers.set(descriptor,true);
    };
    const hitTestBlockerSummary = () => [...hitTestBlockers.keys()].slice(0,3).join(';').slice(0,220);
    const unobscured=(el,r)=>{
      const left=Math.max(0,r.left),right=Math.min(vw,r.right),topEdge=Math.max(0,r.top),bottomEdge=Math.min(vh,r.bottom);
      if(right<=left||bottomEdge<=topEdge) return false;
      const width=right-left,height=bottomEdge-topEdge;
      const points=[
        [left+width*0.5,topEdge+height*0.5],
        [left+width*0.2,topEdge+height*0.5],
        [left+width*0.8,topEdge+height*0.5],
        [left+width*0.5,topEdge+height*0.2],
        [left+width*0.5,topEdge+height*0.8]
      ];
      const rejectedTargets=[];
      for(const [x,y] of points) {
        const top=d.elementFromPoint(x,y);
        if(!top) continue;
        if(top===el || el.contains(top)) return true;
        if(top!==b && top!==e && top.contains(el)) return true;
        rejectedTargets.push(top);
      }
      rejectedTargets.forEach(rememberHitTestTarget);
      return false;
    };
    let imageCount=0,imageArea=0,visibleText=0,readableText=0,nodes=0;
    const visibleTextHostElements=new Set();
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
        visibleTextHostElements.add(el);
        if(readable(el)) readableText=Math.min(10000,readableText+value.length);
      }
    }
    const visibleMediaElements=[...d.querySelectorAll('video,canvas,iframe')].filter(el=>{
      const r=el.getBoundingClientRect();
      if(!visible(el,r)||r.width<32||r.height<18||!unobscured(el,r)) return false;
      if(el.tagName==='VIDEO') return el.videoWidth>0&&el.videoHeight>0&&el.readyState>=2;
      if(el.tagName==='CANVAS') return el.width>0&&el.height>0;
      return true;
    });
    const visibleMedia=visibleMediaElements.length;
    const visibleSemanticContainers=[...d.querySelectorAll('main,article,[role="main"]')].filter(el=>{
      const r=el.getBoundingClientRect();
      if(!visible(el,r)) return false;
      return [...visibleTextHostElements].some(host=>el===host||el.contains(host));
    });
    const visibleContentElements=new Set([
      ...visibleTextHostElements,
      ...visibleSemanticContainers,
      ...visibleMediaElements
    ]);
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
      hitTestBlockerSummary:hitTestBlockerSummary(),
      visibleTextChars:visibleText,readableVisibleTextChars:readableText,
      visibleMediaElementCount:visibleMedia,
      visibleTextHostElementCount:visibleTextHostElements.size,
      visibleSemanticContainerCount:visibleSemanticContainers.length,
      visibleContentElementCount:visibleContentElements.size,
      visibleContentElementScope:'unique_visible_text_hosts_semantic_containers_and_renderable_media',
      challengeDetected:challenge
    });
  } catch(e) { return JSON.stringify({diagnosticError:'recheck_failed'}); }
})()
""".trimIndent()

