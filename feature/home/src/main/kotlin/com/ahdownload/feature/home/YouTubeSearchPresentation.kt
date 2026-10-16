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
