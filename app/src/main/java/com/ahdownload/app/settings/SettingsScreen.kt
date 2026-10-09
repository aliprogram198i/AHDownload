package com.ahdownload.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ahdownload.app.BuildConfig
import com.ahdownload.app.diagnostics.PersistentDiagnosticLogger
import com.ahdownload.core.common.AudioBitratePreference
import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLog
import com.ahdownload.core.common.DownloadPreferences
import com.ahdownload.core.common.DownloadPreferencesProvider
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.VideoQualityPreference
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.AHBottomNavDestination
import com.ahdownload.core.designsystem.AHBottomNavigationBar
import com.ahdownload.core.designsystem.AHThemeMode
import com.ahdownload.core.designsystem.rememberUiTraceContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    store: DownloadLocationStore,
    diagnosticLogger: PersistentDiagnosticLogger,
    themeMode: AHThemeMode,
    onThemeChanged: (AHThemeMode) -> Unit,
    onPickDownloadFolder: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenUiDiagnostics: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateDownloads: () -> Unit,
    uiTraceLogger: UiTraceLogger,
    activeDownloads: Int = 0,
) {
    val context = LocalContext.current
    val location by store.location.collectAsState()
    val preferencesStore = remember(context) { DownloadPreferencesStore(context) }
    var preferences by remember { mutableStateOf(preferencesStore.read()) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var folderError by remember { mutableStateOf<String?>(null) }
    var storageInfo by remember(context) { mutableStateOf(StorageInfoReader.read(context)) }
    var latestError by remember { mutableStateOf<DiagnosticLog?>(null) }
    val uiContext = rememberUiTraceContext()

    LaunchedEffect(Unit) {
        latestError = diagnosticLogger.list().firstOrNull { it.level == DiagnosticLevel.ERROR }
    }

    val currentError = latestError

    LaunchedEffect(location, showFolderDialog, folderError, themeMode, currentError) {
        uiTraceLogger.snapshot(
            screen = "SETTINGS",
            component = "SettingsScreen",
            components = buildList {
                add("topbar")
                add("download_preferences")
                add("storage")
                add("appearance")
                add("diagnostics")
                add("about")
                add("bottom_navigation")
                if (showFolderDialog) add("create_folder_dialog")
                if (folderError != null) add("folder_error")
            }.joinToString(","),
            stateSummary = "custom=" + location.isCustom +
                ";accessible=" + location.isAccessible +
                ";storage_free_percent=" + ((storageInfo.freeRatio * 100f).toInt()) +
                ";theme=" + themeMode.storageValue +
                ";latest_error=" + (latestError != null),
            context = uiContext,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات") },
                navigationIcon = {
                    IconButton(onClick = {
                        uiTraceLogger.interaction("SETTINGS", "back_button", "back")
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
        bottomBar = {
            AHBottomNavigationBar(
                selected = AHBottomNavDestination.SETTINGS,
                activeDownloads = activeDownloads,
                onDestinationSelected = { destination ->
                    when (destination) {
                        AHBottomNavDestination.HOME -> {
                            uiTraceLogger.interaction("SETTINGS", "bottom_nav_home", "open_home")
                            onNavigateHome()
                        }
                        AHBottomNavDestination.DOWNLOADS -> {
                            uiTraceLogger.interaction("SETTINGS", "bottom_nav_downloads", "open_downloads")
                            onNavigateDownloads()
                        }
                        AHBottomNavDestination.SETTINGS -> Unit
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "الإعدادات",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "كل ما تحتاجه لضبط التنزيل والتخزين والمظهر والتشخيص في مكان واحد.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SettingsSectionTitle("التنزيل") }

            item {
                SettingsCard {
                    SettingsSwitchRow(
                        title = "Wi‑Fi فقط",
                        description = "تقييد التنزيلات الجديدة على شبكة Wi‑Fi.",
                        checked = preferences.wifiOnly,
                        onCheckedChange = {
                            preferencesStore.setWifiOnly(it)
                            preferences = preferences.copy(wifiOnly = it)
                        },
                    )
                    Text("جودة الفيديو الافتراضية", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(VideoQualityPreference.entries) { quality ->
                            FilterChip(
                                selected = preferences.videoQuality == quality,
                                onClick = {
                                    preferencesStore.setVideoQuality(quality)
                                    preferences = preferences.copy(videoQuality = quality)
                                },
                                label = { Text(quality.label) },
                            )
                        }
                    }
                    Text("جودة الصوت الافتراضية", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AudioBitratePreference.entries) { bitrate ->
                            FilterChip(
                                selected = preferences.audioBitrate == bitrate,
                                onClick = {
                                    preferencesStore.setAudioBitrate(bitrate)
                                    preferences = preferences.copy(audioBitrate = bitrate)
                                },
                                label = { Text(bitrate.label) },
                            )
                        }
                    }
                }
            }

            item { SettingsSectionTitle("التخزين") }

            item {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Folder, contentDescription = null)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "مجلد التنزيل",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(location.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    !location.isAccessible -> "غير متاح — أعد اختيار المجلد"
                                    location.isCustom -> "صلاحية القراءة والكتابة فعالة"
                                    else -> "المجلد الافتراضي للتطبيق فعال"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (location.isAccessible) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }

                    Button(
                        onClick = {
                            uiTraceLogger.interaction("SETTINGS", "change_folder_button", "pick_folder")
                            onPickDownloadFolder()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("تغيير مجلد التنزيل")
                    }

                    if (location.isCustom) {
                        OutlinedButton(
                            onClick = {
                                uiTraceLogger.interaction("SETTINGS", "reset_default_button", "reset")
                                store.resetToDefault()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("العودة للمجلد الافتراضي")
                        }
                        OutlinedButton(
                            onClick = {
                                uiTraceLogger.interaction("SETTINGS", "create_folder_button", "open_dialog")
                                folderError = null
                                folderName = ""
                                showFolderDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                            Text("إنشاء مجلد داخل المسار")
                        }
                    }
                }
            }

            item {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "صحة التخزين",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "المساحة المتاحة على وحدة التخزين المستخدمة من AHDownload.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                uiTraceLogger.interaction("SETTINGS", "storage_refresh", "refresh")
                                storageInfo = StorageInfoReader.read(context)
                            },
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "تحديث مساحة التخزين")
                        }
                    }
                    LinearProgressIndicator(
                        progress = { storageInfo.freeRatio },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "متاح " + formatStorageBytes(storageInfo.freeBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "من " + formatStorageBytes(storageInfo.totalBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (storageInfo.isLow) {
                            "المساحة منخفضة. قد تفشل الملفات الكبيرة."
                        } else {
                            "التخزين ضمن النطاق الطبيعي."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (storageInfo.isLow) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            item { SettingsSectionTitle("المظهر") }

            item {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Palette, contentDescription = null)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                "الثيم",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                themeMode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AHThemeMode.entries) { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = {
                                    uiTraceLogger.interaction(
                                        "SETTINGS",
                                        "theme_" + mode.storageValue,
                                        "set_theme",
                                    )
                                    onThemeChanged(mode)
                                },
                                label = { Text(mode.label) },
                            )
                        }
                    }

                    if (themeMode == AHThemeMode.DARK_TECH) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF0B0F19),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Dark Tech",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color(0xFFF2F7FF),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color(0xFF21D9FF).copy(alpha = 0.14f),
                                    ) {
                                        Text(
                                            "Engine Ready",
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF21D9FF),
                                        )
                                    }
                                }
                                Text(
                                    "Obsidian عميق مع Cyan وIndigo وإضاءات خفيفة بدون تشتيت.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFA9B6C7),
                                )
                            }
                        }
                    }
                }
            }

            item { SettingsSectionTitle("التشخيص والدعم") }

            item {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (latestError == null) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.ErrorOutline
                            },
                            contentDescription = null,
                            tint = if (latestError == null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "آخر مشكلة",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (currentError == null) {
                                    "لا توجد أخطاء مسجلة حاليًا."
                                } else {
                                    currentError.type
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            currentError?.let {
                                Text(
                                    it.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                                Text(
                                    formatDiagnosticTime(it.timestampEpochMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            uiTraceLogger.interaction("SETTINGS", "diagnostics_button", "open_diagnostics")
                            onOpenDiagnostics()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                        Text("فتح سجل الأخطاء")
                    }

                    OutlinedButton(
                        onClick = {
                            uiTraceLogger.interaction("SETTINGS", "ui_diagnostics_button", "open_ui_diagnostics")
                            onOpenUiDiagnostics()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null)
                        Text("تشخيص الواجهة")
                    }
                }
            }

            item { SettingsSectionTitle("حول") }

            item {
                SettingsCard {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("AHDownload", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "الإصدار " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "تنزيل الوسائط مع التحقق من المصدر وإدارة التخزين والمعالجة محليًا.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFolderDialog && location.isCustom && location.isAccessible) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text("إنشاء مجلد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = {
                            folderName = it
                            folderError = null
                        },
                        label = { Text("اسم المجلد") },
                        singleLine = true,
                        isError = folderError != null,
                    )
                    folderError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = folderName.trim().isNotBlank(),
                    onClick = {
                        val created = runCatching {
                            SelectedDirectoryStorage.createFolder(
                                context = context,
                                treeUri = location.uri ?: error("Custom tree URI missing"),
                                folderName = folderName,
                            )
                        }.getOrNull()
                        if (created != null) {
                            folderName = ""
                            folderError = null
                            showFolderDialog = false
                        } else {
                            folderError = "تعذر إنشاء المجلد في هذا المسار."
                        }
                    },
                ) {
                    Text("إنشاء")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showFolderDialog = false }) {
                    Text("إلغاء")
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatDiagnosticTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

class DownloadPreferencesStore(context: android.content.Context) : DownloadPreferencesProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        "ahdownload_download_preferences",
        android.content.Context.MODE_PRIVATE,
    )

    init {
        // Remove the retired setting without affecting other download preferences.
        preferences.edit().remove("smart_download").apply()
    }

    override fun read(): DownloadPreferences = DownloadPreferences(
        wifiOnly = preferences.getBoolean("wifi_only", false),
        videoQuality = VideoQualityPreference.entries.firstOrNull {
            it.wireValue == preferences.getString("video_quality", "auto")
        } ?: VideoQualityPreference.AUTO,
        audioBitrate = AudioBitratePreference.entries.firstOrNull {
            it.kbps == preferences.getInt("audio_bitrate", 0)
        } ?: AudioBitratePreference.AUTO,
    )

    fun setWifiOnly(enabled: Boolean) {
        preferences.edit().putBoolean("wifi_only", enabled).apply()
    }

    fun setVideoQuality(value: VideoQualityPreference) {
        preferences.edit().putString("video_quality", value.wireValue).apply()
    }

    fun setAudioBitrate(value: AudioBitratePreference) {
        preferences.edit().putInt("audio_bitrate", value.kbps).apply()
    }
}
