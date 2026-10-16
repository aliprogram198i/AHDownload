package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.search.ContentSearchKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSearchProviderTest {
    @Test
    fun parsesVideoResultsFromInitialData() = runTest {
        val client = textClient(
            """<html><script>var ytInitialData = {"contents":{"videoRenderer":{"videoId":"abc123","title":{"runs":[{"text":"Test video"}]},"lengthText":{"simpleText":"4:20"},"ownerText":{"runs":[{"text":"Test channel"}]},"thumbnail":{"thumbnails":[{"url":"https://img.example/small.jpg"},{"url":"https://img.example/large.jpg"}]}}}};</script></html>""",
        )

        val results = YouTubeSearchProvider(client).search("test", limit = 5)

        assertEquals(1, results.size)
        assertEquals("abc123", results.first().id)
        assertEquals("Test video", results.first().title)
        assertEquals("4:20", results.first().durationLabel)
        assertEquals("Test channel", results.first().channelLabel)
        assertEquals(ContentSearchKind.VIDEO, results.first().kind)
        assertTrue(results.first().thumbnailUrl?.contains("large.jpg") == true)
    }

    @Test
    fun parsesShortsAndChannelResultsAlongsideVideos() = runTest {
        val client = textClient(
            """<html><script>var ytInitialData = {"contents":{
              "videoRenderer":{"videoId":"video123","title":{"simpleText":"Regular video"}},
              "reelItemRenderer":{"videoId":"short123","headline":{"simpleText":"Short clip"},"thumbnail":{"thumbnails":[{"url":"//img.example/short.jpg"}]}},
              "channelRenderer":{"channelId":"UCchannel123","title":{"simpleText":"Creator channel"},"subscriberCountText":{"simpleText":"1.2M subscribers"},"thumbnail":{"thumbnails":[{"url":"https://img.example/channel.jpg"}]}}
            }};</script></html>""",
        )

        val results = YouTubeSearchProvider(client).search("creator", limit = 12)

        assertEquals(3, results.size)
        assertEquals(
            setOf(ContentSearchKind.VIDEO, ContentSearchKind.SHORT, ContentSearchKind.CHANNEL),
            results.map { it.kind }.toSet(),
        )
        val short = results.single { it.kind == ContentSearchKind.SHORT }
        assertEquals("https://www.youtube.com/shorts/short123", short.url)
        assertEquals("https://img.example/short.jpg", short.thumbnailUrl)
        val channel = results.single { it.kind == ContentSearchKind.CHANNEL }
        assertEquals("https://www.youtube.com/channel/UCchannel123", channel.url)
        assertEquals("1.2M subscribers", channel.subtitle)
    }

    @Test
    fun preservesSourceOrderAndPromotesDuplicateVideoToShorts() = runTest {
        val client = textClient(
            """<html><script>var ytInitialData = {"contents":[
              {"videoRenderer":{"videoId":"video-first","title":{"simpleText":"First video"}}},
              {"channelRenderer":{"channelId":"UCcreator","title":{"simpleText":"Creator"}}},
              {"videoRenderer":{"videoId":"shared-short","title":{"simpleText":"Short before promotion"}}},
              {"reelItemRenderer":{"videoId":"shared-short","headline":{"simpleText":"Actual Short"}}},
              {"videoRenderer":{"videoId":"video-last","title":{"simpleText":"Last video"}}}
            ]};</script></html>""",
        )

        val results = YouTubeSearchProvider(client).search("ordered", limit = 12)

        assertEquals(
            listOf("video-first", "UCcreator", "shared-short", "video-last"),
            results.map { it.id },
        )
        assertEquals(ContentSearchKind.SHORT, results[2].kind)
        assertEquals("Actual Short", results[2].title)
    }

    @Test
    fun parsesModernShortsLockupRenderer() = runTest {
        val client = textClient(
            """<html><script>var ytInitialData = {"contents":{"shortsLockupViewModel":{"entityId":"shorts-shelf-item-short789","overlayMetadata":{"primaryText":{"content":"Modern short"}},"thumbnail":{"sources":[{"url":"https://img.example/modern.jpg"}]}}}};</script></html>""",
        )

        val result = YouTubeSearchProvider(client).search("shorts").single()

        assertEquals("short789", result.id)
        assertEquals(ContentSearchKind.SHORT, result.kind)
        assertEquals("https://img.example/modern.jpg", result.thumbnailUrl)
    }

    @Test
    fun forwardsRedactedOperationContextToHttpClient() = runTest {
        val html = """<html><script>var ytInitialData = {"contents":{"videoRenderer":{"videoId":"abc123","title":{"simpleText":"Test video"}}}};</script></html>"""
        var requestedUrl = ""
        var seenContext: Map<String, String> = emptyMap()
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = html

            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                diagnosticContext: Map<String, String>,
            ): String {
                requestedUrl = url
                seenContext = diagnosticContext
                return html
            }
        }

        val results = YouTubeSearchProvider(client).search(
            query = "private search phrase",
            limit = 5,
            diagnosticContext = mapOf(
                "operation_id" to "youtube-search-test-42",
                "query_length" to "21",
            ),
        )

        assertEquals(1, results.size)
        assertTrue(requestedUrl.contains("search_query=private+search+phrase"))
        assertEquals("youtube.search", seenContext["diagnostic_operation"])
        assertEquals("youtube-search-test-42", seenContext["operation_id"])
        assertEquals("YouTube", seenContext["platform"])
        assertEquals("SEARCH", seenContext["stage"])
        assertEquals("21", seenContext["search_query_length"])
        assertFalse(seenContext.values.any { it.contains("private search phrase") })
    }

    @Test
    fun parsesWindowBracketInitialDataAssignment() = runTest {
        val html = """<html><script>window["ytInitialData"]={"contents":{"videoRenderer":{"videoId":"window123","title":{"simpleText":"Window payload"}}}};</script></html>"""

        val result = YouTubeSearchProvider(textClient(html)).search("window").single()

        assertEquals("window123", result.id)
        assertEquals("Window payload", result.title)
    }

    @Test
    fun parsesJsonParseWrappedInitialDataAssignment() = runTest {
        val payload = """{"contents":{"videoRenderer":{"videoId":"wrapped123","title":{"simpleText":"Wrapped payload"}}}}"""
        val html = """<html><script>window["ytInitialData"] = JSON.parse(${com.google.gson.JsonPrimitive(payload)});</script></html>"""

        val result = YouTubeSearchProvider(textClient(html)).search("wrapped").single()

        assertEquals("wrapped123", result.id)
        assertEquals("Wrapped payload", result.title)
    }

    @Test
    fun reportsRedactedReasonWhenResponseIsAConsentPage() = runTest {
        val html = """<!doctype html><html><title>Before you continue to YouTube</title><body>We use cookies and data</body></html>"""
        val error = runCatching {
            YouTubeSearchProvider(textClient(html)).search("private query")
        }.exceptionOrNull()

        assertTrue(error is YouTubeSearchPayloadException)
        val payloadError = error as YouTubeSearchPayloadException
        assertEquals("consent_or_privacy_gate", payloadError.payloadFailureReason)
        assertEquals(html.length, payloadError.responseChars)
        assertFalse(payloadError.message.orEmpty().contains("private query"))
        assertFalse(payloadError.message.orEmpty().contains("Before you continue"))
    }

    @Test
    fun parsesParenthesizedObjectWithJavaScriptCommentsAroundAssignment() = runTest {
        val html = """<html><script>window["ytInitialData"] = /* payload follows */ (
          {"contents":{"videoRenderer":{"videoId":"commented123","title":{"simpleText":"Commented payload"}}}}
        );</script></html>"""

        val result = YouTubeSearchProvider(textClient(html)).search("comments").single()

        assertEquals("commented123", result.id)
        assertEquals("Commented payload", result.title)
    }

    @Test
    fun parsesJsonParseWithSingleQuotedJavaScriptString() = runTest {
        val payload = """{"contents":{"videoRenderer":{"videoId":"single123","title":{"simpleText":"Quoted payload"}}}}"""
        val escapedPayload = payload
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
        val html = "<html><script>ytInitialData = JSON.parse('$escapedPayload');</script></html>"

        val result = YouTubeSearchProvider(textClient(html)).search("single quote").single()

        assertEquals("single123", result.id)
        assertEquals("Quoted payload", result.title)
    }

    @Test
    fun parsesJsonParseWithDecodeURIComponentWrapper() = runTest {
        val payload = """{"contents":{"videoRenderer":{"videoId":"encoded123","title":{"simpleText":"Plus + and space"}}}}"""
        val encoded = java.net.URLEncoder.encode(payload, "UTF-8").replace("+", "%20")
        val html = """<html><script>ytInitialData = JSON.parse(decodeURIComponent("$encoded"));</script></html>"""

        val result = YouTubeSearchProvider(textClient(html)).search("encoded").single()

        assertEquals("encoded123", result.id)
        assertEquals("Plus + and space", result.title)
    }

    @Test
    fun parsesJsonParseWithBase64Wrapper() = runTest {
        val payload = """{"contents":{"videoRenderer":{"videoId":"base64123","title":{"simpleText":"Base64 payload"}}}}"""
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        val html = """<html><script>ytInitialData = JSON.parse(atob("$encoded"));</script></html>"""

        val result = YouTubeSearchProvider(textClient(html)).search("base64").single()

        assertEquals("base64123", result.id)
        assertEquals("Base64 payload", result.title)
    }

    @Test
    fun parsesJsonParseWithConcatenatedStringLiterals() = runTest {
        val payload = """{"contents":{"videoRenderer":{"videoId":"concat123","title":{"simpleText":"Concatenated payload"}}}}"""
        val split = payload.length / 2
        val firstLiteral = com.google.gson.JsonPrimitive(payload.substring(0, split)).toString()
        val secondLiteral = com.google.gson.JsonPrimitive(payload.substring(split)).toString()
        val html = """<html><script>ytInitialData = JSON.parse($firstLiteral + $secondLiteral);</script></html>"""

        val result = YouTubeSearchProvider(textClient(html)).search("concat").single()

        assertEquals("concat123", result.id)
        assertEquals("Concatenated payload", result.title)
    }

    @Test
    fun parsesJsonParsePayloadWithAntiXssiPrefixWithoutNewline() = runTest {
        val payload = """)]}'{"contents":{"videoRenderer":{"videoId":"xssi123","title":{"simpleText":"Prefixed payload"}}}}"""
        val html = """<html><script>ytInitialData = JSON.parse(${com.google.gson.JsonPrimitive(payload)});</script></html>"""

        val result = YouTubeSearchProvider(textClient(html)).search("xssi").single()

        assertEquals("xssi123", result.id)
        assertEquals("Prefixed payload", result.title)
    }

    @Test
    fun parsesJsonParseUsingEarlierStaticStringVariable() = runTest {
        val payload = """{"contents":{"videoRenderer":{"videoId":"static123","title":{"simpleText":"Static variable payload"}}}}"""
        val serialized = com.google.gson.JsonPrimitive(payload).toString()
        val html = """<html><script>
            const serializedInitialData = $serialized;
            window["ytInitialData"] = JSON.parse(serializedInitialData);
        </script></html>"""

        val result = YouTubeSearchProvider(textClient(html)).search("static").single()

        assertEquals("static123", result.id)
        assertEquals("Static variable payload", result.title)
    }

    @Test
    fun parsesStaticJsonParseWithNullSecondArgument() = runTest {
        val payload = """{"contents":{"videoRenderer":{"videoId":"secondArg123","title":{"simpleText":"Optional argument payload"}}}}"""
        val serialized = com.google.gson.JsonPrimitive(payload).toString()
        val html = """<html><script>ytInitialData = JSON.parse($serialized, null);</script></html>"""

        val result = YouTubeSearchProvider(textClient(html)).search("second argument").single()

        assertEquals("secondArg123", result.id)
        assertEquals("Optional argument payload", result.title)
    }

    @Test
    fun fallsBackToInnertubeSearchWhenInitialDataExpressionIsUnsupported() = runTest {
        val html = """<html><script>
            ytcfg.set({"INNERTUBE_API_KEY":"test-api-key","INNERTUBE_CONTEXT":{
              "client":{"clientName":"WEB","clientVersion":"2.20261015.01.00","hl":"en","gl":"US","visitorData":"visitor-test"}
            }});
            window["ytInitialData"] = JSON.parse(buildInitialData());
        </script></html>"""
        val innertubeJson = """{
          "contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
            {"itemSectionRenderer":{"contents":[
              {"videoRenderer":{"videoId":"livevideo123","title":{"runs":[{"text":"Search result video"}]},"lengthText":{"simpleText":"3:20"},"ownerText":{"runs":[{"text":"Example channel"}]},"thumbnail":{"thumbnails":[{"url":"https://img.example/video.jpg"}]}}},
              {"reelItemRenderer":{"videoId":"shortvideo123","headline":{"simpleText":"Search result Short"},"thumbnail":{"thumbnails":[{"url":"https://img.example/short.jpg"}]}}},
              {"channelRenderer":{"channelId":"UCexample123","title":{"simpleText":"Search result channel"},"subscriberCountText":{"simpleText":"10K subscribers"}}}
            ]}}
          ]}}}}
        }"""
        var postedUrl = ""
        var postedBody = ""
        var postedHeaders: Map<String, String> = emptyMap()
        var postedDiagnostics: Map<String, String> = emptyMap()
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = html

            override suspend fun postJson(
                url: String,
                body: String,
                headers: Map<String, String>,
                diagnosticContext: Map<String, String>,
            ): String {
                postedUrl = url
                postedBody = body
                postedHeaders = headers
                postedDiagnostics = diagnosticContext
                return innertubeJson
            }
        }

        val results = YouTubeSearchProvider(client).search(
            query = "كاظم",
            limit = 12,
            diagnosticContext = mapOf("operation_id" to "innertube-fallback-test"),
        )

        assertEquals(listOf("livevideo123", "shortvideo123", "UCexample123"), results.map { it.id })
        assertEquals(listOf(ContentSearchKind.VIDEO, ContentSearchKind.SHORT, ContentSearchKind.CHANNEL), results.map { it.kind })
        assertEquals("3:20", results[0].durationLabel)
        assertEquals("Example channel", results[0].channelLabel)
        assertEquals("https://img.example/short.jpg", results[1].thumbnailUrl)
        assertEquals("10K subscribers", results[2].subtitle)
        assertTrue(postedUrl.startsWith("https://www.youtube.com/youtubei/v1/search?prettyPrint=false&key=test-api-key"))
        val requestJson = com.google.gson.JsonParser.parseString(postedBody).asJsonObject
        assertEquals("كاظم", requestJson.getAsJsonPrimitive("query").asString)
        assertEquals("WEB", requestJson.getAsJsonObject("context").getAsJsonObject("client").getAsJsonPrimitive("clientName").asString)
        assertEquals("1", postedHeaders["X-YouTube-Client-Name"])
        assertEquals("2.20261015.01.00", postedHeaders["X-YouTube-Client-Version"])
        assertEquals("INNERTUBE_FALLBACK", postedDiagnostics["search_strategy"])
        assertEquals("innertube-fallback-test", postedDiagnostics["operation_id"])
        assertFalse(postedDiagnostics.values.joinToString(" ").contains("كاظم"))
    }

    @Test
    fun usesInnertubeSearchWhenPageDoesNotExposeAnApiKey() = runTest {
        val html = """<html><script>
            ytcfg.set({"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.20261015.01.00","hl":"en","gl":"US"}}});
            ytInitialData = buildInitialData();
        </script></html>"""
        val response = """{"contents":{"videoRenderer":{"videoId":"noKeyVideo123","title":{"simpleText":"No key fallback video"}}}}"""
        var postedUrl = ""
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = html

            override suspend fun postJson(
                url: String,
                body: String,
                headers: Map<String, String>,
                diagnosticContext: Map<String, String>,
            ): String {
                postedUrl = url
                return response
            }
        }

        val result = YouTubeSearchProvider(client).search("test")

        assertEquals("noKeyVideo123", result.single().id)
        assertEquals("No key fallback video", result.single().title)
        assertEquals("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", postedUrl)
    }

    @Test
    fun reportsSafeShapeDetailsForUnsupportedNestedJsonParseFunction() = runTest {
        val html = """<html><script>
            window["ytInitialData"] = JSON.parse(makeSerializedPayload());
        </script></html>"""
        val error = runCatching {
            YouTubeSearchProvider(textClient(html)).search("secret query")
        }.exceptionOrNull()

        assertTrue(error is YouTubeSearchPayloadException)
        val payloadError = error as YouTubeSearchPayloadException
        assertEquals("json_parse", payloadError.assignmentValueKind)
        assertEquals("expression_decode", payloadError.parserFailureStage)
        assertEquals("nested_function_call", payloadError.expressionKind)
        assertEquals("unsupported_nested_function_call", payloadError.expressionFailureDetail)
        assertEquals("json_parse", payloadError.expressionWrapper)
        assertEquals("closing_paren", payloadError.jsonParseArgumentTermination)
        assertEquals(1, payloadError.jsonParseArgumentCount)
        assertTrue(payloadError.expressionLength > 0)
        assertTrue(payloadError.expressionStartOffset >= 0)
        assertTrue(payloadError.assignmentOffset >= 0)
        assertEquals(1, payloadError.assignmentAttempt)
        assertTrue(payloadError.responseMarkerProfile.contains("yt_initial_data"))
        assertFalse(payloadError.message.orEmpty().contains("secret query"))
        assertFalse(payloadError.toString().contains("makeSerializedPayload"))
    }

    @Test
    fun distinguishesUnresolvedStaticJsonParseIdentifier() = runTest {
        val html = """<html><script>ytInitialData = JSON.parse(serializedPayload);</script></html>"""
        val error = runCatching {
            YouTubeSearchProvider(textClient(html)).search("private phrase")
        }.exceptionOrNull()

        assertTrue(error is YouTubeSearchPayloadException)
        val payloadError = error as YouTubeSearchPayloadException
        assertEquals("identifier_reference", payloadError.expressionKind)
        assertEquals("no_prior_static_declaration", payloadError.expressionResolution)
        assertEquals("unresolved_static_identifier", payloadError.expressionFailureDetail)
        assertFalse(payloadError.message.orEmpty().contains("private phrase"))
    }

    @Test
    fun rejectsUnsupportedJavaScriptExpressionWithoutScrapingUnrelatedObjects() = runTest {
        val html = """<html><script>
            var ytInitialData = createInitialData();
            var unrelated = {"contents":{"videoRenderer":{"videoId":"wrong123","title":{"simpleText":"Do not use"}}}};
        </script></html>"""

        val error = runCatching {
            YouTubeSearchProvider(textClient(html)).search("invalid")
        }.exceptionOrNull()

        assertTrue(error is YouTubeSearchPayloadException)
        val payloadError = error as YouTubeSearchPayloadException
        assertEquals("initial_data_parse_failed", payloadError.payloadFailureReason)
        assertEquals("unsupported_expression", payloadError.assignmentValueKind)
        assertEquals("expression_decode", payloadError.parserFailureStage)
    }

    private fun textClient(html: String): HttpTextClient = object : HttpTextClient {
        override suspend fun get(url: String): String = html
    }
}
