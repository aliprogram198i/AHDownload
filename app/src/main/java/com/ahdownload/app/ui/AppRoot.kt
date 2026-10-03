package com.ahdownload.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.ahdownload.app.data.DownloadRepository
import com.ahdownload.app.domain.DownloadJob
import com.ahdownload.app.domain.DownloadStatus
import com.ahdownload.app.ui.theme.AHDownloadTheme
import kotlinx.coroutines.delay

@Composable
fun AppRoot() {
    AHDownloadTheme {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf(
                        Triple("home", "الرئيسية", Icons.Default.Home),
                        Triple("downloads", "التنزيلات", Icons.Default.Download),
                        Triple("library", "المكتبة", Icons.Default.Folder),
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
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun HomeScreen(openDownloads: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { DownloadRepository(context) }
    var url by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var valid by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("AHDownload", style = MaterialTheme.typography.headlineLarge)
            Text("Smart Download & Media Center", style = MaterialTheme.typography.bodyLarge)
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("تنزيل رابط", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            valid = false
                            message = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("الرابط") },
                        placeholder = { Text("https://example.com/video.mp4") },
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
                        enabled = url.trim().startsWith("http") && !checking,
                        onClick = { checking = true; message = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (checking) "جاري التحقق…" else "تحليل الرابط") }
                    if (checking) {
                        LaunchedEffect(url) {
                            delay(300)
                            checking = false
                            valid = url.trim().startsWith("http")
                            message = if (valid) {
                                "الرابط صالح. التنزيل المباشر يعمل مع الملفات التي يعرضها المصدر كـ HTTP/HTTPS."
                            } else {
                                "الرابط غير صالح."
                            }
                        }
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (valid) {
                        Button(
                            onClick = {
                                repository.create(url.trim())
                                url = ""
                                valid = false
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
                    Text("حماية السلوك", style = MaterialTheme.typography.titleMedium)
                    Text("لا ضغط تلقائي، لا تقسيم تلقائي، واستئناف باستخدام HTTP Range عند توفره. روابط المنصات التي تحتاج Resolver API ليست ممثلة كروابط مباشرة.")
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { DownloadRepository(context) }
    var jobs by remember { mutableStateOf(repository.all()) }

    LaunchedEffect(Unit) {
        while (true) {
            jobs = repository.all()
            delay(750)
        }
    }

    if (jobs.isEmpty()) {
        EmptyState("لا توجد تنزيلات", "ابدأ من الرئيسية.")
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(jobs, key = { it.id }) { job ->
                DownloadCard(job) { repository.cancel(job.id) }
            }
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
            if (job.status == DownloadStatus.DOWNLOADING ||
                job.status == DownloadStatus.QUEUED ||
                job.status == DownloadStatus.RETRYING) {
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { DownloadRepository(context) }
    val completed = repository.all().filter { it.status == DownloadStatus.COMPLETED }
    if (completed.isEmpty()) {
        EmptyState("المكتبة فارغة", "ستظهر الملفات المكتملة هنا.")
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(completed) { job ->
                ListItem(
                    leadingContent = { Icon(Icons.Default.InsertDriveFile, null) },
                    headlineContent = { Text(job.title) },
                    supportingContent = { Text(job.sourceUrl, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    var wifiOnly by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(true) }
    var background by remember { mutableStateOf(true) }
    var concurrent by remember { mutableIntStateOf(2) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item { Text("الإعدادات", style = MaterialTheme.typography.headlineMedium) }
        item { Setting("التنزيل عبر Wi‑Fi فقط", wifiOnly) { wifiOnly = it } }
        item { Setting("إشعارات اكتمال التنزيل", notifications) { notifications = it } }
        item { Setting("السماح بالتنزيل في الخلفية", background) { background = it } }
        item {
            ListItem(
                headlineContent = { Text("التنزيلات المتزامنة") },
                supportingContent = { Text(concurrent.toString() + " تنزيلات") },
                trailingContent = {
                    Row {
                        IconButton(onClick = { if (concurrent > 1) concurrent-- }) { Icon(Icons.Default.Remove, null) }
                        IconButton(onClick = { if (concurrent < 4) concurrent++ }) { Icon(Icons.Default.Add, null) }
                    }
                }
            )
        }
        item {
            Text(
                "Smart Studio يبقى يدويًا بعد التنزيل. لا ضغط أو تقسيم تلقائي.",
                Modifier.padding(vertical = 16.dp)
            )
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
