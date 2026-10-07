package com.ahdownload.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import com.ahdownload.core.common.UiTraceLogger
import com.ahdownload.core.common.interaction
import com.ahdownload.core.common.snapshot
import com.ahdownload.core.designsystem.rememberUiTraceContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    store: DownloadLocationStore,
    onPickDownloadFolder: () -> Unit,
    onBack: () -> Unit,
    uiTraceLogger: UiTraceLogger,
) {
    val context = LocalContext.current
    val location by store.location.collectAsState()
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var folderError by remember { mutableStateOf<String?>(null) }

    val uiContext = rememberUiTraceContext()
    LaunchedEffect(location, showFolderDialog, folderError) {
        val components = buildList {
            add("topbar")
            add("storage_card")
            add("change_folder_button")
            if (location.isCustom) {
                add("reset_default_button")
                add("create_folder_button")
            }
            if (showFolderDialog) add("create_folder_dialog")
            if (folderError != null) add("folder_error")
        }.joinToString(",")
        uiTraceLogger.snapshot(
            screen = "SETTINGS",
            component = "SettingsScreen",
            components = components,
            stateSummary = "display_name=" + location.displayName +
                ";custom=" + location.isCustom +
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
                    IconButton(onClick = { uiTraceLogger.interaction("SETTINGS", "back_button", "back"); onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("التخزين والتنزيل", style = MaterialTheme.typography.headlineSmall)
            Text(
                "حدد المكان الذي تحفظ فيه الوسائط. يستخدم التطبيق صلاحية النظام للمجلد المحدد فقط، ولا يحتاج إلى وصول شامل للتخزين.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null)
                    Text("مسار تنزيل الوسائط", style = MaterialTheme.typography.titleMedium)
                    Text(location.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            !location.isAccessible -> "غير متاح — أعد اختيار المجلد"
                            location.isCustom -> "✓ صلاحية القراءة والكتابة فعالة"
                            else -> "✓ المسار الافتراضي للتطبيق فعال"
                        },
                        color = if (location.isAccessible) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { uiTraceLogger.interaction("SETTINGS", "change_folder_button", "pick_folder"); onPickDownloadFolder() }, modifier = Modifier.fillMaxWidth()) {
                        Text("تغيير مسار التنزيل")
                    }
                    if (location.isCustom) {
                        OutlinedButton(onClick = { uiTraceLogger.interaction("SETTINGS", "reset_default_button", "reset"); store.resetToDefault() }, modifier = Modifier.fillMaxWidth()) {
                            Text("العودة إلى المسار الافتراضي")
                        }
                        OutlinedButton(
                            onClick = { uiTraceLogger.interaction("SETTINGS", "create_folder_button", "open_dialog"); folderError = null; showFolderDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                            Text("إنشاء مجلد داخل المسار")
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
                        onValueChange = { folderName = it; folderError = null },
                        label = { Text("اسم المجلد") },
                        singleLine = true,
                        isError = folderError != null,
                    )
                    folderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
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
                }) { Text("إنشاء") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showFolderDialog = false }) { Text("إلغاء") }
            },
        )
    }
}
