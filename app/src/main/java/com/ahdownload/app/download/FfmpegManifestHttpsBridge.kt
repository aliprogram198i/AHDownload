package com.ahdownload.app.download

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adapts upstream HTTPS HLS/DASH to loopback HTTP for FFmpeg builds without the
 * native HTTPS protocol. Android's TLS-capable HttpURLConnection fetches remote
 * resources; media bodies stream through and byte-range requests are forwarded.
 */
internal class FfmpegManifestHttpsBridge(
    private val requestHeaders: Map<String, String>,
    private val cookie: String?,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : Closeable {
    private data class Target(val url: String, val forceManifest: Boolean = false)
    private enum class ManifestKind { HLS, DASH }

    private val closed = AtomicBoolean(false)
    private val ids = AtomicInteger(1)
    private val targets = ConcurrentHashMap<String, Target>()
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32)
    }
    private val executor = Executors.newCachedThreadPool { Thread(it, "ahdownload-manifest-proxy").apply { isDaemon = true } }
    private val root = "http://127.0.0.1:${server.localPort}"

    private val acceptThread = Thread({
        while (!closed.get()) {
            try {
                val client = server.accept()
                executor.execute { handleClient(client) }
            } catch (_: SocketException) {
                if (closed.get()) break
            } catch (_: IOException) {
                if (closed.get()) break
            }
        }
    }, "ahdownload-manifest-proxy-accept").apply { isDaemon = true; start() }

    fun manifestUrl(remoteUrl: String): String = addResource(remoteUrl, true)

    /** Used by the runtime smoke test to check an actual HTTPS-to-HTTP bridge. */
    fun resourceUrl(remoteUrl: String): String = addResource(remoteUrl, false)

    private fun addResource(url: String, forceManifest: Boolean): String {
        requireHttpUrl(url)
        val id = ids.getAndIncrement().toString(36)
        targets[id] = Target(url, forceManifest)

        // FFmpeg's HLS demuxer checks segment extensions against an allowlist.
        // Keep a safe extension hint on opaque proxy paths so .ts/.m4s/.mp4
        // segments remain recognizable without disabling that security check.
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val leaf = path.substringAfterLast('/')
        val extension = leaf.substringAfterLast('.', "")
            .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        val suffix = extension?.let { "/resource.$it" }.orEmpty()
        return "$root/resource/$id$suffix"
    }

    private fun addBase(url: String): String {
        requireHttpUrl(url)
        val id = ids.getAndIncrement().toString(36)
        targets[id] = Target(url)
        return "$root/base/$id/"
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1))
            val pieces = (reader.readLine() ?: return).split(' ', limit = 3)
            if (pieces.size < 2) { writeSimpleResponse(client, 400, "Bad Request"); return }
            val method = pieces[0].uppercase()
            if (method != "GET" && method != "HEAD") { writeSimpleResponse(client, 405, "Method Not Allowed"); return }

            val incoming = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim()
                if (name.equals("Range", true) || name.equals("If-Range", true)) incoming[name] = line.substring(colon + 1).trim()
            }

            try {
                val local = URI(pieces[1])
                val parts = local.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
                val id = parts.getOrNull(1)
                val target = id?.let(targets::get)
                val remoteUrl = when {
                    parts.size >= 2 && parts[0] == "resource" -> target?.url
                    parts.size >= 2 && parts[0] == "base" && target != null ->
                        resolveBase(target.url, parts.drop(2).joinToString("/"), local.rawQuery)
                    else -> null
                }
                if (remoteUrl == null) { writeSimpleResponse(client, 404, "Not Found"); return }
                fetch(client, method, remoteUrl, incoming, target?.forceManifest == true)
            } catch (_: Exception) {
                // Never echo signed URLs, query values, or cookies to the native process.
                writeSimpleResponse(client, 502, "Upstream Fetch Failed")
            }
        }
    }

    private fun fetch(
        socket: Socket,
        method: String,
        remoteUrl: String,
        incoming: Map<String, String>,
        forceManifest: Boolean,
    ) {
        requireHttpUrl(remoteUrl)
        val connection = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = method
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Encoding", "identity")
            requestHeaders.forEach { (name, value) -> if (forwardHeader(name)) setRequestProperty(name, value) }
            cookie?.takeIf(String::isNotBlank)?.let { setRequestProperty("Cookie", it) }
            incoming.forEach { (name, value) ->
                if (name.equals("Range", true) || name.equals("If-Range", true)) setRequestProperty(name, value)
            }
        }
        try {
            val status = connection.responseCode
            val body = if (method == "HEAD") null else runCatching {
                if (status >= 400) connection.errorStream else connection.inputStream
            }.getOrNull()
            val type = connection.getHeaderField("Content-Type").orEmpty()
            val finalUrl = connection.url.toString()

            if (status in 200..299 && body != null) {
                val input = BufferedInputStream(body)
                input.mark(SNIFF_BYTES)
                val prefix = ByteArray(SNIFF_BYTES)
                val count = runCatching { input.read(prefix) }.getOrDefault(-1)
                input.reset()
                val sample = if (count > 0) String(prefix, 0, count, StandardCharsets.UTF_8)
                    .trimStart('\uFEFF', ' ', '\t', '\r', '\n') else ""
                val kind = manifestKind(finalUrl, type, sample, forceManifest)
                if (kind != null) {
                    val text = String(readBounded(input), StandardCharsets.UTF_8)
                    val rewritten = when (kind) {
                        ManifestKind.HLS -> rewriteHls(text, finalUrl)
                        ManifestKind.DASH -> rewriteDash(text, finalUrl)
                    }.toByteArray(StandardCharsets.UTF_8)
                    writeHeaders(socket, status, if (kind == ManifestKind.HLS) "application/vnd.apple.mpegurl" else "application/dash+xml", rewritten.size.toLong(), emptyMap())
                    socket.getOutputStream().apply { write(rewritten); flush() }
                } else {
                    writeHeaders(socket, status, type, connection.contentLengthLong, responseHeaders(connection))
                    input.copyTo(socket.getOutputStream(), BUFFER_BYTES)
                    socket.getOutputStream().flush()
                }
            } else {
                writeHeaders(socket, status, type, connection.contentLengthLong, responseHeaders(connection))
                if (method != "HEAD") body?.use { it.copyTo(socket.getOutputStream(), BUFFER_BYTES) }
                socket.getOutputStream().flush()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun manifestKind(url: String, contentType: String, sample: String, force: Boolean): ManifestKind? {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") ||
                contentType.contains("mpegurl", true) || sample.startsWith("#EXTM3U") -> ManifestKind.HLS
            path.endsWith(".mpd") || contentType.contains("dash+xml", true) || MPD_TAG.containsMatchIn(sample) -> ManifestKind.DASH
            force && sample.startsWith("#EXTM3U") -> ManifestKind.HLS
            force && MPD_TAG.containsMatchIn(sample) -> ManifestKind.DASH
            else -> null
        }
    }

    private fun rewriteHls(text: String, finalUrl: String): String {
        val base = URI(finalUrl)
        return text.lineSequence().joinToString("\n") { line ->
            when {
                line.trim().isEmpty() -> line
                line.trim().startsWith("#") -> HLS_URI.replace(line) { m ->
                    """URI="${addResource(base.resolve(URI(m.groupValues[1])).toString(), false)}""""
                }
                else -> addResource(base.resolve(URI(line.trim())).toString(), false)
            }
        }
    }

    /**
     * DASH relatives are resolved against a loopback BaseURL. Absolute BaseURL
     * and media references are rewritten to opaque local URLs; templates retain
     * their $Number$ / $RepresentationID$ placeholders for FFmpeg to expand.
     */
    private fun rewriteDash(text: String, finalUrl: String): String {
        val base = URI(finalUrl)
        var rewritten = BASE_URL.replace(text) { m ->
            val raw = xmlUnescape(m.groupValues[2].trim())
            val resolved = runCatching {
                if (raw.isEmpty()) base.resolve(".") else base.resolve(URI(raw))
            }.getOrNull()
            if (resolved != null &&
                (resolved.scheme.equals("http", true) || resolved.scheme.equals("https", true))
            ) {
                "<BaseURL${m.groupValues[1]}>${addBase(resolved.toString())}</BaseURL>"
            } else m.value
        }

        val rootOpen = MPD_OPEN.find(rewritten) ?: throw IOException("DASH manifest has no MPD root")
        val existingBase = BASE_URL.find(rewritten)
        val firstPeriod = PERIOD_OR_LOCATION.find(rewritten)
        val rootHasBase = existingBase != null && (firstPeriod == null || existingBase.range.first < firstPeriod.range.first)
        if (!rootHasBase) {
            val defaultBase = addBase(base.resolve(".").toString())
            val programInfo = PROGRAM_INFO_END.find(rewritten)
            val insertAt = programInfo?.range?.last?.plus(1) ?: rootOpen.range.last + 1
            rewritten = rewritten.substring(0, insertAt) + "<BaseURL>$defaultBase</BaseURL>" + rewritten.substring(insertAt)
        }

        rewritten = ABSOLUTE_DASH_URI.replace(rewritten) { m ->
            val urlValue = xmlUnescape(m.groupValues[2])
            val absolute = base.resolve(URI(urlValue)).toString()
            val local = if ('$' in urlValue) templateUrl(URI(absolute)) else addResource(absolute, false)
            "${m.groupValues[1]}$local${m.groupValues[3]}"
        }
        rewritten = LOCATION_TEXT.replace(rewritten) { m ->
            val raw = xmlUnescape(m.groupValues[2].trim())
            val absolute = base.resolve(URI(raw)).toString()
            "${m.groupValues[1]}${addResource(absolute, false)}${m.groupValues[3]}"
        }
        return rewritten
    }

    private fun templateUrl(remote: URI): String {
        val path = remote.rawPath.orEmpty()
        val slash = path.lastIndexOf('/')
        val directory = if (slash >= 0) path.substring(0, slash + 1) else "/"
        val name = if (slash >= 0) path.substring(slash + 1) else path
        val local = addBase("${remote.scheme}://${remote.rawAuthority}$directory") + name
        return remote.rawQuery?.let { "$local?$it" } ?: local
    }

    private fun resolveBase(baseUrl: String, suffix: String, query: String?): String {
        val base = URI(baseUrl)
        val relative = suffix + if (query.isNullOrBlank()) "" else "?$query"
        val resolved = base.resolve(URI(relative))
        val pairs = linkedMapOf<String, String>()
        fun add(raw: String?) {
            raw?.split('&')?.filter(String::isNotEmpty)?.forEach { part ->
                val key = part.substringBefore('=')
                pairs.remove(key)
                pairs[key] = part
            }
        }
        add(base.rawQuery)
        add(query)
        return buildString {
            append(resolved.scheme).append("://").append(resolved.rawAuthority).append(resolved.rawPath.orEmpty())
            val merged = pairs.values.joinToString("&")
            if (merged.isNotBlank()) append('?').append(merged)
        }.also(::requireHttpUrl)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_MANIFEST_BYTES) throw IOException("Manifest exceeds safe size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun responseHeaders(connection: HttpURLConnection): Map<String, String> = buildMap {
        listOf("Content-Range", "Accept-Ranges", "Last-Modified", "ETag").forEach { name ->
            connection.getHeaderField(name)?.takeIf(String::isNotBlank)?.let { put(name, it) }
        }
    }

    private fun writeHeaders(socket: Socket, status: Int, type: String, length: Long, extra: Map<String, String>) {
        val reason = when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 301 -> "Moved Permanently"; 302 -> "Found"
            304 -> "Not Modified"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"
            500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
            else -> "Response"
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        if (type.isNotBlank()) output.write("Content-Type: $type\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        if (length >= 0) output.write("Content-Length: $length\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        extra.forEach { (name, value) ->
            if (name.matches(Regex("[A-Za-z0-9-]+")) && !value.contains('\r') && !value.contains('\n')) {
                output.write("$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            }
        }
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun writeSimpleResponse(socket: Socket, status: Int, reason: String) {
        val body = "Manifest proxy request failed".toByteArray(StandardCharsets.UTF_8)
        runCatching {
            writeHeaders(socket, status, "text/plain; charset=utf-8", body.size.toLong(), emptyMap())
            socket.getOutputStream().apply { write(body); flush() }
        }
    }

    private fun forwardHeader(name: String): Boolean =
        name.equals("Referer", true) || name.equals("Origin", true) ||
            name.equals("Accept", true) || name.equals("Accept-Language", true) ||
            name.startsWith("Sec-Fetch-", true) || name.startsWith("Sec-CH-UA", true) ||
            name.equals("X-Goog-Visitor-Id", true) || name.equals("X-YouTube-Client-Name", true) ||
            name.equals("X-YouTube-Client-Version", true)

    private fun requireHttpUrl(url: String) {
        val scheme = runCatching { URI(url).scheme }.getOrNull()
        require(scheme.equals("http", true) || scheme.equals("https", true)) {
            "Only HTTP(S) resources are supported by the manifest bridge"
        }
    }

    private fun xmlUnescape(value: String): String =
        value.replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        executor.shutdownNow()
        targets.clear()
        @Suppress("UNUSED_VARIABLE") val keepReference = acceptThread
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 60_000
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val SNIFF_BYTES = 1024
        const val BUFFER_BYTES = 32 * 1024
        const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        val HLS_URI = Regex("""(?i)\bURI="([^"]+)"""")
        val MPD_TAG = Regex("<(?:[a-zA-Z0-9_-]+:)?MPD(?:\\s|>)", RegexOption.IGNORE_CASE)
        val MPD_OPEN = Regex("<(?:[a-zA-Z0-9_-]+:)?MPD\\b[^>]*>", RegexOption.IGNORE_CASE)
        val BASE_URL = Regex("""<BaseURL([^>]*)>(.*?)</BaseURL>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val ABSOLUTE_DASH_URI = Regex("""((?:media|initialization|sourceURL|href|index)=")(https?://[^"]+)(")""", RegexOption.IGNORE_CASE)
        val LOCATION_TEXT = Regex("""(<(?:[\w.-]+:)?(?:Location|PatchLocation)\b[^>]*>)([^<]+)(</(?:[\w.-]+:)?(?:Location|PatchLocation)\s*>)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val PERIOD_OR_LOCATION = Regex("<(?:[\\w.-]+:)?(?:Period|Location)\\b", RegexOption.IGNORE_CASE)
        val PROGRAM_INFO_END = Regex("</(?:[\\w.-]+:)?ProgramInformation\\s*>", RegexOption.IGNORE_CASE)
    }
}
