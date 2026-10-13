package com.ahdownload.app.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
    var pageTitle by remember { mutableStateOf("") }
    var pageProgress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var resultTitle by remember { mutableStateOf<String?>(null) }
    var resultThumbnail by remember { mutableStateOf<String?>(null) }
    var resultSourceUrl by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<MediaCandidate>>(emptyList()) }
    var inspectMessage by remember { mutableStateOf<String?>(null) }
    var showResults by remember { mutableStateOf(false) }
    var selectedAudioCandidateId by remember { mutableStateOf<String?>(null) }
    var selectedAudioFormat by remember { mutableStateOf<AudioOutputFormat?>(null) }
    var actionInProgress by remember { mutableStateOf(false) }

    fun recordError(type: String, message: String, stage: String, url: String?, code: String? = null) {
        errorStore.record(type, message, stage, url, code)
        logger.log(
            DiagnosticLevel.WARNING,
            "BROWSER_$type",
            message,
            "browser.$stage",
            buildMap {
                put("host", safeHost(url))
                code?.let { put("code", it.take(40)) }
            },
            null,
        )
    }

    fun loadAddress() {
        val normalized = normalizeBrowserUrl(address)
        if (normalized == null) {
            inspectMessage = "أدخل عنوان موقع صالحًا يبدأ بـ https:// أو http://."
            recordError("INVALID_ADDRESS", "عنوان الموقع غير صالح", "navigation", address)
            return
        }
        address = normalized
        inspectMessage = null
        showResults = false
        webView?.loadUrl(normalized)
    }

    BackHandler {
        if (showResults) {
            showResults = false
        } else if (canGoBack) {
            webView?.goBack()
        } else {
            onBack()
        }
    }

    DisposableEffect(webView) {
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
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("AHDownload Browser")
                        Text(
                            "تصفح واسع مع فحص الوسائط",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { address = "https://www.google.com"; currentUrl = address; webView?.loadUrl(address) }) {
                        Icon(Icons.Rounded.Home, contentDescription = "الصفحة الرئيسية")
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            inspectMessage ?: if (inspecting) "جارٍ فحص مصادر الوسائط…" else "يمكن فحص الصفحة الحالية",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (inspectMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    Button(
                        enabled = !inspecting && currentUrl.isNotBlank(),
                        onClick = {
                            val target = webView?.url ?: currentUrl
                            val link = analyzer.analyze(target)
                            if (link == null) {
                                inspectMessage = "تعذر تحليل الرابط. تأكد من أن الصفحة تستخدم HTTPS."
                                recordError("ANALYSIS_REJECTED", "تعذر تحليل عنوان الصفحة", "inspection", target)
                            } else {
                                inspecting = true
                                inspectMessage = null
                                candidates = emptyList()
                                showResults = false
                                val operationId = "browser-" + UUID.randomUUID().toString()
                                scope.launch {
                                    try {
                                        when (val result = resolver.resolve(link, operationId)) {
                                            is ResolverResult.Success -> {
                                                candidates = result.candidates
                                                resultTitle = result.title
                                                resultThumbnail = result.thumbnailUrl
                                                resultSourceUrl = target
                                                showResults = result.candidates.isNotEmpty()
                                                inspectMessage = if (result.candidates.isEmpty()) {
                                                    "لم يُعثر على مصدر وسائط صالح."
                                                } else {
                                                    "تم العثور على ${result.candidates.size} خيارًا."
                                                }
                                                if (result.candidates.isEmpty()) {
                                                    recordError("NO_CANDIDATES", "لم يعثر المحرك على مصادر وسائط", "inspection", target)
                                                }
                                            }
                                            is ResolverResult.Failure -> {
                                                inspectMessage = result.message ?: "تعذر فحص هذه الصفحة."
                                                recordError(result.code.name, inspectMessage.orEmpty(), "inspection", target)
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        inspectMessage = "حدث خطأ أثناء فحص الصفحة."
                                        recordError("INSPECTION_EXCEPTION", "حدث استثناء أثناء فحص الصفحة", "inspection", target, error.javaClass.simpleName)
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
                        Text("فحص الخيارات")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
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
                IconButton(enabled = canGoBack, onClick = { webView?.goBack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "السابق")
                }
                IconButton(enabled = canGoForward, onClick = { webView?.goForward() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "التالي")
                }
                IconButton(onClick = { webView?.reload() }) {
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
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme?.lowercase()
                                if (scheme == "https" || scheme == "http") return false
                                recordError("BLOCKED_SCHEME", "تم منع مخطط عنوان غير مدعوم", "navigation", request.url.toString(), scheme)
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                currentUrl = url
                                address = url
                                isLoading = true
                                inspectMessage = null
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                currentUrl = url
                                address = url
                                pageTitle = view.title.orEmpty()
                                isLoading = false
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    isLoading = false
                                    recordError(
                                        "PAGE_LOAD_FAILED",
                                        "تعذر تحميل الصفحة داخل المتصفح",
                                        "navigation",
                                        request.url.toString(),
                                        error.errorCode.toString(),
                                    )
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: android.webkit.WebResourceResponse,
                            ) {
                                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                                    recordError(
                                        "HTTP_ERROR",
                                        "أعاد الموقع استجابة HTTP غير ناجحة",
                                        "navigation",
                                        request.url.toString(),
                                        errorResponse.statusCode.toString(),
                                    )
                                }
                            }

                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel()
                                isLoading = false
                                recordError("TLS_CERTIFICATE_ERROR", "تم إيقاف صفحة بسبب خطأ في شهادة TLS", "security", error.url, error.primaryError.toString())
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                pageProgress = newProgress
                                isLoading = newProgress < 100
                            }

                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title.orEmpty()
                            }
                        }
                        webView = this
                        if (currentUrl.isNotBlank()) loadUrl(currentUrl)
                    }
                },
                update = { },
            )
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
                                                if (outcome != DownloadEnqueueResult.QUEUED) {
                                                    recordError("DOWNLOAD_ENQUEUE_FAILED", inspectMessage.orEmpty(), "download", resultSourceUrl, outcome.name)
                                                }
                                            }
                                            is CandidateValidationResult.Invalid -> {
                                                inspectMessage = validation.failure.toBrowserMessage()
                                                recordError("MEDIA_VALIDATION_FAILED", inspectMessage.orEmpty(), "validation", candidate.sourceUrl)
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        inspectMessage = "تعذر بدء التنزيل."
                                        recordError("DOWNLOAD_EXCEPTION", "حدث خطأ أثناء تجهيز التنزيل", "download", resultSourceUrl, error.javaClass.simpleName)
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
                                                if (outcome != DownloadEnqueueResult.QUEUED) {
                                                    recordError("AUDIO_ENQUEUE_FAILED", inspectMessage.orEmpty(), "audio", resultSourceUrl, outcome.name)
                                                }
                                            }
                                            is CandidateValidationResult.Invalid -> {
                                                inspectMessage = validation.failure.toBrowserMessage()
                                                recordError("AUDIO_VALIDATION_FAILED", inspectMessage.orEmpty(), "validation", candidate.sourceUrl)
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        inspectMessage = "تعذر تجهيز استخراج الصوت."
                                        recordError("AUDIO_EXCEPTION", "حدث خطأ أثناء تجهيز استخراج الصوت", "audio", resultSourceUrl, error.javaClass.simpleName)
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
