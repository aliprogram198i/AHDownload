package com.ahdownload.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ahdownload.app.AHDownloadApplication
import com.ahdownload.app.auth.GoogleAccountSession
import com.ahdownload.app.auth.GoogleAuthManager
import com.ahdownload.app.auth.GoogleAuthResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleAccountScreen(
    onBack: () -> Unit,
    onAuthenticated: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val manager = remember {
        GoogleAuthManager(
            context,
            (context.applicationContext as AHDownloadApplication).diagnosticLogger,
        )
    }

    var session by remember { mutableStateOf<GoogleAccountSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        session = manager.existingSession()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("حساب Google") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("اختر حساب Google الموجود على الجهاز", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(
                "سيظهر لك مدير حسابات Google النظامي. لا نطلب كلمة مرور Google من AHDownload.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))

            session?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("تم ربط الحساب", style = MaterialTheme.typography.titleMedium)
                        Text(it.email)
                        it.displayName?.let { name -> Text(name) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy,
                    onClick = onAuthenticated,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("متابعة إلى التنزيل")
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        manager.signOut()
                        session = null
                        message = "تم فصل الحساب من AHDownload."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("فصل الحساب")
                }
            } ?: run {
                Button(
                    enabled = !busy && activity != null,
                    onClick = {
                        val host = activity ?: return@Button
                        busy = true
                        message = null
                        scope.launch {
                            when (val result = manager.signIn(host)) {
                                is GoogleAuthResult.Success -> {
                                    session = result.session
                                    busy = false
                                    onAuthenticated()
                                }
                                GoogleAuthResult.ConfigurationMissing -> {
                                    busy = false
                                    message = "يلزم ضبط Google Web Client ID للمشروع قبل تفعيل تسجيل الدخول."
                                }
                                is GoogleAuthResult.Cancelled -> {
                                    busy = false
                                    message = result.message
                                }
                                is GoogleAuthResult.Failure -> {
                                    busy = false
                                    message = result.message
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Login, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text(if (busy) "جاري فتح حسابات Google…" else "اختيار حساب Google")
                }
            }

            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
