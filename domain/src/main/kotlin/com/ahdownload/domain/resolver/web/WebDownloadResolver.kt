package com.ahdownload.domain.resolver.web

import com.ahdownload.core.common.DiagnosticLevel
import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.resolver.FailureCode
import com.ahdownload.domain.resolver.MediaCandidate
import com.ahdownload.domain.resolver.MediaContainer
import com.ahdownload.domain.resolver.MediaFormat
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Resolves a public HTTP(S) URL that returns a file directly.
 *
 * HTML pages are deliberately not treated as files: link extraction from page
 * contents is a separate phase. Requests are metadata-only (HEAD or GET Range)
 * and redirect targets are validated before the next request is made.
 */
class WebDownloadResolver(
    private val logger: DiagnosticLogger = DiagnosticLogger { _, _, _, _, _, _ -> },
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun resolve(request: ResolverRequest): ResolverResult = withContext(Dispatchers.IO) {
        val initialUrl = request.link.normalizedUrl.toHttpUrlOrNull()
        if (initialUrl == null || !isAllowedDestination(initialUrl)) {
            log(
                DiagnosticLevel.WARNING,
                "WEB_URL_REJECTED",
                "رفض رابط ويب غير صالح أو يشير إلى وجهة محلية",
                request,
                mapOf("reason" to "invalid_or_local_destination"),
            )
            return@withContext ResolverResult.Failure(
                FailureCode.InvalidRequest,
                "الرابط غير صالح أو لا يُسمح بالتنزيل من هذه الوجهة.",
            )
        }

        try {
            val head = probe(initialUrl, ProbeMethod.HEAD)
            val shouldTryRange = head.statusCode in setOf(403, 405, 501) ||
                (head.statusCode in 200..299 && (
                    head.contentType.isNullOrBlank() ||
                        (isGenericBinary(head.contentType) &&
                            extensionFromName(head.dispositionFileName.orEmpty()) == null &&
                            extensionFromPath(head.finalUrl) == null)
                    ))
            val result = if (shouldTryRange) probe(initialUrl, ProbeMethod.RANGE_GET) else head

            if (result.statusCode !in 200..299) {
                val authRequired = result.statusCode == 401 || result.statusCode == 403
                log(
                    DiagnosticLevel.WARNING,
                    "WEB_FILE_PROBE_FAILED",
                    "تعذّر الوصول إلى الملف عبر HTTP",
                    request,
                    mapOf(
                        "status_code" to result.statusCode.toString(),
                        "host" to hostOf(result.finalUrl),
                        "method" to result.method,
                        "redirect_count" to result.redirectCount.toString(),
                    ),
                )
                return@withContext ResolverResult.Failure(
                    if (authRequired) FailureCode.AuthenticationRequired else FailureCode.ResolverUnavailable,
                    if (authRequired) {
                        "يتطلب هذا الملف صلاحية وصول أو جلسة تسجيل دخول."
                    } else {
                        "تعذّر الوصول إلى الملف من الموقع (HTTP ${result.statusCode})."
                    },
                )
            }

            val mimeType = result.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"

            if (mimeType == "text/html" || mimeType == "application/xhtml+xml") {
                log(
                    DiagnosticLevel.INFO,
                    "WEB_URL_IS_PAGE",
                    "الرابط يعيد صفحة HTML وليس ملفًا مباشرًا",
                    request,
                    mapOf(
                        "host" to hostOf(result.finalUrl),
                        "status_code" to result.statusCode.toString(),
                        "redirect_count" to result.redirectCount.toString(),
                    ),
                )
                return@withContext ResolverResult.Failure(
                    FailureCode.NoCandidates,
                    "هذا رابط صفحة ويب وليس ملفًا مباشرًا. اكتشاف الملفات داخل الصفحات سيكون في المرحلة التالية.",
                )
            }

            val explicitName = result.dispositionFileName
            val pathName = fileNameFromPath(result.finalUrl)
            val proposedName = explicitName ?: pathName ?: "download"
            val dispositionExtension = extensionFromName(explicitName.orEmpty())
            val mimeExtension = extensionFromMime(mimeType)
            val pathExtension = extensionFromPath(result.finalUrl)
            val extension = dispositionExtension
                ?: mimeExtension?.takeIf { it != "bin" }
                ?: pathExtension
                ?: mimeExtension
                ?: "bin"
            val fileName = finalizeFileName(proposedName, extension, explicitName != null)
            val size = result.contentRangeTotal
                ?: result.contentLengthBytes?.takeIf { result.statusCode != 206 }

            val candidate = MediaCandidate(
                id = "web-file-${extension}-${result.finalUrl.hashCode().toUInt().toString(16)}",
                sourceUrl = result.finalUrl,
                format = MediaFormat(
                    id = "web-file:$mimeType:$extension",
                    kind = MediaKind.Unknown,
                    container = MediaContainer.Unknown,
                    fileSizeBytes = size,
                    hasVideo = false,
                    hasAudio = false,
                    webFile = true,
                    fileExtension = extension,
                    mimeType = mimeType,
                ),
            )

            log(
                DiagnosticLevel.INFO,
                "WEB_FILE_RESOLVED",
                "تم التحقق من وجود ملف ويب مباشر",
                request,
                mapOf(
                    "host" to hostOf(result.finalUrl),
                    "status_code" to result.statusCode.toString(),
                    "method" to result.method,
                    "mime_type" to mimeType,
                    "file_extension" to extension,
                    "file_size_bytes" to (size?.toString() ?: "unknown"),
                    "redirect_count" to result.redirectCount.toString(),
                ),
            )

            ResolverResult.Success(
                title = fileName,
                thumbnailUrl = null,
                durationMs = null,
                candidates = listOf(candidate),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: UnsafeDestinationException) {
            log(
                DiagnosticLevel.WARNING,
                "WEB_REDIRECT_REJECTED",
                "تم رفض إعادة توجيه إلى وجهة محلية أو غير مسموحة",
                request,
                mapOf("reason" to "unsafe_redirect"),
            )
            ResolverResult.Failure(
                FailureCode.InvalidRequest,
                "رفض التطبيق وجهة إعادة توجيه غير آمنة.",
            )
        } catch (error: Throwable) {
            log(
                DiagnosticLevel.WARNING,
                "WEB_FILE_RESOLUTION_FAILED",
                error::class.java.simpleName,
                request,
                mapOf("exception_type" to error::class.java.simpleName),
            )
            ResolverResult.Failure(
                FailureCode.ResolverUnavailable,
                "تعذّر فحص رابط الويب. تحقق من الاتصال أو أعد المحاولة.",
            )
        }
    }

    private fun probe(initialUrl: HttpUrl, method: ProbeMethod): ProbeSnapshot {
        var current = initialUrl
        var redirects = 0
        while (true) {
            if (!isAllowedDestination(current)) throw UnsafeDestinationException()
            val builder = Request.Builder()
                .url(current)
                .header("User-Agent", "AHDownload/1.0 (Android)")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
            if (method == ProbeMethod.HEAD) {
                builder.head()
            } else {
                builder.get().header("Range", "bytes=0-0")
            }

            val response = client.newCall(builder.build()).execute()
            val location = response.header("Location")
            if (response.code in REDIRECT_CODES && !location.isNullOrBlank()) {
                val next = current.resolve(location)
                response.close()
                if (next == null || !isAllowedDestination(next)) throw UnsafeDestinationException()
                if (redirects >= MAX_REDIRECTS) throw IOException("Redirect limit exceeded")
                current = next
                redirects++
                continue
            }

            val result = ProbeSnapshot(
                statusCode = response.code,
                finalUrl = response.request.url.toString(),
                contentType = response.header("Content-Type"),
                contentLengthBytes = response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L },
                contentRangeTotal = parseContentRangeTotal(response.header("Content-Range")),
                dispositionFileName = fileNameFromDisposition(response.header("Content-Disposition")),
                method = method.label,
                redirectCount = redirects,
            )
            response.close()
            return result
        }
    }

    private fun log(
        level: DiagnosticLevel,
        type: String,
        reason: String,
        request: ResolverRequest,
        extra: Map<String, String>,
    ) {
        logger.log(
            level,
            type,
            reason,
            "web.resolve",
            buildMap {
                put("platform", "Web")
                put("operation_id", request.operationId ?: "none")
                putAll(extra)
            },
            null,
        )
    }

    private fun parseContentRangeTotal(value: String?): Long? {
        val match = Regex("""(?i)^bytes\s+\d+-\d+/(\d+)$""")
            .matchEntire(value.orEmpty().trim())
        return match?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0L }
    }

    private fun fileNameFromDisposition(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val encoded = Regex("""(?i)filename\*\s*=\s*([^;]+)""")
            .find(value)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
        val decodedExtended = encoded?.let { raw ->
            val payload = raw.substringAfter("''", raw).trim()
            runCatching {
                URLDecoder.decode(payload.replace("+", "%2B"), StandardCharsets.UTF_8.name())
            }.getOrDefault(payload)
        }?.let(::sanitizeFileName)
        if (decodedExtended != null) return decodedExtended

        val plain = Regex("""(?i)filename\s*=\s*(?:"([^"]+)"|([^;]+))""")
            .find(value)
        return (plain?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: plain?.groupValues?.getOrNull(2)?.trim()?.trim('"'))
            ?.let(::sanitizeFileName)
    }

    private fun fileNameFromPath(url: String): String? {
        val raw = url.toHttpUrlOrNull()?.encodedPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val decoded = runCatching {
            URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(raw)
        return sanitizeFileName(decoded)
    }

    private fun sanitizeFileName(value: String): String? =
        value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\u0000-\u001F\u007F]"""), "")
            .replace(Regex("""[<>:"|?*]"""), "_")
            .trim()
            .trim('.')
            .take(160)
            .takeIf { it.isNotBlank() && it != "." && it != ".." }

    private fun finalizeFileName(proposed: String, extension: String, explicitName: Boolean): String {
        val cleaned = sanitizeFileName(proposed).orEmpty().ifBlank { "download" }
        val existingExtension = extensionFromName(cleaned)
        val base = when {
            existingExtension == extension -> cleaned
            explicitName && existingExtension != null -> cleaned
            existingExtension != null -> cleaned.dropLast(existingExtension.length + 1).trimEnd('.').ifBlank { "download" }
            else -> cleaned
        }
        return if (base.endsWith(".$extension", ignoreCase = true)) base else "$base.$extension"
    }

    private fun extensionFromName(name: String): String? =
        name.substringAfterLast('/', name)
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }

    private fun extensionFromPath(url: String): String? {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: return null
        val raw = path.substringAfterLast('/').substringAfterLast('.', "")
        return raw.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
    }

    private fun extensionFromMime(mime: String): String? = when (mime.substringBefore(';').lowercase(Locale.ROOT)) {
        "application/pdf" -> "pdf"
        "application/zip", "application/x-zip-compressed" -> "zip"
        "application/x-rar-compressed", "application/vnd.rar" -> "rar"
        "application/x-7z-compressed" -> "7z"
        "application/gzip", "application/x-gzip" -> "gz"
        "application/x-tar" -> "tar"
        "application/vnd.android.package-archive" -> "apk"
        "application/epub+zip" -> "epub"
        "application/msword" -> "doc"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.ms-excel" -> "xls"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.ms-powerpoint" -> "ppt"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
        "application/json" -> "json"
        "application/xml", "text/xml" -> "xml"
        "text/csv" -> "csv"
        "text/plain" -> "txt"
        "text/markdown" -> "md"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg" -> "ogg"
        "audio/flac" -> "flac"
        "audio/wav", "audio/x-wav" -> "wav"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        "image/bmp" -> "bmp"
        "image/tiff" -> "tiff"
        "image/svg+xml" -> "svg"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "application/octet-stream" -> "bin"
        else -> null
    }

    private fun isGenericBinary(mime: String?): Boolean =
        mime?.substringBefore(';')?.trim()?.equals("application/octet-stream", ignoreCase = true) == true

    private fun isAllowedDestination(url: HttpUrl): Boolean {
        if (url.scheme != "http" && url.scheme != "https") return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val host = url.host.trimEnd('.').lowercase(Locale.ROOT)
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".test")
        ) return false
        if (host.contains(":")) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return !isNonPublicAddress(address)
        }
        if (host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))) {
            val octets = host.split('.').mapNotNull(String::toIntOrNull)
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            val address = InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
            return !isNonPublicAddress(address)
        }
        return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
    }

    private fun hostOf(value: String): String =
        value.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT) ?: "unknown"

    private data class ProbeSnapshot(
        val statusCode: Int,
        val finalUrl: String,
        val contentType: String?,
        val contentLengthBytes: Long?,
        val contentRangeTotal: Long?,
        val dispositionFileName: String?,
        val method: String,
        val redirectCount: Int,
    )

    private enum class ProbeMethod(val label: String) { HEAD("HEAD"), RANGE_GET("GET_RANGE") }

    private class UnsafeDestinationException : IOException()

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    if (addresses.isEmpty() || addresses.any(::isNonPublicAddress)) {
                        throw UnknownHostException("Blocked non-public destination")
                    }
                    return addresses
                }
            })
            .build()

        fun isNonPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
            ) return true
            val bytes = address.address.map { it.toInt() and 0xff }
            if (address is Inet4Address && bytes.size == 4) {
                val a = bytes[0]
                val b = bytes[1]
                return a == 0 || a == 10 || a == 127 ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) ||
                    (a == 100 && b in 64..127) ||
                    a >= 224 ||
                    (a == 198 && b in 18..19)
            }
            if (address is Inet6Address && bytes.size == 16) {
                return (bytes[0] and 0xfe) == 0xfc ||
                    (bytes[0] == 0xfe && (bytes[1] and 0xc0) == 0x80)
            }
            return false
        }
    }
}
