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

        return Result.success(uri.normalize().toString())
    }
}
