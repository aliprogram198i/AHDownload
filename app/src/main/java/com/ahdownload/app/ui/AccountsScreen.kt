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
import androidx.compose.material.icons.filled.Warning
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
    var loginError by remember { mutableStateOf<String?>(null) }

    if (selected != null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selected!!.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    CookieManager.getInstance().flush()
                    val cookies = CookieManager.getInstance().getCookie(selected!!.url).orEmpty()
                    val authenticated = hasAuthenticatedSession(selected!!.key, cookies)
                    if (authenticated) {
                        AppLogger.info(context, "account.session_saved", "platform=" + selected!!.key)
                        loginError = null
                        selected = null
                        refresh++
                    } else {
                        AppLogger.error(
                            context,
                            "account.session_not_verified",
                            IllegalStateException("AUTH_SESSION_NOT_VERIFIED"),
                            "platform=" + selected!!.key
                        )
                        loginError = "لم يتم التحقق من جلسة تسجيل الدخول. أكمل تسجيل الدخول داخل الصفحة ثم اضغط تم."
                    }
                }) { Text("تم") }
            }
            loginError?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Warning, null) },
                        headlineContent = { Text("تعذر التحقق من الحساب") },
                        supportingContent = { Text(it) }
                    )
                }
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
                    hasAuthenticatedSession(
                        account.key,
                        CookieManager.getInstance().getCookie(account.url).orEmpty()
                    )
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


private fun hasAuthenticatedSession(platform: String, cookies: String): Boolean {
    val names = cookies.split(';')
        .mapNotNull { part ->
            part.trim().substringBefore('=').takeIf { it.isNotBlank() }
        }
        .toSet()

    return when (platform) {
        "instagram" -> "sessionid" in names
        "facebook" -> "c_user" in names && "xs" in names
        "youtube" -> setOf("SID", "SAPISID", "APISID").any(names::contains)
        else -> false
    }
}
