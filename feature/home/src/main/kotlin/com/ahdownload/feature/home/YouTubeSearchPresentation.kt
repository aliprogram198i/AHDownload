package com.ahdownload.feature.home

import com.ahdownload.domain.search.ContentSearchItem
import com.ahdownload.domain.search.ContentSearchKind

/**
 * Stable counts for the YouTube search summary and filter labels.
 * Counts are computed from the unfiltered result set so switching filters never changes totals.
 */
internal data class YouTubeSearchResultCounts(
    val all: Int,
    val videos: Int,
    val shorts: Int,
    val channels: Int,
)

internal fun countYouTubeSearchResults(
    results: List<ContentSearchItem>,
): YouTubeSearchResultCounts = YouTubeSearchResultCounts(
    all = results.size,
    videos = results.count { it.kind == ContentSearchKind.VIDEO },
    shorts = results.count { it.kind == ContentSearchKind.SHORT },
    channels = results.count { it.kind == ContentSearchKind.CHANNEL },
)

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
