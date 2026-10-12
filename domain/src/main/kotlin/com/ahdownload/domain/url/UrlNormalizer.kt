package com.ahdownload.domain.url

import java.net.URI

class UrlNormalizer {
    fun normalize(rawUrl: String): Result<String> {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "URL is empty" }

        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = URI(candidate)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Unsupported URL scheme" }

        require(!uri.host.isNullOrBlank()) { "URL host is missing" }

        val normalized = uri.normalize()
        val authority = normalized.rawAuthority
            ?: throw IllegalArgumentException("URL authority is missing")
        val host = normalized.host
            ?: throw IllegalArgumentException("URL host is missing")
        val hostIndex = authority.lastIndexOf(host)
        require(hostIndex >= 0) { "URL host is missing from authority" }
        val normalizedAuthority = authority.substring(0, hostIndex) +
            host.lowercase(java.util.Locale.ROOT) +
            authority.substring(hostIndex + host.length)
        val normalizedUrl = buildString {
            append(scheme)
            append("://")
            append(normalizedAuthority)
            append(normalized.rawPath.orEmpty())
            normalized.rawQuery?.let { append('?').append(it) }
            normalized.rawFragment?.let { append('#').append(it) }
        }
        return Result.success(URI(normalizedUrl).normalize().toString())
    }
}
