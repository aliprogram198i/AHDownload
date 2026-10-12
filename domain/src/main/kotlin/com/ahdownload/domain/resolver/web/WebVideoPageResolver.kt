package com.ahdownload.domain.resolver.web

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import com.ahdownload.domain.network.PublicInternetDns
import com.ahdownload.domain.resolver.browser.BrowserMediaSession
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider
import com.ahdownload.domain.resolver.browser.ParsedPageMedia
import com.ahdownload.domain.resolver.browser.WebPageMediaParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.InetAddress
import javax.net.ssl.SSLException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Discovers downloadable video resources declared by a normal HTML page.
 *
 * It supports HTML5 video/source tags, Open Graph/Twitter video metadata, script data
 * exposed by the shared parser, and browser-observed media requests as a fallback.
 * It does not defeat DRM or bypass an authenticated session.
 */
class WebVideoPageResolver(
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
    private val client: OkHttpClient = defaultClient(),
    private val browserMediaSessionProvider: BrowserMediaSessionProvider? = null,
) {
    suspend fun resolve(request: ResolverRequest): ResolverResult = withContext(Dispatchers.IO) {
        val initial = request.link.normalizedUrl.toHttpUrlOrNull()?.let(::preferHttps)
        if (initial == null || !isAllowedDestination(initial)) {
            return@withContext ResolverResult.Failure(
                FailureCode.InvalidRequest,
                "رابط صفحة الفيديو غير صالح أو لا يُسمح بالوصول إلى هذه الوجهة.",
            )
        }

        var resolverStage = "PAGE_FETCH"
        try {
            // Only HTTP responses that do not yield HTML can use the WebView fallback.
            // Transport, DNS, unsafe-redirect, and cancellation errors must not be converted
            // into a second navigation path.
            val page = fetchPage(initial, request)
            val pageUrl = page?.finalUrl ?: initial.toString()
            resolverStage = "HTML_PARSE"
            val parsedPage = page?.let { WebPageMediaParser.parse(it.html, it.finalUrl) }
                ?: ParsedPageMedia(title = null, thumbnailUrl = null, durationMs = null, mediaUrls = emptyList())
            val parsedHints = parsedPage.mediaUrls.mapNotNull { raw ->
                val parsedUrl = raw.toHttpUrlOrNull() ?: return@mapNotNull null
                val extension = extensionFromUrl(raw)
                val videoQuery = Regex("""[?&](?:mime|content-type|type|format)=[^&]*video""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(raw)
                if (
                    extension !in VIDEO_EXTENSIONS && extension !in MANIFEST_EXTENSIONS &&
                    !videoQuery
                ) return@mapNotNull null
                Hint(
                    url = parsedUrl.toString(),
                    label = null,
                    height = inferHeight(null, raw),
                    explicitVideo = true,
                )
            }
            val staticHints = ((page?.let { extractHints(it.html, it.finalUrl) } ?: emptyList()) + parsedHints)
                .distinctBy { it.url }
                .take(MAX_CANDIDATES * 3)
            resolverStage = "STATIC_MEDIA_PROBE"
            var candidates = buildCandidates(pageUrl, staticHints, request, resolverStage)
            var browserSession: BrowserMediaSession? = null

            // Only open the WebView when server-delivered HTML did not yield a valid
            // source. This lets JavaScript-driven players expose their real media requests
            // without adding browser startup latency to ordinary HTML5 pages.
            if (candidates.isEmpty() && browserMediaSessionProvider != null) {
                resolverStage = "BROWSER_FALLBACK"
                browserSession = try {
                    browserMediaSessionProvider.snapshot(pageUrl, MediaPlatform.Web)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log(
                        DiagnosticLevel.WARNING,
                        "WEB_VIDEO_BROWSER_FALLBACK_FAILED",
                        "تعذر الحصول على جلسة المتصفح لاكتشاف الوسائط",
                        request,
                        mapOf(
                            "failure_stage" to resolverStage,
                            "failure_category" to "BROWSER_SESSION_FAILURE",
                            "exception_type" to error.javaClass.simpleName,
                            "host" to hostOf(pageUrl),
                        ),
                        error,
                    )
                    null
                }
                val observedPageUrl = safeHttpUrl(browserSession?.finalUrl, pageUrl) ?: pageUrl
                val observedHints = browserSession?.mediaUrls.orEmpty().mapNotNull { raw ->
                    val parsedUrl = observedPageUrl.toHttpUrlOrNull()?.resolve(raw) ?: return@mapNotNull null
                    if (!isAllowedDestination(parsedUrl)) return@mapNotNull null
                    val extension = extensionFromUrl(parsedUrl.toString())
                    val videoQuery = Regex("""[?&](?:mime|content-type|type|format)=[^&]*video""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(parsedUrl.toString())
                    if (
                        extension in AUDIO_EXTENSIONS || extension in IMAGE_EXTENSIONS ||
                        (extension !in VIDEO_EXTENSIONS && extension !in MANIFEST_EXTENSIONS && !videoQuery)
                    ) return@mapNotNull null
                    Hint(
                        url = parsedUrl.toString(),
                        label = null,
                        height = inferHeight(null, parsedUrl.toString()),
                        explicitVideo = true,
                        requestHeaders = browserSession?.requestHeadersByUrl?.get(raw).orEmpty(),
                    )
                }.distinctBy { it.url }
                resolverStage = "BROWSER_MEDIA_PROBE"
                candidates = buildCandidates(observedPageUrl, observedHints, request, resolverStage)
            }

            if (candidates.isEmpty()) {
                log(
                    DiagnosticLevel.INFO,
                    "WEB_VIDEO_PAGE_NO_SOURCE",
                    "لم يتم العثور على مصدر فيديو قابل للتحقق من HTML أو جلسة المتصفح",
                    request,
                    mapOf(
                        "host" to hostOf(browserSession?.finalUrl ?: pageUrl),
                        "html_source_count" to staticHints.size.toString(),
                        "browser_observation" to (browserSession != null).toString(),
                        "browser_source_count" to (browserSession?.mediaUrls?.size ?: 0).toString(),
                    ),
                )
                return@withContext ResolverResult.Failure(
                    FailureCode.NoCandidates,
                    "لم يُعثر على مصدر فيديو قابل للتنزيل. قد يستخدم الموقع DRM أو يتطلب تسجيل الدخول أو آلية غير مدعومة.",
                )
            }

            val effectivePageUrl = safeHttpUrl(browserSession?.finalUrl, pageUrl) ?: pageUrl
            val title = browserSession?.title?.takeIf { it.isNotBlank() }
                ?: parsedPage.title
                ?: page?.title
                ?: "Web video"
            val thumbnail = safeHttpUrl(browserSession?.thumbnailUrl, effectivePageUrl)
                ?: safeHttpUrl(parsedPage.thumbnailUrl, effectivePageUrl)
                ?: safeHttpUrl(page?.thumbnailUrl, effectivePageUrl)
            val duration = browserSession?.durationMs ?: parsedPage.durationMs ?: page?.durationMs
            log(
                DiagnosticLevel.INFO,
                "WEB_VIDEO_PAGE_RESOLVED",
                "تم اكتشاف مصادر فيديو من صفحة ويب",
                request,
                mapOf(
                    "host" to hostOf(effectivePageUrl),
                    "discovered_count" to (staticHints.size + (browserSession?.mediaUrls?.size ?: 0)).toString(),
                    "accepted_count" to candidates.size.toString(),
                    "manifest_count" to candidates.count { it.streamingManifest }.toString(),
                    "browser_fallback" to (browserSession != null).toString(),
                ),
            )
            ResolverResult.Success(
                title = title,
                thumbnailUrl = thumbnail,
                durationMs = duration,
                candidates = candidates,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: UnsafeDestinationException) {
            log(
                DiagnosticLevel.WARNING,
                "WEB_VIDEO_REDIRECT_REJECTED",
                "تم رفض إعادة توجيه إلى وجهة غير عامة",
                request,
                mapOf("reason" to "unsafe_redirect"),
            )
            ResolverResult.Failure(FailureCode.InvalidRequest, "رفض التطبيق وجهة إعادة توجيه غير آمنة.")
        } catch (error: Exception) {
            log(
                DiagnosticLevel.WARNING,
                "WEB_VIDEO_PAGE_FAILED",
                error.javaClass.simpleName,
                request,
                mapOf(
                    "exception_type" to error.javaClass.simpleName,
                    "failure_stage" to resolverStage,
                    "failure_category" to failureCategory(error),
                ),
                error,
            )
            ResolverResult.Failure(
                FailureCode.ResolverUnavailable,
                "تعذّر تحليل صفحة الفيديو. تحقق من الاتصال أو جرّب رابطًا آخر.",
            )
        }
    }

    private fun fetchPage(initial: HttpUrl, resolverRequest: ResolverRequest): Page? {
        var current = initial
        var redirects = 0
        // HTTP fragments are not sent to servers, so exclude them from loop detection.
        val visitedRedirectUrls = mutableSetOf(redirectKey(current))
        while (true) {
            if (!isAllowedDestination(current)) throw UnsafeDestinationException()
            val pageRequest = Request.Builder()
                .url(current)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .header("Accept-Encoding", "identity")
                .build()
            val response = executeLogged(pageRequest, resolverRequest, "PAGE_FETCH")
            val location = response.header("Location")
            if (response.code in REDIRECT_CODES && !location.isNullOrBlank()) {
                val statusCode = response.code
                val fromHost = current.host.lowercase(Locale.ROOT)
                val next = current.resolve(location)?.let(::preferHttps)
                response.close()
                if (next == null || !isAllowedDestination(next)) throw UnsafeDestinationException()

                val nextKey = redirectKey(next)
                val targetAlreadyVisited = nextKey in visitedRedirectUrls
                log(
                    level = DiagnosticLevel.INFO,
                    event = "WEB_VIDEO_REDIRECT",
                    reason = "HTTP redirect observed",
                    request = resolverRequest,
                    values = mapOf(
                        "request_stage" to "PAGE_FETCH",
                        "http_status" to statusCode.toString(),
                        "redirect_index" to (redirects + 1).toString(),
                        "from_host" to fromHost,
                        "to_host" to next.host.lowercase(Locale.ROOT),
                        "same_host" to (fromHost == next.host.lowercase(Locale.ROOT)).toString(),
                        "target_already_visited" to targetAlreadyVisited.toString(),
                    ),
                )

                if (targetAlreadyVisited) {
                    log(
                        level = DiagnosticLevel.WARNING,
                        event = "WEB_VIDEO_REDIRECT_LOOP_DETECTED",
                        reason = "Redirect target was already visited",
                        request = resolverRequest,
                        values = mapOf(
                            "request_stage" to "PAGE_FETCH",
                            "failure_category" to "REDIRECT_LOOP",
                            "redirect_count" to redirects.toString(),
                            "from_host" to fromHost,
                            "to_host" to next.host.lowercase(Locale.ROOT),
                        ),
                    )
                    throw RedirectLoopException()
                }
                if (redirects >= MAX_REDIRECTS) {
                    log(
                        level = DiagnosticLevel.WARNING,
                        event = "WEB_VIDEO_REDIRECT_LIMIT_EXCEEDED",
                        reason = "Maximum page redirect count exceeded",
                        request = resolverRequest,
                        values = mapOf(
                            "request_stage" to "PAGE_FETCH",
                            "failure_category" to "REDIRECT_LIMIT_EXCEEDED",
                            "redirect_count" to redirects.toString(),
                            "from_host" to fromHost,
                            "to_host" to next.host.lowercase(Locale.ROOT),
                        ),
                    )
                    throw RedirectLimitExceededException()
                }
                visitedRedirectUrls += nextKey
                current = next
                redirects++
                continue
            }
            if (response.code !in 200..299) {
                response.close()
                return null
            }
            val mime = response.header("Content-Type").orEmpty()
                .substringBefore(';').trim().lowercase(Locale.ROOT)
            if (mime !in HTML_MIME_TYPES) {
                response.close()
                return null
            }
            val finalUrl = response.request.url.toString()
            val bytes = response.body.byteStream().use { readBounded(it, MAX_HTML_BYTES) }
            response.close()
            val html = String(bytes, StandardCharsets.UTF_8)
            return Page(
                finalUrl = finalUrl,
                html = html,
                title = extractTitle(html),
                thumbnailUrl = extractThumbnail(html, finalUrl),
            )
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count <= 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private fun probe(
        initial: HttpUrl,
        method: Method,
        headers: Map<String, String>,
        resolverRequest: ResolverRequest,
        stage: String,
    ): Probe {
        var current = initial
        var redirects = 0
        while (true) {
            if (!isAllowedDestination(current)) throw UnsafeDestinationException()
            val builder = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
            safeMediaHeaders(headers).forEach { (name, value) ->
                builder.header(name, value)
            }
            if (method == Method.HEAD) builder.head()
            else builder.get().header("Range", "bytes=0-0")

            val response = executeLogged(builder.build(), resolverRequest, "${stage}_${method.name}")
            val location = response.header("Location")
            if (response.code in REDIRECT_CODES && !location.isNullOrBlank()) {
                val next = current.resolve(location)?.let(::preferHttps)
                response.close()
                if (next == null || !isAllowedDestination(next)) throw UnsafeDestinationException()
                if (redirects >= MAX_REDIRECTS) throw IOException("Redirect limit exceeded")
                current = next
                redirects++
                continue
            }
            val snapshot = Probe(
                status = response.code,
                finalUrl = response.request.url.toString(),
                contentType = response.header("Content-Type"),
                contentLength = response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L },
                contentRangeTotal = response.header("Content-Range")
                    ?.substringAfterLast('/', "")
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L },
            )
            response.close()
            return snapshot
        }
    }

    private fun buildCandidates(
        pageUrl: String,
        hints: List<Hint>,
        resolverRequest: ResolverRequest,
        stage: String,
    ): List<MediaCandidate> =
        hints.take(MAX_CANDIDATES).mapNotNull { hint ->
            val mediaUrl = pageUrl.toHttpUrlOrNull()?.resolve(hint.url)?.let(::preferHttps) ?: return@mapNotNull null
            if (!isAllowedDestination(mediaUrl)) return@mapNotNull null
            val requestHeaders = safeMediaHeaders(hint.requestHeaders + ("Referer" to pageUrl))
            val response = try {
                val head = probe(mediaUrl, Method.HEAD, requestHeaders, resolverRequest, stage)
                if (head.status in 200..299 && !head.contentType.isNullOrBlank()) head
                else probe(mediaUrl, Method.RANGE, requestHeaders, resolverRequest, stage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@mapNotNull null
            }
            if (response.status !in 200..299) return@mapNotNull null

            val mime = response.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (mime == "text/html" || mime == "application/xhtml+xml") return@mapNotNull null
            val extension = extensionFromUrl(response.finalUrl)
                ?: extensionFromMime(mime)
                ?: when {
                    isHlsMime(mime) -> "m3u8"
                    isDashMime(mime) -> "mpd"
                    else -> null
                }
                ?: return@mapNotNull null
            val manifest = extension in MANIFEST_EXTENSIONS || isHlsMime(mime) || isDashMime(mime)
            val videoMime = mime?.startsWith("video/") == true
            val explicitVideoSource = hint.explicitVideo ||
                extension in VIDEO_EXTENSIONS || extension in MANIFEST_EXTENSIONS
            if (!videoMime && !manifest && !explicitVideoSource) return@mapNotNull null

            val height = hint.height ?: inferHeight(hint.label, response.finalUrl)
            val size = response.contentRangeTotal ?: response.contentLength?.takeIf { response.status != 206 }
            MediaCandidate(
                id = "web-video-" + extension + "-" + response.finalUrl.hashCode().toUInt().toString(16),
                sourceUrl = response.finalUrl,
                requestHeaders = requestHeaders,
                format = MediaFormat(
                    id = "web-video:" + extension + ":" + (height ?: 0) + ":" + (size ?: 0),
                    kind = MediaKind.Video,
                    container = containerFor(extension, manifest),
                    height = height,
                    fileSizeBytes = size,
                    hasVideo = true,
                    // HLS/DASH manifests may describe a muxed stream or separate tracks.
                    hasAudio = true,
                ),
                streamingManifest = manifest,
            )
        }.distinctBy { it.sourceUrl }.take(MAX_CANDIDATES)

    private fun safeMediaHeaders(source: Map<String, String>): Map<String, String> = buildMap {
        source.forEach { (name, value) ->
            when (name.lowercase(Locale.ROOT)) {
                "user-agent" -> put("User-Agent", value)
                "referer" -> put("Referer", value)
                "origin" -> put("Origin", value)
                "accept" -> put("Accept", value)
                "accept-language" -> put("Accept-Language", value)
                "sec-fetch-dest" -> put("Sec-Fetch-Dest", value)
                "sec-fetch-mode" -> put("Sec-Fetch-Mode", value)
                "sec-fetch-site" -> put("Sec-Fetch-Site", value)
            }
        }
    }

    private fun safeHttpUrl(value: String?, baseUrl: String): String? {
        if (value.isNullOrBlank()) return null
        val parsed = runCatching { baseUrl.toHttpUrlOrNull()?.resolve(value)?.let(::preferHttps) }.getOrNull() ?: return null
        return parsed.toString().takeIf { isAllowedDestination(parsed) }
    }

    private fun extractHints(html: String, baseUrl: String): List<Hint> {
        val source = html
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x2F;", "/")
            .replace("&#47;", "/")
        val results = linkedMapOf<String, Hint>()

        fun add(raw: String?, label: String? = null, explicitVideo: Boolean = false) {
            val value = raw?.trim()?.trim('"', '\'')?.takeIf { it.isNotBlank() } ?: return
            if (value.startsWith("data:", true) || value.startsWith("blob:", true) ||
                value.startsWith("javascript:", true) || value.startsWith("mailto:", true)
            ) return
            val url = runCatching { baseUrl.toHttpUrlOrNull()?.resolve(value)?.toString() }.getOrNull() ?: return
            val parsed = url.toHttpUrlOrNull() ?: return
            if (!isAllowedDestination(parsed)) return
            val extension = extensionFromUrl(url)
            val videoLike = extension in VIDEO_EXTENSIONS || extension in MANIFEST_EXTENSIONS ||
                explicitVideo || inferHeight(label, url) != null
            if (!videoLike) return
            results.putIfAbsent(
                url,
                Hint(url, label?.take(80), inferHeight(label, url), explicitVideo),
            )
        }

        val tagPattern = Regex("(?is)<(video|source)\\b([^>]*)>")
        val attributePattern = Regex("(?is)\\b([a-z_:][-a-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        tagPattern.findAll(source).forEach { match ->
            val attributes = parseAttributes(match.groupValues[2], attributePattern)
            val type = attributes["type"].orEmpty()
            val isVideo = match.groupValues[1].equals("video", ignoreCase = true) ||
                type.startsWith("video/", true) || type.contains("mpegurl", true) || type.contains("dash+xml", true)
            add(
                attributes["src"] ?: attributes["data-src"] ?: attributes["data-url"],
                attributes["label"] ?: attributes["data-quality"] ?: attributes["res"] ?: type,
                isVideo,
            )
        }

        val metaPattern = Regex("(?is)<meta\\b([^>]*)>")
        metaPattern.findAll(source).forEach { match ->
            val attributes = parseAttributes(match.groupValues[1], attributePattern)
            val key = (attributes["property"] ?: attributes["name"] ?: "").lowercase(Locale.ROOT)
            val videoMeta = key in VIDEO_META_KEYS || key.contains("video_url") || key.contains("contenturl")
            if (videoMeta) add(attributes["content"], key, explicitVideo = true)
        }

        // Some sites expose their CDN media URLs in embedded JSON/script text rather than source tags.
        val embeddedUrls = Regex("(?i)(?:https?:)?//[^\\s\\\"'<>]+?\\.(?:mp4|m4v|webm|mov|mkv|3gp|m3u8|mpd)(?:\\?[^\\s\\\"'<>]*)?")
        embeddedUrls.findAll(source).take(MAX_CANDIDATES * 3).forEach { add(it.value, explicitVideo = true) }

        return results.values.take(MAX_CANDIDATES * 3).toList()
    }

    private fun parseAttributes(raw: String, pattern: Regex): Map<String, String> =
        pattern.findAll(raw).associate { match ->
            val value = match.groupValues[2].ifBlank {
                match.groupValues[3].ifBlank { match.groupValues[4] }
            }
            match.groupValues[1].lowercase(Locale.ROOT) to value
        }

    private fun extractTitle(html: String): String? {
        val title = Regex("(?is)<meta\\b[^>]*(?:property|name)\\s*=\\s*[\"'](?:og:title|twitter:title)[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>")
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("(?is)<title\\b[^>]*>(.*?)</title>").find(html)?.groupValues?.getOrNull(1)
        return title?.replace(Regex("<[^>]+>"), " ")?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")?.trim()?.take(180)?.takeIf { it.isNotBlank() }
    }

    private fun extractThumbnail(html: String, pageUrl: String): String? {
        val tag = Regex("(?is)<meta\\b[^>]*(?:property|name)\\s*=\\s*[\"'](?:og:image|twitter:image)[\"'][^>]*>")
            .find(html)?.value ?: return null
        val content = Regex("(?i)content\\s*=\\s*[\"']([^\"']+)[\"']").find(tag)
            ?.groupValues?.getOrNull(1)?.replace("&amp;", "&") ?: return null
        val parsed = runCatching { pageUrl.toHttpUrlOrNull()?.resolve(content) }.getOrNull() ?: return null
        return parsed.toString().takeIf { isAllowedDestination(parsed) }
    }

    private fun inferHeight(label: String?, url: String): Int? {
        val text = label.orEmpty() + " " + url
        return Regex("(?i)(?:^|[^0-9])(2160|1440|1080|720|576|480|360|240)p?(?:[^0-9]|$)")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extensionFromUrl(url: String): String? {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: return null
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
    }

    private fun extensionFromMime(mime: String?): String? = when (mime?.substringBefore(';')?.lowercase(Locale.ROOT)) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "video/3gpp" -> "3gp"
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl", "audio/mpegurl" -> "m3u8"
        "application/dash+xml" -> "mpd"
        else -> null
    }

    private fun isHlsMime(mime: String?): Boolean =
        mime?.contains("mpegurl", true) == true

    private fun isDashMime(mime: String?): Boolean =
        mime?.contains("dash+xml", true) == true

    private fun containerFor(extension: String, manifest: Boolean): MediaContainer = when {
        manifest -> MediaContainer.Mkv
        extension in setOf("mp4", "m4v") -> MediaContainer.Mp4
        extension == "webm" -> MediaContainer.Webm
        extension == "mov" -> MediaContainer.Mov
        extension == "mkv" -> MediaContainer.Mkv
        extension == "3gp" -> MediaContainer.ThreeGp
        extension == "avi" -> MediaContainer.Avi
        else -> MediaContainer.Unknown
    }

    private fun mimeFromExtension(extension: String): String = when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mpd" -> "application/dash+xml"
        else -> "application/octet-stream"
    }

    /**
     * Prefer encrypted transport for user-supplied and page-discovered URLs.
     * This avoids Android cleartext-policy failures without globally enabling HTTP.
     * HTTP-only hosts may fail TLS negotiation; the resolver never downgrades back to HTTP.
     */
    private fun preferHttps(url: HttpUrl): HttpUrl =
        if (url.scheme == "http") url.newBuilder().scheme("https").build() else url

    private fun isAllowedDestination(url: HttpUrl): Boolean {
        if (url.scheme != "http" && url.scheme != "https") return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val host = url.host.trimEnd('.').lowercase(Locale.ROOT)
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".test")
        ) return false
        if (host.contains(":") || host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return !PublicInternetDns.isNonPublicAddress(address)
        }
        return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
    }

    private fun hostOf(url: String): String =
        url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT) ?: "unknown"

    private fun log(
        level: DiagnosticLevel,
        event: String,
        reason: String,
        request: ResolverRequest,
        values: Map<String, String>,
        throwable: Throwable? = null,
    ) {
        logger.log(
            level,
            event,
            reason,
            "web.video.resolve",
            buildMap {
                put("platform", "Web")
                put("operation_id", request.operationId ?: "none")
                putAll(values)
            },
            throwable,
        )
    }

    private fun executeLogged(
        callRequest: Request,
        resolverRequest: ResolverRequest,
        stage: String,
    ): okhttp3.Response {
        val startedAtNanos = System.nanoTime()
        val host = callRequest.url.host.lowercase(Locale.ROOT)
        return try {
            val response = client.newCall(callRequest).execute()
            log(
                level = if (response.isSuccessful) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                event = "HTTP_REQUEST",
                reason = "HTTP request completed",
                request = resolverRequest,
                values = mapOf(
                    "request_stage" to stage,
                    "request_method" to callRequest.method,
                    "host" to host,
                    "http_status" to response.code.toString(),
                    "duration_ms" to ((System.nanoTime() - startedAtNanos) / 1_000_000L).toString(),
                    "network_outcome" to if (response.isSuccessful) "SUCCESS" else "HTTP_ERROR",
                ),
            )
            response
        } catch (error: IOException) {
            log(
                level = DiagnosticLevel.WARNING,
                event = "HTTP_REQUEST",
                reason = "HTTP request failed before a response was received",
                request = resolverRequest,
                values = mapOf(
                    "request_stage" to stage,
                    "request_method" to callRequest.method,
                    "host" to host,
                    "duration_ms" to ((System.nanoTime() - startedAtNanos) / 1_000_000L).toString(),
                    "network_outcome" to "FAILED",
                    "failure_category" to failureCategory(error),
                    "exception_type" to error.javaClass.simpleName,
                ),
                throwable = error,
            )
            throw error
        }
    }

    private fun failureCategory(error: Throwable): String {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            when (current) {
                is SocketTimeoutException, is InterruptedIOException -> return "TIMEOUT"
                is UnknownHostException -> return "DNS_FAILURE"
                is SSLException -> return "TLS_FAILURE"
                is ConnectException -> return "CONNECTION_FAILURE"
                is SocketException -> {
                    val message = current.message.orEmpty().lowercase(Locale.ROOT)
                    return if ("reset" in message || "broken pipe" in message) "CONNECTION_RESET" else "SOCKET_FAILURE"
                }
                is RedirectLoopException -> return "REDIRECT_LOOP"
                is RedirectLimitExceededException -> return "REDIRECT_LIMIT_EXCEEDED"
                is IOException -> return "IO_FAILURE"
            }
            current = current.cause
            depth++
        }
        return "UNEXPECTED_FAILURE"
    }

    private data class Page(
        val finalUrl: String,
        val html: String,
        val title: String?,
        val thumbnailUrl: String?,
        val durationMs: Long? = null,
    )
    private data class Hint(
        val url: String,
        val label: String?,
        val height: Int?,
        val explicitVideo: Boolean,
        val requestHeaders: Map<String, String> = emptyMap(),
    )
    private data class Probe(
        val status: Int,
        val finalUrl: String,
        val contentType: String?,
        val contentLength: Long?,
        val contentRangeTotal: Long?,
    )
    private enum class Method { HEAD, RANGE }
    private class UnsafeDestinationException : IOException()
    private class RedirectLoopException : IOException()
    private class RedirectLimitExceededException : IOException()

    private fun redirectKey(url: HttpUrl): String =
        url.newBuilder().fragment(null).build().toString()

    private companion object {
        const val MAX_HTML_BYTES = 1_048_576
        const val MAX_CANDIDATES = 12
        const val MAX_REDIRECTS = 5
        const val USER_AGENT = "AHDownload/1.0 (Android)"
        val HTML_MIME_TYPES = setOf("text/html", "application/xhtml+xml")
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "avi")
        val MANIFEST_EXTENSIONS = setOf("m3u8", "mpd")
        val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "ogg", "flac", "wav", "opus")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "tiff", "heic", "heif")
        val VIDEO_META_KEYS = setOf(
            "og:video", "og:video:url", "og:video:secure_url",
            "twitter:player:stream", "twitter:player:stream:content_type",
        )
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .dns(PublicInternetDns)
            .build()
    }
}
