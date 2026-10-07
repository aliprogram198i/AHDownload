package com.ahdownload.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahdownload.app.BuildConfig
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
    activeDownloads: Int = 0,
) {
    val context = LocalContext.current
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
                add("advanced_diagnostics")
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
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("الإعدادات", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "تحكم في مكان حفظ الملفات وأدوات التشخيص بدون تعطيل مسار التنزيل الأساسي.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.Folder, contentDescription = null)
                        Text("مكان التنزيل", style = MaterialTheme.typography.titleMedium)
                        Text(location.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when {
                                !location.isAccessible -> "غير متاح — أعد اختيار المجلد"
                                location.isCustom -> "صلاحية القراءة والكتابة فعالة"
                                else -> "المسار الافتراضي للتطبيق فعال"
                            },
                            color = if (location.isAccessible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
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
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("تشخيص متقدم", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "لا تظهر أدوات التشخيص في الصفحة الرئيسية؛ استخدمها عند فحص مشكلة محددة.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                uiTraceLogger.interaction("SETTINGS", "diagnostics_button", "open_diagnostics")
                                onOpenDiagnostics()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                            Text("سجل الأخطاء")
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
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null)
                        Text("حول AHDownload", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "الإصدار " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "إدارة التنزيلات واختيار المصادر يتمان عبر طبقات التطبيق والتحقق قبل التنفيذ.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
