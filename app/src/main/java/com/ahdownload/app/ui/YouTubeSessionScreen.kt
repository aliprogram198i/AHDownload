package com.ahdownload.app.ui

import android.annotation.SuppressLint
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSessionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun cookies(): String = CookieManager.getInstance()
        .getCookie("https://www.youtube.com/")
        .orEmpty()

    fun hasSession(value: String): Boolean {
        val names = value.split(';')
            .mapNotNull { it.trim().substringBefore('=').takeIf { n -> n.isNotBlank() } }
            .toSet()
        return setOf("SID", "SAPISID", "APISID", "__Secure-3PSID", "LOGIN_INFO").any(names::contains)
    }

    LaunchedEffect(Unit) {
        connected = hasSession(cookies())
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("جلسة YouTube") },
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
                        CookieManager.getInstance().flush()
                        connected = hasSession(cookies())
                        message = if (connected) "تم العثور على جلسة YouTube على الجهاز." else "لم يتم العثور على جلسة مصادق عليها."
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
                    Text(if (connected) "YouTube متصل" else "YouTube غير متصل")
                    Text(
                        if (connected) "سيستخدم المحلل الجلسة المحلية عند الحاجة."
                        else "سجّل الدخول داخل الصفحة ثم اضغط فحص الجلسة."
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
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
                    loadUrl("https://www.youtube.com/")
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        if (!connected) {
            FilledTonalButton(
                onClick = { message = "سجّل الدخول في صفحة YouTube أعلاه، ثم استخدم زر فحص الجلسة." },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                Icon(Icons.Default.Login, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("تم تسجيل الدخول")
            }
        }
    }
}
