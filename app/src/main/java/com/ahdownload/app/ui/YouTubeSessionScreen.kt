package com.ahdownload.app.ui

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSessionScreen(
    onBack: () -> Unit,
    onReady: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun cookies(): String {
        val manager = CookieManager.getInstance()
        manager.flush()
        return manager.getCookie("https://www.youtube.com/").orEmpty()
    }

    fun hasSession(value: String): Boolean {
        val names = value.split(';')
            .mapNotNull { it.trim().substringBefore('=').takeIf { n -> n.isNotBlank() } }
            .toSet()
        return setOf("SID", "SAPISID", "APISID", "__Secure-3PSID", "LOGIN_INFO").any(names::contains)
    }

    fun refreshConnectionStatus() {
        connected = hasSession(cookies())
    }

    LaunchedEffect(Unit) {
        refreshConnectionStatus()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("تسجيل الدخول إلى YouTube") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                }
            },
            actions = {
                IconButton(enabled = !checking, onClick = {
                    checking = true
                    message = null
                    scope.launch {
                        refreshConnectionStatus()
                        message = if (connected) {
                            "تم العثور على جلسة YouTube محفوظة على الجهاز."
                        } else {
                            "أكمل تسجيل الدخول داخل YouTube ثم أعد الفحص."
                        }
                        checking = false
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "فحص الجلسة")
                }
            }
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (connected) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (connected) "تم تسجيل الدخول" else "تسجيل الدخول مطلوب")
                    Text(
                        if (connected) {
                            "يمكنك الآن متابعة إلى صفحة التنزيل."
                        } else {
                            "سجّل الدخول داخل YouTube في الصفحة أدناه."
                        }
                    )
                }
            }
        }

        message?.let {
            Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        AndroidView(
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false

                    val manager = CookieManager.getInstance()
                    manager.setAcceptCookie(true)
                    manager.setAcceptThirdPartyCookies(this, true)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }

                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            CookieManager.getInstance().flush()
                            refreshConnectionStatus()
                        }
                    }
                    loadUrl("https://www.youtube.com/")
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        FilledTonalButton(
            enabled = connected,
            onClick = {
                if (connected) {
                    onReady()
                } else {
                    message = "أكمل تسجيل الدخول داخل YouTube ثم اضغط فحص الجلسة."
                }
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            Icon(Icons.Default.Login, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (connected) "متابعة إلى التنزيل" else "أكمل تسجيل الدخول أولاً")
        }
    }
}
