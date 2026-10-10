package com.ahdownload.domain.resolver.youtube

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.ResolverRequest
import com.ahdownload.domain.resolver.ResolverResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeResolverTest {
    @Test
    fun resolvesVideoUsingInjectedClientAndFiltersRequestedKind() = runBlocking {
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = """
                var ytInitialPlayerResponse = {
                  "videoDetails": {"title":"Resolver Test","lengthSeconds":"10"},
                  "streamingData": {
                    "formats": [
                      {
                        "itag":"18",
                        "mimeType":"video/mp4; codecs=\\"avc1.42001E, mp4a.40.2\\"",
                        "width":640,
                        "height":360,
                        "url":"https://cdn.example.com/18"
                      }
                    ],
                    "adaptiveFormats": [
                      {
                        "itag":"140",
                        "mimeType":"audio/mp4; codecs=\\"mp4a.40.2\\"",
                        "bitrate":128000,
                        "url":"https://cdn.example.com/140"
                      }
                    ]
                  }
                };
            """.trimIndent()
        }

        val resolver = YouTubeResolver(client)
        val result = resolver.resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/example",
                    normalizedUrl = "https://youtu.be/example",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
                requestedKind = MediaKind.Audio,
            ),
        )

        assertTrue(result is ResolverResult.Success)
        result as ResolverResult.Success
        assertEquals("Resolver Test", result.title)
        assertEquals(1, result.candidates.size)
        assertEquals(MediaKind.Audio, result.candidates.single().format.kind)
    }

    @Test
    fun rejectsNonYouTubeRequest() = runBlocking {
        val resolver = YouTubeResolver(object : HttpTextClient {
            override suspend fun get(url: String): String = ""
        })

        val result = resolver.resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://example.com/video",
                    normalizedUrl = "https://example.com/video",
                    platform = MediaPlatform.Unknown,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertEquals(
            com.ahdownload.domain.resolver.FailureCode.UnsupportedPlatform,
            (result as ResolverResult.Failure).code,
        )
    }

    @Test
    fun fallsBackToYouTubePlayerApiWhenHtmlHasNoPlayerResponse() = runBlocking {
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = """
                {"INNERTUBE_API_KEY":"test-key","INNERTUBE_CONTEXT":{"client":{"clientName":"ANDROID","clientVersion":"19.09.37"}}}
            """.trimIndent()

            override suspend fun postJson(url: String, body: String): String = """
                {
                  "videoDetails":{"title":"Fallback Test","lengthSeconds":"8"},
                  "playabilityStatus":{"status":"OK"},
                  "streamingData":{"formats":[
                    {"itag":"18","mimeType":"video/mp4; codecs=\\"avc1.42001E, mp4a.40.2\\"","width":640,"height":360,"url":"https://cdn.example.com/fallback"}
                  ]}
                }
            """.trimIndent()
        }

        val resolver = YouTubeResolver(client)
        val result = resolver.resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/fallback1",
                    normalizedUrl = "https://youtu.be/fallback1",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        assertEquals("Fallback Test", (result as ResolverResult.Success).title)
    }

    @Test
    fun addsEmbeddableFallbackSourcesEvenWhenPrimaryPlayerSucceeds() = runBlocking {
        var postCount = 0
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = """
                {"INNERTUBE_API_KEY":"test-key","INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"1"}}}
            """.trimIndent()

            override suspend fun postJson(url: String, body: String): String {
                postCount++
                return if (postCount == 1) {
                    """
                    {
                      "videoDetails":{"title":"Primary","lengthSeconds":"8"},
                      "playabilityStatus":{"status":"OK"},
                      "streamingData":{"formats":[
                        {"itag":"18","mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","width":640,"height":360,"url":"https://rr1---sn.googlevideo.com/videoplayback?itag=18&mime=video%2Fmp4&source=primary"}
                      ]}
                    }
                    """.trimIndent()
                } else {
                    """
                    {
                      "videoDetails":{"title":"Embedded","lengthSeconds":"8"},
                      "playabilityStatus":{"status":"OK"},
                      "streamingData":{"formats":[
                        {"itag":"22","mimeType":"video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"","width":1280,"height":720,"url":"https://rr1---sn.googlevideo.com/videoplayback?itag=22&mime=video%2Fmp4&source=embedded"},
                        {"itag":"140","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"url":"https://rr1---sn.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4&source=embedded-audio"}
                      ]}
                    }
                    """.trimIndent()
                }
            }
        }

        val result = YouTubeResolver(client).resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/embedtest",
                    normalizedUrl = "https://youtu.be/embedtest",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        result as ResolverResult.Success
        assertTrue(result.candidates.any { it.id == "18" })
        assertTrue(result.candidates.any { it.id == "embedded-22" })
        assertTrue(result.candidates.any { it.id == "embedded-140" && it.format.kind == MediaKind.Audio })
        assertTrue(postCount >= 2)
    }

    @Test
    fun recoversCipheredVideoQualitiesAndAudioFromObservedWebViewUrls() = runBlocking {
        val video1080Url =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=137&mime=video%2Fmp4&pot=browser-1080"
        val video720Url =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=136&mime=video%2Fmp4&pot=browser-720"
        val audioUrl =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4&pot=browser-audio"
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String =
                throw IllegalStateException("Sign in to confirm you're not a bot")
        }
        val session = object : YouTubeSessionProvider {
            override suspend fun snapshot(url: String) = YouTubeSessionSnapshot(
                cookies = null,
                videoUrls = listOf(video1080Url, video720Url),
                audioUrls = listOf(audioUrl),
                playerResponse = """
                    {
                      "videoDetails":{"title":"Cipher Session Test","lengthSeconds":"10"},
                      "playabilityStatus":{"status":"OK"},
                      "streamingData":{
                        "adaptiveFormats":[
                          {
                            "itag":"137",
                            "mimeType":"video/mp4; codecs=\"avc1.640028\"",
                            "width":1920,
                            "height":1080,
                            "fps":30,
                            "bitrate":4500000,
                            "signatureCipher":"url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fitag%3D137&s=encrypted"
                          },
                          {
                            "itag":"136",
                            "mimeType":"video/mp4; codecs=\"avc1.4d401f\"",
                            "width":1280,
                            "height":720,
                            "fps":30,
                            "bitrate":2500000,
                            "signatureCipher":"url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fitag%3D136&s=encrypted"
                          },
                          {
                            "itag":"140",
                            "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                            "bitrate":128000,
                            "cipher":"url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fitag%3D140&s=encrypted"
                          }
                        ]
                      }
                    }
                """.trimIndent(),
                authenticated = false,
                browserRequestHeaders = mapOf(
                    video1080Url to mapOf("Referer" to "https://www.youtube.com/"),
                    video720Url to mapOf("Referer" to "https://www.youtube.com/"),
                    audioUrl to mapOf("Referer" to "https://www.youtube.com/"),
                ),
                browserMediaObservedCount = 3,
            )
        }

        val result = YouTubeResolver(client, sessionProvider = session).resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/cipher-session-test",
                    normalizedUrl = "https://youtu.be/cipher-session-test",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        val candidates = (result as ResolverResult.Success).candidates
        assertEquals(3, candidates.size)
        assertEquals(video1080Url, candidates.first { it.format.height == 1080 }.sourceUrl)
        assertEquals(video720Url, candidates.first { it.format.height == 720 }.sourceUrl)
        assertEquals(128, candidates.first { it.format.kind == MediaKind.Audio }.format.bitrateKbps)
        assertTrue(candidates.all {
            it.sourceContext == com.ahdownload.domain.resolver.MediaSourceContext.BROWSER_OBSERVED
        })
    }

    @Test
    fun restoresQualityAndAudioMetadataWhenOnlyObservedWebViewUrlsAreAvailable() = runBlocking {
        val video1080Url =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=137&mime=video%2Fmp4&source=browser"
        val video720Url =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=136&mime=video%2Fmp4&source=browser"
        // Deliberately place the audio URL in videoUrls and omit MIME metadata.
        // The itag catalogue must still classify it as audio, not a video card.
        val audio160Url =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=251&source=browser"
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String =
                throw IllegalStateException("Sign in to confirm you're not a bot")
        }
        val session = object : YouTubeSessionProvider {
            override suspend fun snapshot(url: String) = YouTubeSessionSnapshot(
                cookies = null,
                videoUrls = listOf(video1080Url, video720Url, audio160Url),
                audioUrls = emptyList(),
                authenticated = false,
                browserRequestHeaders = mapOf(
                    video1080Url to mapOf("Referer" to "https://www.youtube.com/"),
                    video720Url to mapOf("Referer" to "https://www.youtube.com/"),
                    audio160Url to mapOf("Referer" to "https://www.youtube.com/"),
                ),
                browserMediaObservedCount = 3,
            )
        }

        val result = YouTubeResolver(client, sessionProvider = session).resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/observed-itag-metadata",
                    normalizedUrl = "https://youtu.be/observed-itag-metadata",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        val candidates = (result as ResolverResult.Success).candidates
        assertEquals(3, candidates.size)

        val video1080 = candidates.first { it.sourceUrl == video1080Url }
        assertEquals(MediaKind.Video, video1080.format.kind)
        assertEquals(1080, video1080.format.height)
        assertEquals(com.ahdownload.domain.resolver.MediaContainer.Mp4, video1080.format.container)
        assertEquals(false, video1080.format.hasAudio)

        val video720 = candidates.first { it.sourceUrl == video720Url }
        assertEquals(MediaKind.Video, video720.format.kind)
        assertEquals(720, video720.format.height)

        val audio160 = candidates.first { it.sourceUrl == audio160Url }
        assertEquals(MediaKind.Audio, audio160.format.kind)
        assertEquals(160, audio160.format.bitrateKbps)
        assertEquals("opus", audio160.format.audioCodec)
        assertEquals(com.ahdownload.domain.resolver.MediaContainer.Webm, audio160.format.container)
    }

    @Test
    fun fallsBackToWebViewSessionCandidatesAfterBotCheck() = runBlocking {
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String =
                throw IllegalStateException("Sign in to confirm you’re not a bot")
        }
        val session = object : YouTubeSessionProvider {
            override suspend fun snapshot(url: String): YouTubeSessionSnapshot =
                YouTubeSessionSnapshot(
                    cookies = "SID=redacted",
                    videoUrls = listOf("https://cdn.example.com/video.mp4"),
                    audioUrls = listOf("https://cdn.example.com/audio.m4a"),
                    authenticated = true,
                    browserRequestHeaders = mapOf(
                        "https://cdn.example.com/video.mp4" to mapOf(
                            "Referer" to "https://www.youtube.com/",
                            "Origin" to "https://www.youtube.com",
                            "Accept" to "*/*",
                        ),
                        "https://cdn.example.com/audio.m4a" to mapOf(
                            "Referer" to "https://www.youtube.com/",
                        ),
                    ),
                    browserMediaObservedCount = 2,
                    browserPoTokenObserved = false,
                )
        }
        val resolver = YouTubeResolver(
            httpClient = client,
            sessionProvider = session,
        )
        val result = resolver.resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/example",
                    normalizedUrl = "https://youtu.be/example",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        result as ResolverResult.Success
        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.any { it.format.kind == MediaKind.Video })
        assertTrue(result.candidates.any { it.format.kind == MediaKind.Audio })
        assertTrue(result.candidates.all { it.requestHeaders["Referer"] == "https://www.youtube.com/" })
        assertTrue(result.candidates.all { it.requestHeaders["Cookie"] == "SID=redacted" })
        assertTrue(result.candidates.first { it.format.kind == MediaKind.Video }.requestHeaders["Accept"] == "*/*")
        assertTrue(result.candidates.first { it.format.kind == MediaKind.Audio }.requestHeaders["Referer"] == "https://www.youtube.com/")
    }

    @Test
    fun preservesCommonVideoCodecsAndContainers() = runBlocking {
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = """
                {
                  "videoDetails":{"title":"Codec Test","lengthSeconds":"12"},
                  "streamingData":{
                    "formats":[
                      {
                        "itag":"999",
                        "mimeType":"video/webm; codecs=\"vp9, opus\"",
                        "width":1920,
                        "height":1080,
                        "url":"https://cdn.example.com/vp9"
                      },
                      {
                        "itag":"1000",
                        "mimeType":"video/mp4; codecs=\"av01.0.08M.08, mp4a.40.2\"",
                        "width":1920,
                        "height":1080,
                        "url":"https://cdn.example.com/av1"
                      }
                    ],
                    "adaptiveFormats":[
                      {
                        "itag":"1001",
                        "mimeType":"audio/webm; codecs=\"opus\"",
                        "bitrate":160000,
                        "url":"https://cdn.example.com/opus"
                      }
                    ]
                  }
                }
            """.trimIndent()
        }

        val result = YouTubeResolver(client).resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/codecs1",
                    normalizedUrl = "https://youtu.be/codecs1",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        result as ResolverResult.Success

        val vp9 = result.candidates.first { it.id == "999" }
        assertEquals(com.ahdownload.domain.resolver.MediaContainer.Webm, vp9.format.container)
        assertEquals("vp9", vp9.format.videoCodec)
        assertEquals("opus", vp9.format.audioCodec)

        val av1 = result.candidates.first { it.id == "1000" }
        assertEquals(com.ahdownload.domain.resolver.MediaContainer.Mp4, av1.format.container)
        assertEquals("av01.0.08M.08", av1.format.videoCodec)
        assertEquals("mp4a.40.2", av1.format.audioCodec)

        val opus = result.candidates.first { it.id == "1001" }
        assertEquals(com.ahdownload.domain.resolver.MediaContainer.Webm, opus.format.container)
        assertEquals("opus", opus.format.audioCodec)
    }


    @Test
    fun prefersCapturedBrowserMediaWhenPlayerUrlCannotBeAlignedByItag() = runBlocking {
        val playerUrl = "https://rr1---sn.googlevideo.com/videoplayback?itag=136&mime=video%2Fmp4&source=youtube"
        val browserUrl = "https://rr1---sn.googlevideo.com/videoplayback?itag=136&mime=video%2Fmp4&source=youtube&pot=redacted"
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String =
                throw IllegalStateException("Sign in to confirm you're not a bot")
        }
        val session = object : YouTubeSessionProvider {
            override suspend fun snapshot(url: String): YouTubeSessionSnapshot =
                YouTubeSessionSnapshot(
                    cookies = null,
                    videoUrls = listOf(browserUrl),
                    audioUrls = emptyList(),
                    playerResponse = """
                        {
                          "videoDetails":{"title":"Browser Source Test","lengthSeconds":"8"},
                          "playabilityStatus":{"status":"OK"},
                          "streamingData":{"formats":[
                            {"itag":"136","mimeType":"video/mp4; codecs=\\"avc1.4d401f\\"","width":1280,"height":720,"url":"$playerUrl"}
                          ]}
                        }
                    """.trimIndent(),
                    authenticated = false,
                )
        }

        val result = YouTubeResolver(
            httpClient = client,
            sessionProvider = session,
        ).resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/browser-alignment",
                    normalizedUrl = "https://youtu.be/browser-alignment",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        result as ResolverResult.Success
        assertEquals(1, result.candidates.size)
        assertEquals(browserUrl, result.candidates.single().sourceUrl)
    }


    @Test
    fun attachesSessionContextWithoutCloningBrowserPoTokenToOtherCandidates() = runBlocking {
        val playerUrl = "https://rr1---sn.googlevideo.com/videoplayback?itag=18&mime=video%2Fmp4&source=player"
        val browserUrl = "https://rr1---sn.googlevideo.com/videoplayback?itag=249&mime=video%2Fwebm&source=browser&pot=pot-redacted"

        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = """
                {
                  "videoDetails":{"title":"Session Context Test","lengthSeconds":"8"},
                  "streamingData":{
                    "formats":[
                      {
                        "itag":"18",
                        "mimeType":"video/mp4; codecs=\"avc1.4d401f, mp4a.40.2\"",
                        "width":640,
                        "height":360,
                        "url":"$playerUrl"
                      }
                    ]
                  }
                }
            """.trimIndent()
        }

        val session = object : YouTubeSessionProvider {
            override suspend fun snapshot(url: String): YouTubeSessionSnapshot =
                YouTubeSessionSnapshot(
                    cookies = "SID=redacted",
                    videoUrls = listOf(browserUrl),
                    audioUrls = emptyList(),
                    authenticated = true,
                    userAgent = "Mozilla/5.0 (Linux; Android 15)",
                    browserPoTokenObserved = true,
                    browserPoToken = "pot-redacted",
                    browserRequestHeaders = mapOf(
                        browserUrl to mapOf(
                            "Referer" to "https://www.youtube.com/",
                            "Origin" to "https://www.youtube.com",
                            "X-YouTube-Client-Name" to "1",
                            "X-YouTube-Client-Version" to "2",
                        ),
                    ),
                    browserMediaObservedCount = 1,
                )
        }

        val result = YouTubeResolver(
            httpClient = client,
            sessionProvider = session,
        ).resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/session-context",
                    normalizedUrl = "https://youtu.be/session-context",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        result as ResolverResult.Success
        val player = result.candidates.single()

        assertEquals(playerUrl, player.sourceUrl)
        assertTrue(!player.sourceUrl.contains("pot=pot-redacted"))
        assertEquals(com.ahdownload.domain.resolver.MediaSourceContext.RESOLVER_GENERATED, player.sourceContext)
        assertEquals("SID=redacted", player.requestHeaders["Cookie"])
        assertEquals("https://www.youtube.com/", player.requestHeaders["Referer"])
    }

    @Test
    fun preservesExactBrowserUrlAndMarksBrowserObservedSource() = runBlocking {
        val browserUrl =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4&source=browser&pot=pot-test"

        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = """
                {
                  "videoDetails":{"title":"Browser Exact URL Test","lengthSeconds":"8"},
                  "streamingData":{
                    "adaptiveFormats":[
                      {
                        "itag":"140",
                        "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                        "bitrate":128000,
                        "url":"https://rr1---sn.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4&source=player"
                      }
                    ]
                  }
                }
            """.trimIndent()
        }
        val session = object : YouTubeSessionProvider {
            override suspend fun snapshot(url: String) = YouTubeSessionSnapshot(
                cookies = null,
                videoUrls = emptyList(),
                audioUrls = listOf(browserUrl),
                authenticated = false,
                browserRequestHeaders = mapOf(
                    browserUrl to mapOf(
                        "Referer" to "https://www.youtube.com/",
                        "X-YouTube-Client-Name" to "56",
                        "X-YouTube-Client-Version" to "2.20260708.00.00",
                    ),
                ),
                browserMediaObservedCount = 1,
                browserPoTokenObserved = true,
                browserPoToken = "pot-test",
            )
        }

        val result = YouTubeResolver(
            httpClient = client,
            sessionProvider = session,
        ).resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://youtu.be/browser-exact",
                    normalizedUrl = "https://youtu.be/browser-exact",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        result as ResolverResult.Success
        val candidate = result.candidates.single()

        assertEquals(browserUrl, candidate.sourceUrl)
        assertEquals(com.ahdownload.domain.resolver.MediaSourceContext.BROWSER_OBSERVED, candidate.sourceContext)
        assertEquals("56", candidate.requestHeaders["X-YouTube-Client-Name"])
        assertEquals("2.20260708.00.00", candidate.requestHeaders["X-YouTube-Client-Version"])
    }


    @Test
    fun triesAndroidPlayerWhenWebAndEmbeddedPlayersHaveNoUsableFormats() = runBlocking {
        val clients = mutableListOf<String>()
        val html = """{"INNERTUBE_API_KEY":"test-key","INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2"}}}"""
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = html

            override suspend fun postJson(url: String, body: String): String {
                val name = Regex("\\"clientName\\":\\"([^\\"]+)\\"")
                    .find(body)?.groupValues?.get(1).orEmpty()
                clients += name
                return when (name) {
                    "ANDROID" -> """{
                        "videoDetails":{"title":"Android fallback","lengthSeconds":"12"},
                        "playabilityStatus":{"status":"OK"},
                        "streamingData":{"formats":[
                          {"itag":"18","mimeType":"video/mp4; codecs=\\"avc1.42001E, mp4a.40.2\\"","width":640,"height":360,
                           "url":"https://rr1---sn.googlevideo.com/videoplayback?itag=18&mime=video%2Fmp4&source=android"}
                        ]}
                    }"""
                    "WEB_EMBEDDED_PLAYER" -> """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"This video is unavailable"}}"""
                    else -> """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable"}}"""
                }
            }
        }

        val result = YouTubeResolver(client).resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://www.youtube.com/watch?v=abcdefghijk",
                    normalizedUrl = "https://www.youtube.com/watch?v=abcdefghijk",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Success)
        val success = result as ResolverResult.Success
        assertEquals("Android fallback", success.title)
        assertTrue(success.candidates.any { it.sourceUrl.contains("source=android") })
        assertEquals(listOf("WEB", "WEB_EMBEDDED_PLAYER", "ANDROID"), clients)
    }

    @Test
    fun doesNotExposeUnclassifiedGoogleVideoProtocolRequestsAsRawVideoCandidates() = runBlocking {
        val clients = mutableListOf<String>()
        val html = """{"INNERTUBE_API_KEY":"test-key","INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2"}}}"""
        val client = object : HttpTextClient {
            override suspend fun get(url: String): String = html

            override suspend fun postJson(url: String, body: String): String {
                val name = Regex("\\"clientName\\":\\"([^\\"]+)\\"")
                    .find(body)?.groupValues?.get(1).orEmpty()
                clients += name
                return """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable"}}"""
            }
        }
        val protocolUrls = listOf(
            "https://rr2.googlevideo.com/videoplayback?source=youtube&rn=1",
            "https://rr2.googlevideo.com/videoplayback?source=youtube&rn=2",
            "https://rr2.googlevideo.com/videoplayback?source=youtube&rn=3",
        )
        val session = object : YouTubeSessionProvider {
            override suspend fun snapshot(url: String) = YouTubeSessionSnapshot(
                cookies = null,
                videoUrls = protocolUrls,
                audioUrls = emptyList(),
                playerResponse = null,
                authenticated = false,
                browserMediaObservedCount = protocolUrls.size,
            )
        }

        val result = YouTubeResolver(client, sessionProvider = session).resolve(
            ResolverRequest(
                link = MediaLink(
                    originalUrl = "https://www.youtube.com/watch?v=abcdefghijk",
                    normalizedUrl = "https://www.youtube.com/watch?v=abcdefghijk",
                    platform = MediaPlatform.YouTube,
                    kind = MediaKind.Unknown,
                ),
            ),
        )

        assertTrue(result is ResolverResult.Failure)
        assertEquals(listOf("WEB", "WEB_EMBEDDED_PLAYER", "ANDROID"), clients)
    }

}
