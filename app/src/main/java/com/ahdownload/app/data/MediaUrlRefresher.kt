package com.ahdownload.app.data

import android.content.Context
import android.net.Uri
import com.ahdownload.app.diagnostics.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaUrlRefresher(private val context: Context) {
    suspend fun refresh(
        sourceUrl: String,
        extension: String,
        mergeRequired: Boolean,
        excludeUrl: String? = null
    ): Result<ResolvedFormat> = withContext(Dispatchers.IO) {
        runCatching {
            val host = Uri.parse(sourceUrl).host.orEmpty().lowercase()
            require(isSupportedPlatform(host)) { "URL_REFRESH_UNSUPPORTED" }

            val excluded = excludeUrl?.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet()
            AppLogger.info(
                context,
                "resolver.refresh_start",
                "host=" + host + " extension=" + extension + " mergeRequired=" + mergeRequired +
                    " excluded=" + excluded.isNotEmpty()
            )

            fun select(media: ResolvedMedia, pass: String): ResolvedFormat? {
                val candidates = media.formats
                    .filter { it.url.isNotBlank() && it.url !in excluded }
                AppLogger.info(
                    context,
                    "resolver.refresh_candidates",
                    "host=" + host + " pass=" + pass + " formats=" + media.formats.size +
                        " usable=" + candidates.size
                )

                val selected = candidates.firstOrNull { format ->
                    format.ext.equals(extension, ignoreCase = true) &&
                        format.mergeRequired == mergeRequired
                } ?: candidates.firstOrNull { it.mergeRequired == mergeRequired }
                    ?: candidates.firstOrNull()

                if (selected != null) {
                    AppLogger.info(
                        context,
                        "resolver.refresh_selected",
                        "host=" + host + " pass=" + pass + " ext=" + selected.ext +
                            " merge=" + selected.mergeRequired + " video=" + selected.hasVideo +
                            " audio=" + selected.hasAudio + " width=" + (selected.width ?: 0) +
                            " height=" + (selected.height ?: 0)
                    )
                }
                return selected
            }

            val first = EmbeddedPlatformResolver(context)
                .resolve(sourceUrl, excludedUrls = excluded, forceFresh = false)
                .getOrElse { failure ->
                    AppLogger.error(
                        context,
                        "resolver.refresh_first_pass_failed",
                        failure,
                        "host=" + host
                    )
                    throw failure
                }

            select(first, "normal")?.let { return@runCatching it }

            if (host == "instagram.com" || host.endsWith(".instagram.com")) {
                AppLogger.warn(
                    context,
                    "resolver.refresh_force_fresh",
                    "host=" + host + " reason=no_alternate_candidate"
                )
                val fresh = EmbeddedPlatformResolver(context)
                    .resolve(sourceUrl, excludedUrls = excluded, forceFresh = true)
                    .getOrElse { failure ->
                        AppLogger.error(
                            context,
                            "resolver.refresh_force_fresh_failed",
                            failure,
                            "host=" + host
                        )
                        throw failure
                    }
                select(fresh, "force_fresh")?.let { return@runCatching it }
            }

            throw IllegalStateException("NO_REFRESHED_FORMAT")
        }.onFailure { failure ->
            AppLogger.error(
                context,
                "resolver.refresh_failed",
                failure,
                "host=" + Uri.parse(sourceUrl).host.orEmpty().lowercase()
            )
        }
    }

    private fun isSupportedPlatform(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host == "instagram.com" || host.endsWith(".instagram.com") ||
            host == "facebook.com" || host.endsWith(".facebook.com") ||
            host == "fb.watch"
}
