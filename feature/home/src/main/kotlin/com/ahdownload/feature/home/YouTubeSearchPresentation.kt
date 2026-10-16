package com.ahdownload.feature.home

import com.ahdownload.domain.search.ContentSearchItem
import com.ahdownload.domain.search.ContentSearchKind

/**
 * Shared presentation filter for the YouTube Search screen.
 * Keeps the original order while showing only the requested result kind.
 */
internal fun filterYouTubeSearchResults(
    results: List<ContentSearchItem>,
    filter: YouTubeSearchFilter,
): List<ContentSearchItem> = when (filter) {
    YouTubeSearchFilter.All -> results
    YouTubeSearchFilter.Videos -> results.filter { it.kind == ContentSearchKind.VIDEO }
    YouTubeSearchFilter.Shorts -> results.filter { it.kind == ContentSearchKind.SHORT }
    YouTubeSearchFilter.Channels -> results.filter { it.kind == ContentSearchKind.CHANNEL }
}

/** Counts the kinds present in the current result set without changing source order. */
internal data class YouTubeSearchCounts(
    val videos: Int,
    val shorts: Int,
    val channels: Int,
) {
    val total: Int get() = videos + shorts + channels
}

internal fun countYouTubeSearchResults(
    results: List<ContentSearchItem>,
): YouTubeSearchCounts = YouTubeSearchCounts(
    videos = results.count { it.kind == ContentSearchKind.VIDEO },
    shorts = results.count { it.kind == ContentSearchKind.SHORT },
    channels = results.count { it.kind == ContentSearchKind.CHANNEL },
)
