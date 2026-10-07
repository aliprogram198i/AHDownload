package com.ahdownload.app.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.AHBottomNavDestination
import com.ahdownload.core.designsystem.AHBottomNavigationBar
import com.ahdownload.core.designsystem.rememberUiTraceContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    store: DownloadLocationStore,
    onPickDownloadFolder: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenUiDiagnostics: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateDownloads: () -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val location by store.location.collectAsState()
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var folderError by remember { mutableStateOf<String?>(null) }
    val uiContext = rememberUiTraceContext()

    LaunchedEffect(location, showFolderDialog, folderError) {
        uiTraceLogger.snapshot(
            screen = "SETTINGS",
            component = "SettingsScreen",
            components = buildList {
                add("topbar")
                add("download_location")
                add("diagnostics")
                add("about")
                add("bottom_navigation")
                if (showFolderDialog) add("create_folder_dialog")
                if (folderError != null) add("folder_error")
            }.joinToString(","),
            stateSummary = "custom=" + location.isCustom +
                ";accessible=" + location.isAccessible +
                ";dialog=" + showFolderDialog +
                ";error=" + (folderError != null),
            context = uiContext,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            uiTraceLogger.interaction("SETTINGS", "back_button", "back")
                            onBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
        bottomBar = {
            AHBottomNavigationBar(
                selected = AHBottomNavDestination.SETTINGS,
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
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 760.dp)
                    .align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("التفضيلات", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "تحكم في مكان حفظ الملفات وأدوات التشخيص دون ازدحام الصفحة الرئيسية.",
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, contentDescription = null)
                            Text(
                                "مكان التنزيل",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                        Text(
                            location.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when {
                                !location.isAccessible -> "المجلد غير متاح — اختر مجلدًا آخر."
                                location.isCustom -> "المجلد المخصص متاح للقراءة والكتابة."
                                else -> "المجلد الافتراضي للتطبيق فعال."
                            },
                            color = if (location.isAccessible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Button(
                            onClick = {
                                uiTraceLogger.interaction("SETTINGS", "change_folder", "pick_folder")
                                onPickDownloadFolder()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("تغيير مجلد التنزيل")
                        }
                        if (location.isCustom) {
                            OutlinedButton(
                                onClick = {
                                    uiTraceLogger.interaction("SETTINGS", "reset_folder", "reset_default")
                                    store.resetToDefault()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("استخدام المجلد الافتراضي")
                            }
                            OutlinedButton(
                                onClick = {
                                    folderError = null
                                    folderName = ""
                                    showFolderDialog = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                                Text("إنشاء مجلد داخل المجلد المحدد", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        Text(
                            "الفيديوهات والصور والصوتيات تُنشر في مجلدات Android المناسبة عند توفرها.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(
                                "التنزيل في الخلفية",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "يستمر التنزيل عبر WorkManager مع إشعار حالة النظام ويمكن إيقافه أو استئنافه من سجل التنزيلات.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.BugReport, contentDescription = null)
                            Text(
                                "التشخيص المتقدم",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                        Text(
                            "أدوات للمشاكل الفنية فقط. لا تظهر في الصفحة الرئيسية.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                uiTraceLogger.interaction("SETTINGS", "diagnostics_button", "open_diagnostics")
                                onOpenDiagnostics()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("سجل الأخطاء")
                        }
                        OutlinedButton(
                            onClick = {
                                uiTraceLogger.interaction("SETTINGS", "ui_diagnostics_button", "open_ui_diagnostics")
                                onOpenUiDiagnostics()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("تشخيص الواجهة")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("AHDownload", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "تنزيل محلي مع تحقق من المصادر وحفظ آمن عبر واجهات Android الحديثة.",
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
                    Text(
                        it,
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val created = runCatching {
                            SelectedDirectoryStorage.createFolder(
                                context = context,
                                treeUri = location.uri!!,
                                folderName = folderName,
                            )
                        }.getOrNull()
                        if (created != null) {
                            folderName = ""
                            showFolderDialog = false
                        } else {
                            folderError = "تعذر إنشاء المجلد في هذا المسار."
                        }
                    },
                    enabled = folderName.isNotBlank(),
                ) {
                    Text("إنشاء")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) {
                    Text("إلغاء")
                }
            },
        )
    }
}
