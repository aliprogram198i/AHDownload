package com.ahdownload.app.ui

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
import com.ahdownload.app.ui.theme.AHDownloadTheme

private enum class MediaChoice { VIDEO, AUDIO, FILE }

@Composable
fun AppRoot() {
    AHDownloadTheme {
        val nav = rememberNavController()
        val back by nav.currentBackStackEntryAsState()
        val route = back?.destination?.route
        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf(
                        Triple("home", "الرئيسية", Icons.Default.Home),
                        Triple("downloads", "التنزيلات", Icons.Default.Download),
                        Triple("library", "المكتبة", Icons.Default.Folder),
                        Triple("settings", "الإعدادات", Icons.Default.Settings)
                    ).forEach { (r, label, icon) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = { nav.navigate(r) { launchSingleTop = true } },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") { HomeScreen() }
                composable("downloads") { DownloadsScreen() }
                composable("library") { LibraryScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    var url by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var analyzed by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf(MediaChoice.VIDEO) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
                        onValueChange = { url = it; analyzed = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("ألصق الرابط هنا") },
                        placeholder = { Text("https://…") },
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                        singleLine = true
                    )
                    Button(
                        onClick = { analyzing = true; analyzed = false },
                        enabled = url.trim().startsWith("http") && !analyzing,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (analyzing) "جاري تحليل الرابط…" else "تحليل الرابط") }
                    if (analyzing) {
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(500)
                            analyzing = false
                            analyzed = true
                        }
                    }
                }
            }
        }
        if (analyzed) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("نتيجة التحليل", style = MaterialTheme.typography.titleLarge)
                        Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("سيتم عرض الصيغ والجودات الفعلية فقط بعد وصول بيانات المصدر.")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(MediaChoice.VIDEO to "فيديو", MediaChoice.AUDIO to "صوت", MediaChoice.FILE to "ملف").forEachIndexed { index, (value, label) ->
                                SegmentedButton(
                                    selected = choice == value,
                                    onClick = { choice = value },
                                    shape = SegmentedButtonDefaults.itemShape(index, 3)
                                ) { Text(label) }
                            }
                        }
                        Button(onClick = {}, Modifier.fillMaxWidth()) { Text("اختيار الصيغة والتنزيل") }
                    }
                }
            }
        }
        item { Text("وصول سريع", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickCard("📋", "لصق الرابط") { }
                QuickCard("📥", "التنزيلات") { }
                QuickCard("✂️", "Smart Studio") { }
            }
        }
    }
}

@Composable
private fun QuickCard(icon: String, title: String, onClick: () -> Unit) {
    OutlinedCard(Modifier.weight(1f), onClick = onClick) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, style = MaterialTheme.typography.titleLarge)
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DownloadsScreen() {
    val items = remember { mutableStateListOf<Pair<String, Int>>() }
    if (items.isEmpty()) {
        EmptyState("لا توجد تنزيلات بعد", "عند بدء تنزيل سيظهر تقدمه هنا.")
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items) { (title, progress) ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth())
                        Text("$progress%")
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen() {
    EmptyState("المكتبة فارغة", "ستظهر الملفات المكتملة هنا بعد تنزيلها بنجاح.")
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(52.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SettingsScreen() {
    var wifiOnly by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(true) }
    var background by remember { mutableStateOf(true) }
    var concurrent by remember { mutableIntStateOf(2) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("الإعدادات", style = MaterialTheme.typography.headlineMedium) }
        item { Setting("التنزيل عبر Wi‑Fi فقط", wifiOnly) { wifiOnly = it } }
        item { Setting("إشعارات اكتمال التنزيل", notifications) { notifications = it } }
        item { Setting("السماح بالتنزيل في الخلفية", background) { background = it } }
        item {
            ListItem(
                headlineContent = { Text("التنزيلات المتزامنة") },
                supportingContent = { Text("$concurrent تنزيلات") },
                trailingContent = {
                    Row {
                        IconButton(onClick = { if (concurrent > 1) concurrent-- }) { Icon(Icons.Default.Remove, null) }
                        IconButton(onClick = { if (concurrent < 4) concurrent++ }) { Icon(Icons.Default.Add, null) }
                    }
                }
            )
        }
        item { HorizontalDivider() }
        item { Text("التطبيق لا يضغط أو يقسم الفيديو تلقائيًا. التقسيم والمعالجة خيارات يدوية داخل Smart Studio.", Modifier.padding(vertical = 12.dp)) }
    }
}

@Composable
private fun Setting(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, trailingContent = { Switch(checked, onChange) })
}
