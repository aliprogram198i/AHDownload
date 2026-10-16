package com.ahdownload.feature.home

import com.ahdownload.domain.search.ContentSearchItem
import com.ahdownload.domain.search.ContentSearchKind
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeSearchPresentationTest {
    private val results = listOf(
        ContentSearchItem(
            id = "video-1",
            title = "First video",
            url = "https://www.youtube.com/watch?v=video-1",
            kind = ContentSearchKind.VIDEO,
        ),
        ContentSearchItem(
            id = "short-1",
            title = "A Short",
            url = "https://www.youtube.com/shorts/short-1",
            kind = ContentSearchKind.SHORT,
        ),
        ContentSearchItem(
            id = "channel-1",
            title = "A channel",
            url = "https://www.youtube.com/channel/channel-1",
            kind = ContentSearchKind.CHANNEL,
        ),
        ContentSearchItem(
            id = "video-2",
            title = "Second video",
            url = "https://www.youtube.com/watch?v=video-2",
            kind = ContentSearchKind.VIDEO,
        ),
    )

    @Test
    fun countsAllKindsFromTheCompleteUnfilteredResultSet() {
        assertEquals(
            YouTubeSearchResultCounts(all = 4, videos = 2, shorts = 1, channels = 1),
            countYouTubeSearchResults(results),
        )
    }

    @Test
    fun emptyResultCountsAreAllZero() {
        assertEquals(
            YouTubeSearchResultCounts(all = 0, videos = 0, shorts = 0, channels = 0),
            countYouTubeSearchResults(emptyList()),
        )
    }

    @Test
    fun allFilterPreservesResultOrderAndEveryKind() {
        assertEquals(results, filterYouTubeSearchResults(results, YouTubeSearchFilter.All))
    }

    @Test
    fun videoFilterShowsOnlyVideosInOriginalOrder() {
        assertEquals(
            listOf("video-1", "video-2"),
            filterYouTubeSearchResults(results, YouTubeSearchFilter.Videos).map { it.id },
        )
    }

    @Test
    fun shortsFilterShowsOnlyShorts() {
        assertEquals(
            listOf("short-1"),
            filterYouTubeSearchResults(results, YouTubeSearchFilter.Shorts).map { it.id },
        )
    }

    @Test
    fun channelFilterShowsOnlyChannels() {
        assertEquals(
            listOf("channel-1"),
            filterYouTubeSearchResults(results, YouTubeSearchFilter.Channels).map { it.id },
        )
    }

    @Test
    fun filtersWithNoMatchingResultsAreUnavailableButKeepStableCounts() {
        val counts = countYouTubeSearchResults(results)

        assertEquals(true, isYouTubeSearchFilterAvailable(YouTubeSearchFilter.All, counts))
        assertEquals(true, isYouTubeSearchFilterAvailable(YouTubeSearchFilter.Videos, counts))
        assertEquals(true, isYouTubeSearchFilterAvailable(YouTubeSearchFilter.Shorts, counts))
        assertEquals(true, isYouTubeSearchFilterAvailable(YouTubeSearchFilter.Channels, counts))

        val videosOnly = countYouTubeSearchResults(
            results.filter { it.kind == ContentSearchKind.VIDEO },
        )
        assertEquals(true, isYouTubeSearchFilterAvailable(YouTubeSearchFilter.All, videosOnly))
        assertEquals(true, isYouTubeSearchFilterAvailable(YouTubeSearchFilter.Videos, videosOnly))
        assertEquals(false, isYouTubeSearchFilterAvailable(YouTubeSearchFilter.Shorts, videosOnly))
        assertEquals(false, isYouTubeSearchFilterAvailable(YouTubeSearchFilter.Channels, videosOnly))
    }

    @Test
    fun allFiltersAreUnavailableForAnEmptyResultSet() {
        val counts = countYouTubeSearchResults(emptyList())

        YouTubeSearchFilter.entries.forEach { filter ->
            assertEquals(false, isYouTubeSearchFilterAvailable(filter, counts))
        }
    }

    @Test
    fun emptyResultsRemainEmptyForEveryFilter() {
        YouTubeSearchFilter.entries.forEach { filter ->
            assertEquals(emptyList<ContentSearchItem>(), filterYouTubeSearchResults(emptyList(), filter))
        }
    }
}
