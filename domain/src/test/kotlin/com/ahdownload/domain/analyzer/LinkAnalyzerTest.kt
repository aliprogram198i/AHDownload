package com.ahdownload.domain.analyzer

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkAnalyzerTest {
    private val analyzer = LinkAnalyzer()

    @Test
    fun recognizesYouTube() {
        val result = analyzer.analyze("https://youtu.be/example")
        assertEquals(MediaPlatform.YouTube, result?.platform)
        assertEquals(MediaKind.Unknown, result?.kind)
    }

    @Test
    fun recognizesSupportedPlatforms() {
        assertEquals(
            MediaPlatform.Instagram,
            analyzer.analyze("https://www.instagram.com/reel/example/")?.platform,
        )
        assertEquals(
            MediaPlatform.Facebook,
            analyzer.analyze("https://fb.watch/example/")?.platform,
        )
        assertEquals(
            MediaPlatform.TikTok,
            analyzer.analyze("https://www.tiktok.com/@user/video/123")?.platform,
        )
        assertEquals(
            MediaPlatform.X,
            analyzer.analyze("https://x.com/user/status/123")?.platform,
        )
    }

    @Test
    fun recognizesCreatorPrefixedSnapchatSpotlightAsVideo() {
        val result = analyzer.analyze(
            "https://www.snapchat.com/@anjulaeditz/spotlight/W7_EDlXWTBiXAEEniNoMPwAAYYmZiZ2J2a3VhAZgD5F41AZgD5FXFAAAAAQ",
        )
        assertEquals(MediaPlatform.Snapchat, result?.platform)
        assertEquals(MediaKind.Video, result?.kind)
    }

    @Test
    fun extractsUrlFromSharedTextAndNormalizesMobileYouTube() {
        val result = analyzer.analyze("شاهد هذا: https://m.youtube.com/watch?v=abc123 !!!")
        assertEquals(MediaPlatform.YouTube, result?.platform)
        assertEquals("https://m.youtube.com/watch?v=abc123", result?.normalizedUrl)
    }

    @Test
    fun unwrapsRedirectTarget() {
        val result = analyzer.analyze(
            "https://www.google.com/url?sa=t&url=https%3A%2F%2Fyoutu.be%2Fabc123",
        )
        assertEquals(MediaPlatform.YouTube, result?.platform)
        assertEquals("https://youtu.be/abc123", result?.normalizedUrl)
    }

    @Test
    fun detectsMediaTypeFromQueryHint() {
        val result = analyzer.analyze("https://cdn.example.com/resource?id=7&mime=video%2Fmp4")
        assertEquals(MediaPlatform.DirectMedia, result?.platform)
        assertEquals(MediaKind.Video, result?.kind)
    }

    @Test
    fun recognizesDirectMediaByType() {
        assertEquals(
            MediaPlatform.DirectMedia,
            analyzer.analyze("https://cdn.example.com/video.mp4")?.platform,
        )
        assertEquals(
            MediaKind.Video,
            analyzer.analyze("https://cdn.example.com/video.mp4")?.kind,
        )
        assertEquals(
            MediaPlatform.DirectMedia,
            analyzer.analyze("https://cdn.example.com/audio.m4a")?.platform,
        )
        assertEquals(
            MediaKind.Audio,
            analyzer.analyze("https://cdn.example.com/audio.m4a")?.kind,
        )
        assertEquals(
            MediaPlatform.DirectMedia,
            analyzer.analyze("https://cdn.example.com/image.webp")?.platform,
        )
        assertEquals(
            MediaKind.Image,
            analyzer.analyze("https://cdn.example.com/image.webp")?.kind,
        )
    }

    @Test
    fun recognizesDirectHlsDashAndOgvMediaLinks() {
        listOf(
            "https://cdn.example.com/stream.m3u8",
            "https://cdn.example.com/stream.mpd",
            "https://cdn.example.com/movie.ogv",
        ).forEach { url ->
            val result = analyzer.analyze(url)
            assertEquals(MediaPlatform.DirectMedia, result?.platform)
            assertEquals(MediaKind.Video, result?.kind)
        }
    }


    @Test
    fun routesLookalikeHostsToWebWithoutMisclassifyingThemAsSocialPlatforms() {
        assertEquals(
            MediaPlatform.Web,
            analyzer.analyze("https://evil-youtube.com/video")?.platform,
        )
        assertEquals(
            MediaPlatform.Web,
            analyzer.analyze("https://instagram.com.evil.example/reel/123")?.platform,
        )
    }

    @Test
    fun recognizesCaseInsensitiveSchemeAndHost() {
        val result = analyzer.analyze("  HTTPS://WWW.YOUTUBE.COM/watch?v=123  ")
        assertEquals(MediaPlatform.YouTube, result?.platform)
        assertEquals("  HTTPS://WWW.YOUTUBE.COM/watch?v=123  ", result?.originalUrl)
        assertEquals("HTTPS://WWW.YOUTUBE.COM/watch?v=123", result?.normalizedUrl)
    }

    @Test
    fun allowsHttpWebAndDirectMediaButKeepsSocialPlatformsHttpsOnly() {
        assertNull(analyzer.analyze("http://youtu.be/example"))
        assertEquals(MediaPlatform.Web, analyzer.analyze("http://example.com/article")?.platform)
        assertEquals(MediaPlatform.DirectMedia, analyzer.analyze("http://cdn.example.com/video.mp4")?.platform)
    }

    @Test
    fun addsHttpsToSchemeLessUrlsFromClipboard() {
        val youtube = analyzer.analyze("www.youtube.com/watch?v=abc123")
        assertEquals(MediaPlatform.YouTube, youtube?.platform)
        assertEquals("https://www.youtube.com/watch?v=abc123", youtube?.normalizedUrl)

        val directMedia = analyzer.analyze("cdn.example.com/video.mp4")
        assertEquals(MediaPlatform.DirectMedia, directMedia?.platform)
        assertEquals("https://cdn.example.com/video.mp4", directMedia?.normalizedUrl)
    }

    @Test
    fun extractsSchemeLessUrlFromSharedText() {
        val result = analyzer.analyze("شاهد هذا: youtu.be/abc123.")
        assertEquals(MediaPlatform.YouTube, result?.platform)
        assertEquals("https://youtu.be/abc123", result?.normalizedUrl)
    }

    @Test
    fun doesNotRewriteExplicitUnsupportedSchemesAsHttps() {
        assertNull(analyzer.analyze("ftp://example.com/video.mp4"))
        assertNull(analyzer.analyze("file:///storage/emulated/0/video.mp4"))
    }

    @Test
    fun rejectsInvalidAndMalformedUrls() {
        assertNull(analyzer.analyze(""))
        assertNull(analyzer.analyze("   "))
        assertNull(analyzer.analyze("ftp://example.com/video.mp4"))
        assertNull(analyzer.analyze("not a url"))
        assertNull(analyzer.analyze("https:///missing-host"))
    }

    @Test
    fun keepsUnknownMediaKindForNonMediaPaths() {
        val result = analyzer.analyze("https://cdn.example.com/resource?id=123")
        assertEquals(MediaPlatform.Web, result?.platform)
        assertEquals(MediaKind.Unknown, result?.kind)
    }

    @Test
    fun doesNotClassifyIncidentalWordsAsMediaHints() {
        listOf(
            "https://cdn.example.com/resource.video",
            "https://cdn.example.com/resource?type=video-game",
            "https://cdn.example.com/resource?format=audio-book",
            "https://cdn.example.com/resource?ext=imagery",
        ).forEach { url ->
            val result = analyzer.analyze(url)
            assertEquals(url, MediaPlatform.Web, result?.platform)
            assertEquals(url, MediaKind.Unknown, result?.kind)
        }
    }

    @Test
    fun recognizesStrictMimeHintsAndKnownFormatHints() {
        val audio = analyzer.analyze("https://cdn.example.com/resource?mime=audio%2Fmpeg")
        assertEquals(MediaPlatform.DirectMedia, audio?.platform)
        assertEquals(MediaKind.Audio, audio?.kind)

        val video = analyzer.analyze("https://cdn.example.com/resource?format=.mp4")
        assertEquals(MediaPlatform.DirectMedia, video?.platform)
        assertEquals(MediaKind.Video, video?.kind)

        val image = analyzer.analyze("https://cdn.example.com/resource?content_type=image%2Fwebp")
        assertEquals(MediaPlatform.DirectMedia, image?.platform)
        assertEquals(MediaKind.Image, image?.kind)
    }

    @Test
    fun unwrapsRedirectExactlyOnceAndPreservesEscapedTargetQueryValues() {
        val result = analyzer.analyze(
            "https://www.google.com/url?url=https%3A%2F%2Fcdn.example.com%2Fvideo.mp4%3Ftoken%3Da%252Bb%2525c",
        )
        assertEquals(MediaPlatform.DirectMedia, result?.platform)
        assertEquals(
            "https://cdn.example.com/video.mp4?token=a%2Bb%25c",
            result?.normalizedUrl,
        )
    }

    @Test
    fun refusesToUnwrapNonHttpRedirectTargets() {
        val input = "https://www.google.com/url?url=javascript%3Aalert%281%29"
        val result = analyzer.analyze(input)
        assertEquals(MediaPlatform.Web, result?.platform)
        assertEquals(input, result?.normalizedUrl)
    }

    @Test
    fun trimsInputWithoutChangingOriginal() {
        val result = analyzer.analyze("  https://instagram.com/reel/example  ")
        assertEquals("  https://instagram.com/reel/example  ", result?.originalUrl)
        assertEquals("https://instagram.com/reel/example", result?.normalizedUrl)
    }

    @Test
    fun recognizesTikTokShortMobilePhotoStoryAndLiveUrlFamilies() {
        val urls = listOf(
            "https://vm.tiktok.com/ZMYS13VCF",
            "https://vt.tiktok.com/ZS8eY5678/",
            "https://m.tiktok.com/@scout2015/video/6718335390845095173?is_from_webapp=1",
            "https://www.tiktok.com/t/ZMYS13VCF/",
            "https://www.tiktok.com/@creator/photo/7345678901234567890",
            "https://www.tiktok.com/@creator/story/7345678901234567890",
            "https://www.tiktok.com/@creator/live",
        )
        urls.forEach { url ->
            assertEquals(url, MediaPlatform.TikTok, analyzer.analyze(url)?.platform)
        }
    }

    @Test
    fun canonicalizesMobileTikTokVideoHostWithoutChangingOriginalUrlOrQuery() {
        val input = "https://m.tiktok.com/@scout2015/video/6718335390845095173?is_from_webapp=1&sender_device=pc"
        val result = analyzer.analyze(input)

        assertEquals(MediaPlatform.TikTok, result?.platform)
        assertEquals(input, result?.originalUrl)
        assertEquals(
            "https://www.tiktok.com/@scout2015/video/6718335390845095173?is_from_webapp=1&sender_device=pc",
            result?.normalizedUrl,
        )
    }

    @Test
    fun doesNotRewriteMobilePhotoStoryLiveOrOfficialShortLinkRoutes() {
        val urls = listOf(
            "https://m.tiktok.com/@creator/photo/7345678901234567890",
            "https://m.tiktok.com/@creator/story/7345678901234567890",
            "https://m.tiktok.com/@creator/live",
            "https://vm.tiktok.com/ZMYS13VCF",
            "https://vt.tiktok.com/ZS8eY5678/",
        )

        urls.forEach { url ->
            assertEquals(url, MediaPlatform.TikTok, analyzer.analyze(url)?.platform)
            assertEquals(url, analyzer.analyze(url)?.normalizedUrl)
        }
    }

}
