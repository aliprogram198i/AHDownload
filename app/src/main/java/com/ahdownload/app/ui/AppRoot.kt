package com.ahdownload.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.ahdownload.app.data.DirectUrlResolver
import com.ahdownload.app.data.PlatformResolverClient
import com.ahdownload.app.data.ResolvedFormat
import com.ahdownload.app.data.DownloadRepository
import com.ahdownload.app.domain.DownloadJob
import com.ahdownload.app.domain.DownloadStatus
import com.ahdownload.app.ui.theme.AHDownloadTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import com.ahdownload.app.BuildConfig

private const val PLATFORM_RESOLVER_BASE_URL = BuildConfig.PLATFORM_RESOLVER_BASE_URL

private data class LinkAnalysis(
    val url: String,
    val contentType: String?,
    val sizeBytes: Long?,
    val title: String,
    val platform: String? = null,
    val formats: List<ResolvedFormat> = emptyList()
) {
    val isVideo get() = contentType?.startsWith("video/") == true
    val isAudio get() = contentType?.startsWith("audio/") == true
    val isImage get() = contentType?.startsWith("image/") == true
    val isMedia get() = isVideo || isAudio || isImage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    AHDownloadTheme {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: "home"
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { if (route != "home") AppTopBar(route) { nav.navigate("settings") { launchSingleTop = true } } },
            bottomBar = {
                NavigationBar {
                    NavigationBarItemButton(nav, route, "home", "الرئيسية", Icons.Default.Home)
                    NavigationBarItemButton(nav, route, "downloads", "التنزيلات", Icons.Default.Download)
                    NavigationBarItemButton(nav, route, "library", "المكتبة", Icons.Default.VideoLibrary)
                    NavigationBarItemButton(nav, route, "studio", "الاستديو", Icons.Default.AutoAwesome)
                }
            }
        ) { padding ->
            NavHost(nav, "home", Modifier.padding(padding)) {
                composable("home") { HomeScreen { nav.navigate("downloads") } }
                composable("downloads") { DownloadsScreen() }
                composable("library") { LibraryScreen() }
                composable("studio") { StudioScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun NavigationBarItemButton(nav: NavHostController, route: String, target: String, label: String, icon: ImageVector) {
    val selected = route == target
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.25f)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clickable { nav.navigate(target) { launchSingleTop = true; restoreState = true } },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(route: String, openSettings: () -> Unit) {
    val title = when (route) {
        "downloads" -> "التنزيلات"
        "library" -> "المكتبة"
        "studio" -> "Smart Studio"
        "settings" -> "الإعدادات"
        else -> "AHDownload"
    }
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        actions = {
            if (route != "settings") IconButton(onClick = openSettings) {
                Icon(Icons.Default.Settings, "الإعدادات")
            }
        }
    )
}

@Composable
private fun HomeScreen(openDownloads: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { DownloadRepository(context) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<LinkAnalysis?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var platform by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            url = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { HeroHeader() }
        item {
            LinkInputCard(
                url = url,
                analyzing = analyzing,
                onUrlChange = { url = it; error = null; analysis = null; platform = null },
                onPaste = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    url = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
                },
                onAnalyze = {
                    val clean = url.trim()
                    analyzing = true
                    error = null
                    analysis = null
                    scope.launch {
                        val detectedPlatform = detectPlatform(clean)
                        val result = if (detectedPlatform != null && PLATFORM_RESOLVER_BASE_URL.isNotBlank()) {
                            PlatformResolverClient(PLATFORM_RESOLVER_BASE_URL).resolve(clean).map { resolved ->
                                LinkAnalysis(
                                    resolved.source,
                                    resolved.formats.firstOrNull()?.let { f ->
                                        if (f.hasVideo) "video/" + f.ext else if (f.hasAudio) "audio/" + f.ext else "application/octet-stream"
                                    },
                                    resolved.formats.firstOrNull()?.sizeBytes,
                                    resolved.title,
                                    detectedPlatform,
                                    resolved.formats
                                )
                            }
                        } else {
                            DirectUrlResolver().resolve(clean).map {
                                LinkAnalysis(
                                    it.source,
                                    it.formats.firstOrNull()?.let { f ->
                                        when {
                                            f.hasVideo -> "video/" + (f.container ?: "media")
                                            f.hasAudio -> "audio/" + (f.container ?: "media")
                                            else -> "application/octet-stream"
                                        }
                                    },
                                    it.sizeBytes,
                                    it.title,
                                    detectedPlatform
                                )
                            }
                        }
                        analyzing = false
                        result.onSuccess { info -> analysis = info }
                            .onFailure { failure ->
                                platform = detectedPlatform
                                error = when {
                                    failure.message == "HTML_PAGE_NOT_MEDIA" ->
                                        "هذا رابط صفحة وليس ملف وسائط مباشر. لن يتم حفظ HTML بالخطأ."
                                    failure.message == "RESOLVER_NOT_CONFIGURED" ->
                                        "تم التعرف على رابط " + platform + "، لكن محرك استخراج المنصة غير متصل حالياً."
                                    platform != null ->
                                        "تعذر استخراج وسائط حقيقية من " + platform + ". لن يتم تنزيل صفحة HTML بالخطأ."
                                    else ->
                                        "تعذر قراءة المصدر. تحقق من الرابط واتصال الإنترنت ثم حاول مرة أخرى."
                                }
                            }
                    }
                }
            )
        }
        item {
            AnimatedVisibility(error != null) {
                InfoCard(Icons.Default.Warning, "تعذر تحليل الرابط", error.orEmpty(), CardTone.Error)
            }
        }
        item {
            AnimatedVisibility(platform != null && analysis == null && error != null) {
                InfoCard(Icons.Default.Link, "تم التعرف على رابط منصة",
                    (platform ?: "المصدر") + " — لن يتم تنزيل صفحة HTML بالخطأ.", CardTone.Info)
            }
        }
        analysis?.let { info ->
            item {
                MediaResultCard(info) { selectedUrl, selectedTitle ->
                    repository.create(selectedUrl, selectedTitle)
                    url = ""
                    analysis = null
                    openDownloads()
                }
            }
        }
        item { QuickFeatures() }
    }
}

@Composable
private fun HeroHeader() {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .65f),
                    MaterialTheme.colorScheme.surface
                )
            )
        ).padding(22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark()
                Spacer(Modifier.width(12.dp))
                Text("AHDownload", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text("مركز تنزيل الوسائط الذكي", style = MaterialTheme.typography.titleMedium)
            Text("الصق الرابط، تحقّق من المحتوى، ثم اختر ما تريد تنزيله.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(27.dp))
    }
}

@Composable
private fun LinkInputCard(
    url: String,
    analyzing: Boolean,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onAnalyze: () -> Unit
) {
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("ابدأ برابط", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("فيديو، صوت، صورة أو ملف", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("الصق الرابط هنا") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Default.Public, null) },
                trailingIcon = {
                    if (url.isNotBlank()) IconButton(onClick = { onUrlChange("") }) {
                        Icon(Icons.Default.Clear, "مسح")
                    } else IconButton(onClick = onPaste) {
                        Icon(Icons.Default.ContentPaste, "لصق")
                    }
                }
            )
            Button(
                onClick = onAnalyze,
                enabled = url.trim().startsWith("http") && !analyzing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (analyzing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("جاري التحقق…")
                } else {
                    Icon(Icons.Default.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("تحليل الرابط")
                }
            }
        }
    }
}

private enum class CardTone { Info, Error }

@Composable
private fun InfoCard(icon: ImageVector, title: String, text: String, tone: CardTone) {
    val container = if (tone == CardTone.Error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (tone == CardTone.Error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = content)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = content)
                Spacer(Modifier.height(4.dp))
                Text(text, color = content)
            }
        }
    }
}

@Composable
private fun MediaResultCard(info: LinkAnalysis, onDownload: (String, String) -> Unit) {
    var selected by remember(info.url, info.formats) { mutableStateOf(info.formats.firstOrNull()) }
    val available = remember(info.formats) {
        info.formats.filter { it.hasVideo || it.hasAudio }
            .distinctBy { it.height.toString() + ":" + it.abr.toString() + ":" + it.ext + ":" + it.hasVideo + ":" + it.hasAudio }
            .sortedWith(compareByDescending<ResolvedFormat> { it.hasVideo }.thenByDescending { it.height ?: 0 }.thenByDescending { it.abr ?: 0.0 })
            .take(12)
    }
    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(58.dp).clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when {
                            info.isVideo -> Icons.Default.Movie
                            info.isAudio -> Icons.Default.Audiotrack
                            info.isImage -> Icons.Default.Image
                            else -> Icons.Default.InsertDriveFile
                        }, null, Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            info.isVideo -> "فيديو"
                            info.isAudio -> "صوت"
                            info.isImage -> "صورة"
                            else -> "ملف"
                        } + (info.platform?.let { " • " + it } ?: ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaChip(Icons.Default.Verified, if (info.isMedia) "مصدر وسائط" else "ملف مباشر")
                info.sizeBytes?.let { MetaChip(Icons.Default.Storage, formatBytes(it)) }
            }
            if (available.isNotEmpty()) {
                Text("الصيغ المتاحة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                available.forEach { format ->
                    val isSelected = selected?.id == format.id
                    OutlinedButton(
                        onClick = { selected = format },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Icon(if (format.hasVideo) Icons.Default.Movie else Icons.Default.Audiotrack, null)
                        Spacer(Modifier.width(8.dp))
                        Text(formatLabel(format), modifier = Modifier.weight(1f))
                        if (isSelected) Icon(Icons.Default.CheckCircle, null)
                    }
                }
            }
            Text(if (info.isVideo) "تم التحقق من المصدر. لا يوجد ضغط أو تقسيم تلقائي."
                 else "تم التحقق من المصدر قبل بدء التنزيل.")
            Button(
                onClick = { onDownload(selected?.url ?: info.url, info.title) },
                enabled = selected != null || info.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("بدء التنزيل")
            }
        }
    }
}

@Composable
private fun MetaChip(icon: ImageVector, text: String) {
    AssistChip(onClick = {}, enabled = false, leadingIcon = { Icon(icon, null) }, label = { Text(text) })
}

@Composable
private fun QuickFeatures() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("مصمم ليكون بسيطاً", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FeatureTile(Modifier.weight(1f), Icons.Default.Security, "تحقق قبل الحفظ")
            FeatureTile(Modifier.weight(1f), Icons.Default.HighQuality, "بدون ضغط تلقائي")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FeatureTile(Modifier.weight(1f), Icons.Default.DownloadDone, "تنزيل بالخلفية")
            FeatureTile(Modifier.weight(1f), Icons.Default.AutoAwesome, "Smart Studio")
        }
    }
}

@Composable
private fun FeatureTile(modifier: Modifier, icon: ImageVector, text: String) {
    OutlinedCard(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(text, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DownloadsScreen() {
    val context = LocalContext.current
    val repository = remember { DownloadRepository(context) }
    var jobs by remember { mutableStateOf(repository.all()) }
    LaunchedEffect(Unit) { while (true) { jobs = repository.all(); delay(750) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        DownloadSummary(jobs)
        if (jobs.isEmpty()) EmptyState(Icons.Default.Download, "لا توجد تنزيلات", "الصق رابطاً في الرئيسية وابدأ أول تنزيل.")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs, key = { it.id }) { job -> DownloadCard(job) { repository.cancel(job.id) } }
        }
    }
}

@Composable
private fun DownloadSummary(jobs: List<DownloadJob>) {
    val active = jobs.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RETRYING }
    val done = jobs.count { it.status == DownloadStatus.COMPLETED }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SummaryPill(Modifier.weight(1f), "نشطة", active.toString())
        SummaryPill(Modifier.weight(1f), "مكتملة", done.toString())
    }
}

@Composable
private fun SummaryPill(modifier: Modifier, label: String, value: String) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DownloadCard(job: DownloadJob, onCancel: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(statusLabel(job.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(job.progress.toString() + "%", fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(progress = { job.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(job.totalBytes?.let { formatBytes(job.downloadedBytes) + " / " + formatBytes(it) }
                    ?: formatBytes(job.downloadedBytes), style = MaterialTheme.typography.bodySmall)
                if (job.status == DownloadStatus.DOWNLOADING || job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RETRYING) {
                    TextButton(onClick = onCancel) { Text("إلغاء") }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen() {
    val context = LocalContext.current
    val repository = remember { DownloadRepository(context) }
    var completed by remember { mutableStateOf(repository.all().filter { it.status == DownloadStatus.COMPLETED }) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { completed = repository.all().filter { it.status == DownloadStatus.COMPLETED }; delay(1000) } }
    val filtered = completed.filter { it.title.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = { Text("ابحث في المكتبة") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotBlank()) IconButton({ query = "" }) { Icon(Icons.Default.Clear, null) } },
            singleLine = true, shape = RoundedCornerShape(16.dp)
        )
        if (filtered.isEmpty()) EmptyState(Icons.Default.VideoLibrary, "المكتبة فارغة", "ستظهر الملفات المكتملة هنا بعد التنزيل.")
        else LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { job -> LibraryCard(context, job) }
        }
    }
}

@Composable
private fun LibraryCard(context: Context, job: DownloadJob) {
    OutlinedCard(shape = RoundedCornerShape(18.dp)) {
        ListItem(
            leadingContent = {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center) { Icon(fileIcon(job.title), null) }
            },
            headlineContent = { Text(job.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(job.totalBytes?.let(::formatBytes) ?: "الحجم غير معروف") },
            trailingContent = {
                Row {
                    IconButton({ openOutput(context, job.outputUri) }) { Icon(Icons.Default.OpenInNew, "فتح") }
                    IconButton({ shareOutput(context, job.outputUri) }) { Icon(Icons.Default.Share, "مشاركة") }
                }
            }
        )
    }
}

@Composable
private fun StudioScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Smart Studio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("أدوات اختيارية بعد التنزيل. الملف الأصلي يبقى كما هو.")
        }
        item { StudioCard(Icons.Default.ContentCut, "قص الفيديو", "حدد البداية والنهاية وأنشئ نسخة جديدة.") }
        item { StudioCard(Icons.Default.CallSplit, "تقسيم يدوي", "قسّم الملف عند الحاجة فقط، وليس تلقائياً.") }
        item { StudioCard(Icons.Default.Audiotrack, "استخراج الصوت", "أنشئ نسخة صوتية من فيديو محفوظ.") }
        item { StudioCard(Icons.Default.Image, "استخراج صورة", "احفظ إطاراً محدداً كصورة.") }
        item { StudioCard(Icons.Default.Transform, "تحويل", "تحويل الصيغة عند توفر محرك المعالجة.") }
    }
}

@Composable
private fun StudioCard(icon: ImageVector, title: String, description: String) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        ListItem(
            modifier = Modifier.padding(vertical = 4.dp),
            leadingContent = {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
            },
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(description) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) }
        )
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ahdownload_settings", Context.MODE_PRIVATE) }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("wifi_only", false)) }
    var notifications by remember { mutableStateOf(prefs.getBoolean("notifications", true)) }
    var background by remember { mutableStateOf(prefs.getBoolean("background", true)) }
    var concurrent by remember { mutableIntStateOf(prefs.getInt("concurrent", 2).coerceIn(1, 4)) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("الإعدادات", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("تحكم في تجربة التنزيل والتخزين والخصوصية.")
        }
        item { SettingsSection("التنزيل") {
            Setting("التنزيل عبر Wi‑Fi فقط", wifiOnly) { wifiOnly = it; prefs.edit().putBoolean("wifi_only", it).apply() }
            Setting("إشعارات اكتمال التنزيل", notifications) { notifications = it; prefs.edit().putBoolean("notifications", it).apply() }
            Setting("التنزيل في الخلفية", background) { background = it; prefs.edit().putBoolean("background", it).apply() }
            ListItem(
                headlineContent = { Text("التنزيلات المتزامنة") },
                supportingContent = { Text(concurrent.toString() + " تنزيلات") },
                trailingContent = {
                    Row {
                        IconButton({ if (concurrent > 1) { concurrent--; prefs.edit().putInt("concurrent", concurrent).apply() } }) { Icon(Icons.Default.Remove, null) }
                        IconButton({ if (concurrent < 4) { concurrent++; prefs.edit().putInt("concurrent", concurrent).apply() } }) { Icon(Icons.Default.Add, null) }
                    }
                }
            )
        } }
        item { SettingsSection("التخزين") {
            ListItem(leadingContent = { Icon(Icons.Default.Folder, null) },
                headlineContent = { Text("موقع التنزيل") }, supportingContent = { Text("مجلد AHDownload في تخزين الجهاز") })
            ListItem(leadingContent = { Icon(Icons.Default.Storage, null) },
                headlineContent = { Text("الملفات الأصلية") }, supportingContent = { Text("لا يوجد ضغط تلقائي أو تقسيم تلقائي.") })
        } }
        item { SettingsSection("الخصوصية") {
            ListItem(leadingContent = { Icon(Icons.Default.Lock, null) },
                headlineContent = { Text("التحليل المباشر") }, supportingContent = { Text("لا توجد مصادقة وهمية أو حساب سحابي مفروض للتنزيل الأساسي.") })
        } }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
        Column {
            Text(title, Modifier.padding(start = 16.dp, top = 15.dp),
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Setting(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, trailingContent = { Switch(checked, onChange) })
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatLabel(format: ResolvedFormat): String {
    val quality = format.height?.let { it.toString() + "p" } ?: format.abr?.let { it.toInt().toString() + " kbps" } ?: format.ext.uppercase(Locale.US)
    val mode = when { format.hasVideo && format.hasAudio -> "فيديو"; format.hasVideo -> "فيديو بدون صوت"; else -> "صوت" }
    val size = format.sizeBytes?.let { " • " + formatBytes(it) } ?: ""
    return mode + " • " + quality + " • " + format.ext.uppercase(Locale.US) + size
}

private fun fileIcon(title: String): ImageVector {
    val t = title.lowercase(Locale.US)
    return when {
        t.endsWith(".mp4") || t.endsWith(".mkv") || t.endsWith(".webm") || t.endsWith(".mov") -> Icons.Default.Movie
        t.endsWith(".mp3") || t.endsWith(".m4a") || t.endsWith(".aac") || t.endsWith(".wav") -> Icons.Default.Audiotrack
        t.endsWith(".jpg") || t.endsWith(".jpeg") || t.endsWith(".png") || t.endsWith(".webp") -> Icons.Default.Image
        else -> Icons.Default.InsertDriveFile
    }
}

private fun openOutput(context: Context, uriString: String?) {
    if (uriString.isNullOrBlank()) return
    val uri = Uri.parse(uriString)
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("AHDownload", uri)
    }
    runCatching { context.startActivity(intent) }
}

private fun shareOutput(context: Context, uriString: String?) {
    if (uriString.isNullOrBlank()) return
    val uri = Uri.parse(uriString)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = context.contentResolver.getType(uri) ?: "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "مشاركة الملف")) }
}

private fun statusLabel(status: DownloadStatus) = when (status) {
    DownloadStatus.COMPLETED -> "مكتمل"
    DownloadStatus.DOWNLOADING -> "جارٍ التنزيل"
    DownloadStatus.QUEUED -> "في الانتظار"
    DownloadStatus.RETRYING -> "إعادة المحاولة"
    DownloadStatus.FAILED -> "فشل"
    DownloadStatus.CANCELLED -> "ملغى"
    else -> status.name
}

private fun detectPlatform(url: String): String? {
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return when {
        host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" -> "YouTube"
        host == "instagram.com" || host.endsWith(".instagram.com") -> "Instagram"
        host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" -> "Facebook"
        host == "tiktok.com" || host.endsWith(".tiktok.com") -> "TikTok"
        host == "twitter.com" || host.endsWith(".twitter.com") || host == "x.com" || host.endsWith(".x.com") -> "X"
        host == "vimeo.com" || host.endsWith(".vimeo.com") -> "Vimeo"
        host == "reddit.com" || host.endsWith(".reddit.com") -> "Reddit"
        else -> null
    }
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return value.toString() + " B"
    val units = listOf("KB", "MB", "GB", "TB")
    var n = value.toDouble()
    var index = -1
    while (n >= 1024 && index < units.lastIndex) { n /= 1024; index++ }
    return String.format(Locale.US, "%.1f %s", n, units[index])
}
