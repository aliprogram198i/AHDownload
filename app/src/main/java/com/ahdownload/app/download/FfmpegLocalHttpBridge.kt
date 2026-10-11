package com.ahdownload.app.download

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Loopback-only HTTP bridge for FFmpeg builds whose TLS protocol is not enabled.
 *
 * All upstream requests still use OkHttp's TLS implementation. The local listener binds
 * exclusively to 127.0.0.1, scopes cookies to the original cookie host, follows redirects
 * with an explicit per-hop cookie check, and rewrites HLS/DASH child URLs back through the
 * same bridge. No URL, cookie, or request header is written to diagnostics.
 */
internal class FfmpegLocalHttpBridge(
    private val sourceUrl: String,
    requestHeaders: Map<String, String>,
    cookie: String?,
) : Closeable {
    private val safeHeaders = requestHeaders.filterKeys(::isSafeHeader)
    private val cookieHost = runCatching { URI(sourceUrl).host.orEmpty().lowercase().removePrefix("www.") }
        .getOrDefault("")
    @Volatile private var cookieValue: String? = cookie?.takeIf { it.isNotBlank() }
    private val server = ServerSocket()
    private val workers = Executors.newFixedThreadPool(MAX_WORKERS)
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    @Volatile private var closed = false
    @Volatile private var loopbackOrigin: String? = null
    private var acceptThread: Thread? = null

    fun start(): String {
        require(isSafeRemoteUrl(sourceUrl)) { "Invalid HLS/DASH source URL" }
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), BACKLOG)
        loopbackOrigin = "http://127.0.0.1:${server.localPort}"
        acceptThread = thread(name = "ahdownload-ffmpeg-http-bridge", isDaemon = true) {
            while (!closed) {
                try {
                    val socket = server.accept()
                    try {
                        workers.execute { handleClient(socket) }
                    } catch (_: Throwable) {
                        runCatching { socket.close() }
                    }
                } catch (_: IOException) {
                    if (!closed) {
                        // The caller owns the download failure. Do not log URLs or request data.
                        close()
                    }
                }
            }
        }
        return routeForUrl(sourceUrl)
    }

    private fun handleClient(socket: Socket) {
        socket.use { connection ->
            try {
                connection.soTimeout = SOCKET_TIMEOUT_MILLIS
                val input = BufferedInputStream(connection.getInputStream())
                val output = BufferedOutputStream(connection.getOutputStream())
                val requestLine = readLine(input, MAX_LINE_BYTES) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeSimpleResponse(output, 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase(Locale.ROOT)
                if (method !in setOf("GET", "HEAD")) {
                    writeSimpleResponse(output, 405, "Method Not Allowed")
                    return
                }
                val localTarget = parts[1]
                val requestHeaders = linkedMapOf<String, String>()
                var totalHeaderBytes = 0
                while (true) {
                    val line = readLine(input, MAX_LINE_BYTES) ?: break
                    if (line.isEmpty()) break
                    totalHeaderBytes += line.length
                    if (totalHeaderBytes > MAX_HEADER_BYTES) {
                        writeSimpleResponse(output, 431, "Request Header Fields Too Large")
                        return
                    }
                    val split = line.indexOf(':')
                    if (split <= 0) continue
                    val name = line.substring(0, split).trim().lowercase(Locale.ROOT)
                    val value = line.substring(split + 1).trim()
                    if (name.isNotBlank() && value.isNotBlank()) requestHeaders[name] = value
                }

                val upstreamUrl = resolveLocalTarget(localTarget)
                if (upstreamUrl == null || !isSafeRemoteUrl(upstreamUrl)) {
                    writeSimpleResponse(output, 400, "Bad Request")
                    return
                }
                val rangeHeader = requestHeaders["range"]
                val fetched = try {
                    fetchUpstream(upstreamUrl, method, rangeHeader)
                } catch (_: Throwable) {
                    writeSimpleResponse(output, 502, "Bad Gateway")
                    return
                }
                fetched.response.use { response ->
                    val body = response.body
                    if (body == null || method == "HEAD") {
                        writeResponseHeaders(
                            output = output,
                            status = response.code,
                            message = safeReason(response.code, response.message),
                            contentType = response.header("Content-Type"),
                            contentLength = body?.contentLength() ?: 0L,
                            response = response,
                            chunked = false,
                        )
                        output.flush()
                        return
                    }

                    val bodyPath = runCatching { URI(fetched.finalUrl).path.orEmpty().lowercase() }.getOrDefault("")
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    val isHlsCandidate = response.code in 200..299 && (
                        bodyPath.endsWith(".m3u8") ||
                            contentType.contains("mpegurl") ||
                            contentType.contains("vnd.apple.mpegurl")
                    )
                    val isDashCandidate = response.code in 200..299 && (
                        bodyPath.endsWith(".mpd") || contentType.contains("dash+xml")
                    )
                    if (isHlsCandidate || isDashCandidate) {
                        val text = runCatching { body.string() }.getOrElse {
                            writeSimpleResponse(output, 502, "Bad Gateway")
                            return
                        }
                        val rewritten = when {
                            isHlsCandidate && text.trimStart().startsWith("#EXTM3U") ->
                                FfmpegManifestProxyRewriter.rewriteHls(
                                    text = text,
                                    upstreamUrl = fetched.finalUrl,
                                    localOrigin = loopbackOrigin.orEmpty(),
                                    routeForUrl = ::routeForUrl,
                                )
                            isDashCandidate && text.contains("<MPD", ignoreCase = true) ->
                                FfmpegManifestProxyRewriter.rewriteDash(
                                    text = text,
                                    upstreamUrl = fetched.finalUrl,
                                    localOrigin = loopbackOrigin.orEmpty(),
                                    routeForUrl = ::routeForUrl,
                                    routeForBase = ::routeForBase,
                                )
                            else -> text
                        }
                        val bytes = rewritten.toByteArray(Charsets.UTF_8)
                        writeResponseHeaders(
                            output = output,
                            status = response.code,
                            message = safeReason(response.code, response.message),
                            contentType = if (isHlsCandidate) "application/vnd.apple.mpegurl" else "application/dash+xml",
                            contentLength = bytes.size.toLong(),
                            response = response,
                            chunked = false,
                        )
                        output.write(bytes)
                        output.flush()
                        return
                    }

                    val upstreamLength = body.contentLength()
                    writeResponseHeaders(
                        output = output,
                        status = response.code,
                        message = safeReason(response.code, response.message),
                        contentType = response.header("Content-Type"),
                        contentLength = upstreamLength,
                        response = response,
                        chunked = upstreamLength < 0L,
                    )
                    val stream = body.byteStream()
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    if (upstreamLength >= 0L) {
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    } else {
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            output.write(Integer.toHexString(count).toByteArray(Charsets.US_ASCII))
                            output.write(CRLF)
                            output.write(buffer, 0, count)
                            output.write(CRLF)
                        }
                        output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    }
                    output.flush()
                }
            } catch (_: Throwable) {
                // Connection loss during a cancelled FFmpeg process is expected. Never log
                // exception details because URLs may contain signed query values.
            }
        }
    }

    private fun fetchUpstream(url: String, method: String, rangeHeader: String?): FetchedResponse {
        var currentUrl = url
        for (redirectCount in 0..MAX_REDIRECTS) {
            if (!isSafeRemoteUrl(currentUrl)) throw IOException("Rejected upstream URL")
            val currentHost = URI(currentUrl).host.orEmpty().lowercase()
            val request = Request.Builder().url(currentUrl)
            safeHeaders.forEach { (name, value) ->
                if (isSafeHeader(name)) request.header(name, value)
            }
            rangeHeader?.let { request.header("Range", it) }
            val activeCookie = cookieValue
            if (!activeCookie.isNullOrBlank() && isCookieHostAllowed(currentHost)) {
                request.header("Cookie", activeCookie)
            }
            if (method == "HEAD") request.head() else request.get()
            val response = client.newCall(request.build()).execute()
            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank() || redirectCount == MAX_REDIRECTS) {
                    throw IOException("Too many or invalid upstream redirects")
                }
                currentUrl = URI(currentUrl).resolve(location).toString()
                continue
            }
            return FetchedResponse(response, currentUrl)
        }
        throw IOException("Too many upstream redirects")
    }

    private fun resolveLocalTarget(target: String): String? {
        val uri = runCatching { URI(target) }.getOrNull() ?: return null
        val path = uri.rawPath.orEmpty()
        if (path.startsWith("/u/")) {
            val token = path.removePrefix("/u/").takeIf { it.isNotBlank() && '/' !in it } ?: return null
            return decodeToken(token)
        }
        if (path.startsWith("/b/")) {
            val suffixStart = path.indexOf('/', 3)
            val token = if (suffixStart < 0) path.substring(3) else path.substring(3, suffixStart)
            if (token.isBlank()) return null
            val base = decodeToken(token) ?: return null
            val suffix = if (suffixStart < 0) "" else path.substring(suffixStart + 1)
            if (suffix.isBlank() && uri.rawQuery.isNullOrBlank()) return base
            val baseUri = runCatching { URI(base) }.getOrNull() ?: return null
            val relative = suffix + uri.rawQuery?.let { "?$it" }.orEmpty()
            val resolved = runCatching { baseUri.resolve(relative).toString() }.getOrNull() ?: return null
            if (uri.rawQuery == null && baseUri.rawQuery != null && suffix.isNotBlank()) {
                val withoutQuery = resolved.substringBefore('?')
                return withoutQuery + "?" + baseUri.rawQuery
            }
            return resolved
        }
        return null
    }

    private fun routeForUrl(url: String): String =
        loopbackOrigin.orEmpty() + "/u/" + encodeToken(url)

    private fun routeForBase(url: String): String =
        loopbackOrigin.orEmpty() + "/b/" + encodeToken(url)

    private fun encodeToken(value: String): String =
        Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun decodeToken(value: String): String? =
        runCatching {
            String(
                Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
        }.getOrNull()

    private fun isCookieHostAllowed(upstreamHost: String): Boolean {
        val normalized = cookieHost.lowercase().removePrefix("www.")
        val target = upstreamHost.lowercase().removePrefix("www.")
        return normalized.isNotBlank() && (target == normalized || target.endsWith(".$normalized"))
    }

    private fun writeResponseHeaders(
        output: BufferedOutputStream,
        status: Int,
        message: String,
        contentType: String?,
        contentLength: Long,
        response: Response,
        chunked: Boolean,
    ) {
        output.write("HTTP/1.1 $status $message\r\n".toByteArray(Charsets.US_ASCII))
        contentType?.takeIf { it.isNotBlank() }?.let {
            output.write("Content-Type: $it\r\n".toByteArray(Charsets.US_ASCII))
        }
        if (contentLength >= 0L) {
            output.write("Content-Length: $contentLength\r\n".toByteArray(Charsets.US_ASCII))
        }
        if (chunked) output.write("Transfer-Encoding: chunked\r\n".toByteArray(Charsets.US_ASCII))
        for (name in listOf("Content-Range", "Accept-Ranges", "ETag", "Last-Modified", "Cache-Control")) {
            response.header(name)?.takeIf { it.isNotBlank() }?.let { value ->
                output.write("$name: $value\r\n".toByteArray(Charsets.US_ASCII))
            }
        }
        output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun safeReason(status: Int, message: String): String {
        val clean = message.replace(Regex("[\\r\\n]+"), " ").trim().take(80)
        if (clean.isNotBlank()) return clean
        return when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            else -> "Upstream Response"
        }
    }

    private fun writeSimpleResponse(output: BufferedOutputStream, status: Int, reason: String) {
        val body = "$status $reason".toByteArray(Charsets.UTF_8)
        runCatching {
            output.write("HTTP/1.1 $status $reason\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Length: ${body.size}\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cookieValue = null
        runCatching { server.close() }
        workers.shutdownNow()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun isSafeRemoteUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https") || host.isBlank() || uri.userInfo != null) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        val ipv4 = IPV4_REGEX.matches(host)
        if (ipv4) {
            val octets = host.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4) return false
            val first = octets[0]
            val second = octets[1]
            if (first == 0 || first == 10 || first == 127 ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                first >= 224
            ) return false
        }
        if (host.contains(':') && runCatching {
                val address = InetAddress.getByName(host)
                address.isAnyLocalAddress || address.isLoopbackAddress ||
                    address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
            }.getOrDefault(true)
        ) return false
        return true
    }

    private fun isSafeHeader(name: String): Boolean =
        name.equals("User-Agent", true) ||
            name.equals("Referer", true) ||
            name.equals("Origin", true) ||
            name.equals("Accept", true) ||
            name.equals("Accept-Language", true) ||
            name.startsWith("Sec-Fetch-", true) ||
            name.startsWith("Sec-CH-UA", true) ||
            name.equals("X-Goog-Visitor-Id", true) ||
            name.equals("X-YouTube-Client-Name", true) ||
            name.equals("X-YouTube-Client-Version", true)

    private fun readLine(input: BufferedInputStream, maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream()
        var sawAny = false
        while (bytes.size() <= maxBytes) {
            val value = input.read()
            if (value < 0) return if (sawAny) bytes.toString("US-ASCII") else null
            sawAny = true
            if (value == 10) break
            if (value != 13) bytes.write(value)
        }
        if (bytes.size() > maxBytes) throw IOException("HTTP request line too large")
        return bytes.toString("US-ASCII")
    }

    private data class FetchedResponse(val response: Response, val finalUrl: String)

    private companion object {
        const val MAX_WORKERS = 6
        const val BACKLOG = 24
        const val SOCKET_TIMEOUT_MILLIS = 60_000
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADER_BYTES = 32 * 1024
        const val STREAM_BUFFER_BYTES = 32 * 1024
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 60L
        const val CALL_TIMEOUT_SECONDS = 120L
        const val MAX_REDIRECTS = 8
        val CRLF = byteArrayOf(13, 10)
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val IPV4_REGEX = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    }
}

/** Pure manifest URL rewriting. All actual remote data access remains in OkHttp above. */
internal object FfmpegManifestProxyRewriter {
    private val URI_ATTRIBUTE = Regex("""(?i)(URI=)(["'])([^"']+)\2""")
    private val BASE_URL_TAG = Regex("""(?is)(<BaseURL\b[^>]*>)(.*?)(</BaseURL>)""")
    private val DASH_URL_ATTRIBUTE = Regex("""(?is)(\b(?:media|initialization|sourceURL|index|href)=)(["'])(https?://[^"']+)\2""")

    fun rewriteHls(
        text: String,
        upstreamUrl: String,
        localOrigin: String,
        routeForUrl: (String) -> String,
    ): String {
        val resolvedLines = text.split('\n').map { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val resolved = resolve(upstreamUrl, trimmed)
                if (resolved != null) {
                    line.replace(trimmed, proxyUrl(resolved, localOrigin, routeForUrl))
                } else {
                    line
                }
            } else {
                URI_ATTRIBUTE.replace(line) { match ->
                    val raw = match.groupValues[3]
                    val resolved = resolve(upstreamUrl, raw)
                    if (resolved == null) match.value
                    else match.groupValues[1] + match.groupValues[2] +
                        proxyUrl(resolved, localOrigin, routeForUrl) + match.groupValues[2]
                }
            }
        }
        return resolvedLines.joinToString("\n")
    }

    fun rewriteDash(
        text: String,
        upstreamUrl: String,
        localOrigin: String,
        routeForUrl: (String) -> String,
        routeForBase: (String) -> String,
    ): String {
        var rewritten = BASE_URL_TAG.replace(text) { match ->
            val raw = match.groupValues[2].trim()
            if (raw.isBlank()) match.value
            else {
                val resolved = resolve(upstreamUrl, raw)
                if (resolved == null) match.value
                else {
                    val route = if (resolved.substringBefore('?').endsWith('/')) routeForBase(resolved) else
                        proxyUrl(resolved, localOrigin, routeForUrl)
                    match.groupValues[1] + route + match.groupValues[3]
                }
            }
        }
        rewritten = DASH_URL_ATTRIBUTE.replace(rewritten) { match ->
            val raw = match.groupValues[3].replace("&amp;", "&")
            val routed = if (raw.contains('$')) {
                val variableIndex = raw.indexOf('$')
                val slash = raw.lastIndexOf('/', variableIndex)
                if (slash > raw.indexOf("://") + 2) {
                    val base = raw.substring(0, slash + 1)
                    val suffix = raw.substring(slash + 1)
                    if (base.startsWith(localOrigin)) raw else routeForBase(base) + suffix
                } else {
                    proxyUrl(raw, localOrigin, routeForUrl)
                }
            } else {
                proxyUrl(raw, localOrigin, routeForUrl)
            }
            match.groupValues[1] + match.groupValues[2] + routed + match.groupValues[2]
        }
        return rewritten
    }

    private fun proxyUrl(url: String, localOrigin: String, routeForUrl: (String) -> String): String =
        if (url.startsWith(localOrigin + "/", ignoreCase = true)) url else routeForUrl(url)

    private fun resolve(base: String, value: String): String? = runCatching {
        val candidate = URI(base).resolve(value.replace("&amp;", "&")).toString()
        val uri = URI(candidate)
        if (uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            candidate
        } else {
            null
        }
    }.getOrNull()
}
