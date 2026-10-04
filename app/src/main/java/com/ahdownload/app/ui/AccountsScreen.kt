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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import com.ahdownload.app.data.WebViewSessionBridge
import com.ahdownload.app.data.WebViewMediaSnapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import com.ahdownload.app.diagnostics.AppLogger
import com.ahdownload.app.ui.theme.AHGradientButton

private data class PlatformAccount(val key:String,val name:String,val url:String)

private val accounts = listOf(
    PlatformAccount("youtube","YouTube","https://www.youtube.com/"),
    PlatformAccount("instagram","Instagram","https://www.instagram.com/"),
    PlatformAccount("facebook","Facebook","https://www.facebook.com/")
)

private const val VERIFY_INTERVAL_MS = 15 * 60 * 1000L

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AccountsScreen() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<PlatformAccount?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences("ahdownload_accounts", android.content.Context.MODE_PRIVATE)
    }

    fun localSessionExists(account: PlatformAccount): Boolean =
        hasAuthenticatedSession(
            account.key,
            CookieManager.getInstance().getCookie(account.url).orEmpty()
        )

    fun lastVerified(account: PlatformAccount): Long =
        prefs.getLong("last_verified_\${account.key}", 0L)

    fun markVerified(account: PlatformAccount) {
        prefs.edit().putLong("last_verified_\${account.key}", System.currentTimeMillis()).apply()
    }

    fun verifyInBackground(account: PlatformAccount) {
        if (verifying != null) return
        scope.launch {
            verifying = account.key
            loginError = null
            AppLogger.info(context, "account.verify_start", "platform=" + account.key)
            runCatching {
                CookieManager.getInstance().flush()
                val cookies = CookieManager.getInstance().getCookie(account.url).orEmpty()
                if (!hasAuthenticatedSession(account.key, cookies)) {
                    throw IllegalStateException("AUTH_SESSION_MISSING")
                }
                WebViewSessionBridge(context).snapshotFor(
                    account.url,
                    timeoutMs = 8_000L,
                    forceFresh = false
                )
            }.onSuccess { snapshot ->
                if (snapshot.authenticated) {
                    markVerified(account)
                    AppLogger.info(context, "account.verify_success", "platform=" + account.key)
                } else {
                    AppLogger.error(
                        context,
                        "account.verify_not_confirmed",
                        IllegalStateException("AUTH_SESSION_NOT_VERIFIED"),
                        "platform=" + account.key
                    )
                    loginError = "الجلسة موجودة محلياً، لكن تعذر تأكيدها الآن. يمكنك المتابعة والمحاولة لاحقاً."
                }
                refresh++
            }.onFailure { failure ->
                AppLogger.error(
                    context,
                    "account.verify_failed",
                    failure,
                    "platform=" + account.key
                )
                if (!localSessionExists(account)) {
                    loginError = "انتهت الجلسة أو لم تعد متاحة. أعد تسجيل الدخول."
                }
                refresh++
            }
            verifying = null
        }
    }

    LaunchedEffect(Unit) {
        val youtube = accounts.firstOrNull { it.key == "youtube" } ?: return@LaunchedEffect
        if (localSessionExists(youtube) &&
            System.currentTimeMillis() - lastVerified(youtube) > VERIFY_INTERVAL_MS
        ) {
            verifyInBackground(youtube)
        }
    }

    if (selected != null) {
        val account = selected!!
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(account.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = verifying == null,
                    onClick = {
                        scope.launch {
                            verifying = account.key
                            loginError = null
                            AppLogger.info(context, "account.session_verify_start", "platform=" + account.key)
                            runCatching {
                                CookieManager.getInstance().flush()
                                val cookies = CookieManager.getInstance().getCookie(account.url).orEmpty()
                                if (hasAuthenticatedSession(account.key, cookies)) {
                                    WebViewMediaSnapshot(cookies, null, emptyList(), true)
                                } else {
                                    WebViewSessionBridge(context).snapshotFor(
                                        account.url,
                                        timeoutMs = 15_000L,
                                        forceFresh = true
                                    )
                                }
                            }.onSuccess { snapshot ->
                                if (snapshot.authenticated) {
                                    markVerified(account)
                                    AppLogger.info(context, "account.session_saved", "platform=" + account.key)
                                    selected = null
                                    refresh++
                                } else {
                                    AppLogger.error(
                                        context,
                                        "account.session_not_verified",
                                        IllegalStateException("AUTH_SESSION_NOT_VERIFIED"),
                                        "platform=" + account.key
                                    )
                                    loginError = "لم يتم التحقق من جلسة تسجيل الدخول. أكمل تسجيل الدخول داخل الصفحة ثم اضغط تم مرة أخرى."
                                }
                            }.onFailure { failure ->
                                AppLogger.error(
                                    context,
                                    "account.session_verification_failed",
                                    failure,
                                    "platform=" + account.key
                                )
                                loginError = "تعذر فحص جلسة الحساب. أعد المحاولة."
                            }
                            verifying = null
                        }
                    }
                ) { Text(if (verifying == account.key) "جارٍ التحقق..." else "تم") }
            }
            loginError?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
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
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        loadUrl(account.url)
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
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("الحسابات", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "اربط حسابك مرة واحدة، وسيحاول AHDownload استخدام الجلسة المحلية مباشرة قبل فتح صفحة تسجيل الدخول.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "لا تحتاج إلى تسجيل الدخول لتنزيل الروابط العامة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        accounts.forEach { account ->
            item(key = account.key) {
                val connected = remember(refresh) { localSessionExists(account) }
                val last = remember(refresh) { lastVerified(account) }
                val isVerifying = verifying == account.key
                val verificationFresh = last > 0L && System.currentTimeMillis() - last <= VERIFY_INTERVAL_MS

                Card(shape = RoundedCornerShape(20.dp)) {
                    Column {
                        ListItem(
                            leadingContent = {
                                val icon = when (account.key) {
                                    "youtube" -> Icons.Default.PlayCircle
                                    "instagram" -> Icons.Default.PhotoCamera
                                    else -> Icons.Default.Public
                                }
                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            headlineContent = {
                                Text(account.name, fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = {
                                Text(
                                    when {
                                        isVerifying -> "جارٍ اختبار الجلسة في الخلفية…"
                                        connected && verificationFresh -> "متصل • تم التحقق مؤخراً"
                                        connected -> "جلسة محفوظة • تحتاج اختبار اتصال"
                                        else -> "غير متصل • تسجيل الدخول اختياري للمحتوى العام"
                                    }
                                )
                            },
                            trailingContent = {
                                if (connected) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            if (verificationFresh) Icons.Default.Verified else Icons.Default.AccessTime,
                                            contentDescription = "متصل",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.graphicsLayer {
                                                val scale = 1.02f
                                                scaleX = scale
                                                scaleY = scale
                                            }
                                        )
                                        IconButton(
                                            enabled = !isVerifying,
                                            onClick = { verifyInBackground(account) }
                                        ) {
                                            if (isVerifying) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Icon(Icons.Default.Refresh, contentDescription = "اختبار الاتصال")
                                            }
                                        }
                                    }
                                } else {
                                    AHGradientButton(
                                        onClick = {
                                            AppLogger.info(context, "account.login_start", "platform=" + account.key)
                                            loginError = null
                                            selected = account
                                        }
                                    ) {
                                        Icon(Icons.Default.Login, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("تسجيل الدخول")
                                    }
                                }
                            }
                        )
                        if (connected) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    when {
                                        isVerifying -> Icons.Default.CloudDone
                                        verificationFresh -> Icons.Default.CheckCircle
                                        else -> Icons.Default.AccessTime
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when {
                                        isVerifying -> "التحقق يتم في الخلفية دون تعطيل الشاشة."
                                        verificationFresh -> "آخر تحقق: " + formatRelativeVerification(last)
                                        else -> "الجلسة محفوظة على الجهاز ولم تُثبت حديثاً."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!isVerifying) {
                                    TextButton(onClick = { verifyInBackground(account) }) {
                                        Text("اختبار الاتصال")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        loginError?.let { message ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.ErrorOutline, null) },
                        headlineContent = { Text("حالة الحساب") },
                        supportingContent = { Text(message) }
                    )
                }
            }
        }

        item {
            Text(
                "الخصوصية: AHDownload لا يطلب كلمة مرور Google أو المنصات داخل واجهته ولا يسجل الرموز أو ملفات تعريف الارتباط في سجل التشخيص.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        "youtube" -> setOf("SID", "SAPISID", "APISID", "__Secure-3PSID", "LOGIN_INFO").any(names::contains)
        else -> false
    }
}

private fun formatRelativeVerification(timestamp: Long): String {
    val elapsed = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    return when {
        minutes < 1L -> "الآن"
        minutes == 1L -> "منذ دقيقة"
        minutes < 60L -> "منذ " + minutes + " دقائق"
        else -> "منذ " + (minutes / 60L) + " ساعة"
    }
}
