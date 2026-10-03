package com.ahdownload.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.ahdownload.app.data.DownloadRepository
import com.ahdownload.app.domain.DownloadJob
import com.ahdownload.app.domain.DownloadStatus
import com.ahdownload.app.ui.theme.AHDownloadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

private data class LinkAnalysis(
    val url: String,
    val contentType: String?,
    val sizeBytes: Long?,
    val title: String
) {
    val isMedia: Boolean
        get() = contentType?.startsWith("video/") == true ||
            contentType?.startsWith("audio/") == true ||
            contentType?.startsWith("image/") == true
}

@Composable
fun AppRoot() {
    AHDownloadTheme {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        Scaffold(
            topBar = {
                if (route != "home") CenterAlignedTopAppBar(title = { Text("AHDownload") })
            },
            bottomBar = {
                NavigationBar {
                    listOf(
                        Triple("home", "الرئيسية", Icons.Default.Home),
                        Triple("downloads", "التنزيلات", Icons.Default.Download),
                        Triple("library", "المكتبة", Icons.Default.Folder),
                        Triple("studio", "Studio", Icons.Default.AutoAwesome),
                        Triple("settings", "الإعدادات", Icons.Default.Settings)
                    ).forEach { item ->
                        NavigationBarItem(
                            selected = route == item.first,
                            onClick = { nav.navigate(item.first) { launchSingleTop = true } },
                            icon = { Icon(item.third, null) },
                            label = { Text(item.second) }
                        )
                    }
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
private fun HomeScreen(openDownloads: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { DownloadRepository(context) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<LinkAnalysis?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            url = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AHDownload", style = MaterialTheme.typography.headlineLarge)
                Text("Smart Download & Media Center", style = MaterialTheme.typography.bodyLarge)
                Text("نزّل الملفات إلى تخزين الجهاز بدون ضغط أو تقسيم تلقائي.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("تنزيل رابط", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; analysis = null; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("الرابط") },
                        placeholder = { Text("https://example.com/file.mp4") },
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                url = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            }) { Icon(Icons.Default.ContentPaste, "لصق") }
                        },
                        singleLine = true
                    )
                    Button(
                        enabled = url.trim().startsWith("http") && !analyzing,
                        onClick = {
                            val clean = url.trim()
                            analyzing = true
                            error = null
                            scope.launch {
                                val result = analyzeDirectUrl(clean)
                                analyzing = false
                                result.onSuccess { analysis = it }
                                    .onFailure { error = "تعذر تحليل الرابط مباشرة: " + (it.message ?: "مصدر غير متاح") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (analyzing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (analyzing) "جاري التحليل…" else "تحليل الرابط")
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        analysis?.let { info ->
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (info.isMedia) Icons.Default.Movie else Icons.Default.InsertDriveFile,
                                null,
                                Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(info.contentType ?: "نوع غير محدد")
                            }
                        }
                        info.sizeBytes?.let { Text("الحجم المتوقع: " + formatBytes(it)) }
                        Text(
                            if (info.isMedia)
                                "تم اكتشاف ملف وسائط مباشر. سيُحفظ الملف الأصلي دون ضغط."
                            else
                                "تم اكتشاف ملف مباشر. روابط المنصات التي تحتاج Resolver ليست ممثلة كملفات مباشرة."
                        )
                        Button(
                            onClick = {
                                repository.create(info.url, info.title)
                                url = ""
                                analysis = null
                                openDownloads()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("بدء التنزيل") }
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تنزيل آمن ومنظم", style = MaterialTheme.typography.titleMedium)
                    FeatureLine(Icons.Default.PauseCircle, "استئناف HTTP Range عند دعمه")
                    FeatureLine(Icons.Default.Folder, "الحفظ في مجلد AHDownload عبر MediaStore")
                    FeatureLine(Icons.Default.CloudOff, "لا يتم اختراع جودات أو أحجام غير مؤكدة")
                    FeatureLine(Icons.Default.Build, "Studio يدوي بعد التنزيل")
                }
            }
        }
    }
}

private suspend fun analyzeDirectUrl(url: String): Result<LinkAnalysis> = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder().url(url).head().build()
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP " + response.code)
                val type = response.header("Content-Type")?.substringBefore(";")?.lowercase(Locale.US)
                val size = response.header("Content-Length")?.toLongOrNull()
                LinkAnalysis(url, type, size, titleFromUrl(url))
            }
    }
}

private fun titleFromUrl(url: String): String =
    Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: "AHDownload file"

private fun formatBytes(value: Long): String {
    if (value < 1024) return value.toString() + " B"
    val units = listOf("KB", "MB", "GB", "TB")
    var n = value.toDouble()
    var index = -1
    while (n >= 1024 && index < units.lastIndex) { n /= 1024; index++ }
    return String.format(Locale.US, "%.1f %s", n, units[index])
}

@Composable
private fun FeatureLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text)
    }
}

@Composable
private fun DownloadsScreen() {
    val context = LocalContext.current
    val repository = remember { DownloadRepository(context) }
    var jobs by remember { mutableStateOf(repository.all()) }
    LaunchedEffect(Unit) {
        while (true) { jobs = repository.all(); delay(750) }
    }
    if (jobs.isEmpty()) {
        EmptyState("لا توجد تنزيلات", "ابدأ من الرئيسية أو شارك رابطًا مع AHDownload.")
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs, key = { it.id }) { job -> DownloadCard(job) { repository.cancel(job.id) } }
        }
    }
}

@Composable
private fun DownloadCard(job: DownloadJob, onCancel: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { job.progress / 100f }, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(job.progress.toString() + "%")
                Text(statusLabel(job.status))
            }
            if (job.totalBytes != null) {
                Text(formatBytes(job.downloadedBytes) + " / " + formatBytes(job.totalBytes), style = MaterialTheme.typography.bodySmall)
            }
            if (job.status == DownloadStatus.DOWNLOADING || job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RETRYING) {
                OutlinedButton(onClick = onCancel) { Text("إلغاء") }
            }
        }
    }
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

@Composable
private fun LibraryScreen() {
    val context = LocalContext.current
    val repository = remember { DownloadRepository(context) }
    var completed by remember { mutableStateOf(repository.all().filter { it.status == DownloadStatus.COMPLETED }) }
    LaunchedEffect(Unit) {
        while (true) { completed = repository.all().filter { it.status == DownloadStatus.COMPLETED }; delay(1000) }
    }
    if (completed.isEmpty()) EmptyState("المكتبة فارغة", "ستظهر الملفات المكتملة هنا.")
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(completed, key = { it.id }) { job ->
            ListItem(
                leadingContent = { Icon(Icons.Default.InsertDriveFile, null) },
                headlineContent = { Text(job.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("الحفظ: " + if (job.outputUri?.startsWith("content://") == true) "تخزين الجهاز" else "تخزين التطبيق") },
                trailingContent = { IconButton(onClick = { openOutput(context, job.outputUri) }) { Icon(Icons.Default.OpenInNew, "فتح") } }
            )
        }
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

@Composable
private fun StudioScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Smart Studio", style = MaterialTheme.typography.headlineMedium)
            Text("أدوات معالجة يدوية بعد اكتمال التنزيل، مع الحفاظ على الملف الأصلي.")
        }
        item { StudioCard(Icons.Default.ContentCut, "قص الفيديو", "تحديد بداية ونهاية ثم تصدير نسخة جديدة.") }
        item { StudioCard(Icons.Default.CallSplit, "تقسيم يدوي", "تقسيم الملف عند الحاجة فقط، دون أي تشغيل تلقائي.") }
        item { StudioCard(Icons.Default.Audiotrack, "استخراج الصوت", "إنشاء نسخة صوتية من ملف فيديو.") }
        item { StudioCard(Icons.Default.Image, "استخراج صورة", "استخراج إطار كصورة مصغرة.") }
        item { StudioCard(Icons.Default.Transform, "تحويل", "تحويل صيغة الوسائط عند توفر محرك المعالجة.") }
    }
}

@Composable
private fun StudioCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(icon, null, Modifier.size(30.dp)) },
            headlineContent = { Text(title) },
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Text("الإعدادات", style = MaterialTheme.typography.headlineMedium)
            Text("إعدادات التطبيق تُحفظ محليًا ولا تُطلب صلاحيات تخزين واسعة.")
        }
        item { Setting("التنزيل عبر Wi‑Fi فقط", wifiOnly) { wifiOnly = it; prefs.edit().putBoolean("wifi_only", it).apply() } }
        item { Setting("إشعارات اكتمال التنزيل", notifications) { notifications = it; prefs.edit().putBoolean("notifications", it).apply() } }
        item { Setting("السماح بالتنزيل في الخلفية", background) { background = it; prefs.edit().putBoolean("background", it).apply() } }
        item {
            ListItem(
                headlineContent = { Text("التنزيلات المتزامنة") },
                supportingContent = { Text(concurrent.toString() + " تنزيلات") },
                trailingContent = {
                    Row {
                        IconButton(onClick = { if (concurrent > 1) { concurrent--; prefs.edit().putInt("concurrent", concurrent).apply() } }) { Icon(Icons.Default.Remove, null) }
                        IconButton(onClick = { if (concurrent < 4) { concurrent++; prefs.edit().putInt("concurrent", concurrent).apply() } }) { Icon(Icons.Default.Add, null) }
                    }
                }
            )
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("التخزين", style = MaterialTheme.typography.titleMedium)
                    Text("Android 10+ يستخدم MediaStore ويحفظ الملفات في AHDownload داخل Downloads أو Movies أو Music أو Pictures حسب النوع، بدون صلاحية تخزين عامة.")
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("الخصوصية", style = MaterialTheme.typography.titleMedium)
                    Text("لا يتم إرسال الرابط إلى خدمة خارجية في التحليل المباشر الحالي. لا توجد مصادقة وهمية أو حساب محلي يُقدَّم كحساب سحابي.")
                }
            }
        }
    }
}

@Composable
private fun Setting(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, trailingContent = { Switch(checked, onChange) })
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(52.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle)
        }
    }
}
