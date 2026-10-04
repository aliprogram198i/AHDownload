package com.ahdownload.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ahdownload.app.diagnostics.AppLogger

private data class PlatformAccount(val key:String,val name:String,val url:String)

private val accounts = listOf(
    PlatformAccount("youtube","YouTube","https://www.youtube.com/"),
    PlatformAccount("instagram","Instagram","https://www.instagram.com/"),
    PlatformAccount("facebook","Facebook","https://www.facebook.com/")
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AccountsScreen() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<PlatformAccount?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    if (selected != null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selected!!.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    CookieManager.getInstance().flush()
                    AppLogger.info(context, "account.session_saved", "platform=" + selected!!.key)
                    selected = null
                    refresh++
                }) { Text("تم") }
            }
            AndroidView(
                factory = {
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        loadUrl(selected!!.url)
                    }
                },
                update = { },
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("الحسابات", style = MaterialTheme.typography.headlineMedium)
            Text(
                "اربط جلسة تسجيل الدخول المحلية لمساعدة المحلل في الوصول إلى المحتوى الذي يسمح به حسابك. " +
                    "بيانات الدخول لا تغادر الجهاز ولا يتم إرسالها إلى خادم AHDownload.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        accounts.forEach { account ->
            item(key = account.key) {
                val connected = remember(refresh) {
                    !CookieManager.getInstance().getCookie(account.url).isNullOrBlank()
                }
                Card(shape = RoundedCornerShape(20.dp)) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.AccountCircle, null) },
                        headlineContent = { Text(account.name) },
                        supportingContent = {
                            Text(if (connected) "جلسة موجودة على الجهاز" else "غير متصل")
                        },
                        trailingContent = {
                            if (connected) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "متصل",
                                    tint = MaterialTheme.colorScheme.primary)
                            } else {
                                FilledTonalButton(onClick = {
                                    AppLogger.info(context, "account.login_start", "platform=" + account.key)
                                    selected = account
                                }) {
                                    Icon(Icons.Default.Login, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("تسجيل الدخول")
                                }
                            }
                        }
                    )
                }
            }
        }
        item {
            Text(
                "مهم: هذا ليس تجاوزاً للحماية. يجب أن يكون المحتوى متاحاً لحسابك وبما يتوافق مع شروط المنصة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
