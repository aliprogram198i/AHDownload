package com.ahdownload.domain.resolver.youtube

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.OkHttpTextClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in live acceptance test. Standard unit-test runs skip the external request;
 * the dedicated GitHub workflow enables it to distinguish parser tests from live service access.
 */
class YouTubeSearchLiveAcceptanceTest {
    @Test
    fun liveSearchReturnsRenderableYouTubeResults() = runTest {
        assumeTrue(
            "Set AH_YOUTUBE_LIVE_ACCEPTANCE=true only in the dedicated live-acceptance workflow.",
            System.getenv("AH_YOUTUBE_LIVE_ACCEPTANCE") == "true",
        )

        val httpEvents = mutableListOf<String>()
        val logger = DiagnosticLogger { _, type, reason, operation, context, _ ->
            if (type == "HTTP_REQUEST") {
                httpEvents += listOf(
                    operation,
                    reason,
                    context["http_status"] ?: "no_status",
                    context["request_outcome"] ?: "no_outcome",
                    context["search_strategy"] ?: "HTML_PAGE",
                ).joinToString(":")
            }
        }
        var htmlResponse = ""
        var innertubeResponse = ""
        val delegate = OkHttpTextClient(logger = logger)
        val capturingClient = object : HttpTextClient {
            override suspend fun get(url: String): String =
                delegate.get(url).also { htmlResponse = it }

            override suspend fun get(url: String, headers: Map<String, String>): String =
                delegate.get(url, headers).also { htmlResponse = it }

            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                diagnosticContext: Map<String, String>,
            ): String = delegate.get(url, headers, diagnosticContext).also { htmlResponse = it }

            override suspend fun postJson(url: String, body: String): String =
                delegate.postJson(url, body).also { innertubeResponse = it }

            override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
                delegate.postJson(url, body, headers).also { innertubeResponse = it }

            override suspend fun postJson(
                url: String,
                body: String,
                headers: Map<String, String>,
                diagnosticContext: Map<String, String>,
            ): String = delegate.postJson(url, body, headers, diagnosticContext).also {
                innertubeResponse = it
            }
        }
        val results = YouTubeSearchProvider(capturingClient).search(
            query = "كاظم",
            limit = 12,
            diagnosticContext = mapOf("operation_id" to "live-youtube-search-acceptance"),
        )
        val resultKinds = results.groupingBy { it.kind }.eachCount()
        val httpSummary = httpEvents.joinToString("|")
        val htmlShape = safeSearchStructureProfile(htmlResponse)
        val innertubeShape = safeFullSchemaProfile(innertubeResponse)

        assertFalse(
            "Live YouTube search returned no results. HTTP=$httpSummary html=$htmlShape innertube=$innertubeShape",
            results.isEmpty(),
        )
        assertTrue(
            "Expected at least one regular video for an Arabic music query; kinds=$resultKinds HTTP=$httpSummary html=$htmlShape innertube=$innertubeShape",
            results.any { it.kind == com.ahdownload.domain.search.ContentSearchKind.VIDEO },
        )
        assertTrue(
            "Expected at least one channel for an Arabic music query; kinds=$resultKinds HTTP=$httpSummary html=$htmlShape innertube=$innertubeShape",
            results.any { it.kind == com.ahdownload.domain.search.ContentSearchKind.CHANNEL },
        )
        assertEquals(
            "Search result IDs should be deduplicated.",
            results.size,
            results.map { it.id }.distinct().size,
        )
        results.forEach { item ->
            assertFalse("Search result title is empty.", item.title.isBlank())
            assertFalse("Search result ID is empty.", item.id.isBlank())
            assertTrue(
                "Unexpected search result URL.",
                item.url.startsWith("https://www.youtube.com/"),
            )
        }
    }

    /**
     * Records key-name counts only. Never dumps response text, IDs, titles, query terms, or URLs.
     */
    private fun safeSearchStructureProfile(source: String): String {
        if (source.isBlank()) return "empty"
        val keys = listOf(
            "videoRenderer",
            "gridVideoRenderer",
            "compactVideoRenderer",
            "richItemRenderer",
            "lockupViewModel",
            "shortsLockupViewModel",
            "reelItemRenderer",
            "channelRenderer",
            "itemSectionRenderer",
            "sectionListRenderer",
            "twoColumnSearchResultsRenderer",
            "continuationItemRenderer",
        )
        val counts = keys.mapNotNull { key ->
            val count = Regex("\"" + Regex.escape(key) + "\"\\s*:").findAll(source).count()
            if (count == 0) null else "$key=$count"
        }
        val rootKeys = runCatching {
            val element = com.google.gson.JsonParser.parseString(source)
            if (element.isJsonObject) element.asJsonObject.keySet().sorted().take(16).joinToString(",")
            else "root_${element.javaClass.simpleName}"
        }.getOrDefault("non_json_root")
        return "chars=${source.length};root=$rootKeys;${counts.joinToString(",")}".take(900)
    }
    
    private fun safeFullSchemaProfile(source: String): String {
        val root = runCatching { com.google.gson.JsonParser.parseString(source) }.getOrNull()
            ?: return "invalid_json_length=" + source.length
        val counts = linkedMapOf<String, Int>()
        fun collect(element: com.google.gson.JsonElement) {
            when {
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { entry ->
                    counts[entry.key] = (counts[entry.key] ?: 0) + 1
                    collect(entry.value)
                }
                element.isJsonArray -> element.asJsonArray.forEach(::collect)
            }
        }
        collect(root)
        val interesting = Regex(
            "(?i)(renderer|viewmodel|video|short|channel|playlist|title|endpoint|result|search|section|lockup|continuation|entity|command|contents|reel|watch|grid|metadata)",
        )
        val schema = counts.entries
            .filter { interesting.containsMatchIn(it.key) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(60)
            .joinToString(",") { it.key + "=" + it.value }
        val rootKeys = if (root.isJsonObject) {
            root.asJsonObject.keySet().sorted().take(24).joinToString(",")
        } else {
            "non_object_root"
        }
        return ("length=" + source.length + ";root=" + rootKeys + ";schema=" + schema).take(1800)
    }

}
