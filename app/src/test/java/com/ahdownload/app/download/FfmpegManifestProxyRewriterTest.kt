package com.ahdownload.app.download

import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegManifestProxyRewriterTest {
    @Test
    fun hlsPlaylistRoutesSegmentAndEncryptionKeyThroughLoopback() {
        val localOrigin = "http://127.0.0.1:43210"
        val routed = mutableMapOf<String, String>()
        fun route(url: String): String {
            val id = (routed.size + 1).toString()
            routed[id] = url
            return "$localOrigin/u/$id"
        }

        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.example.net/keys/key.bin?sig=secret"
            #EXTINF:4.0,
            segment-001.ts
        """.trimIndent()

        val rewritten = FfmpegManifestProxyRewriter.rewriteHls(
            text = playlist,
            upstreamUrl = "https://media.example.net/live/master.m3u8?token=private",
            localOrigin = localOrigin,
            routeForUrl = ::route,
        )

        assertTrue(rewritten.contains(localOrigin))
        assertTrue(routed.values.contains("https://media.example.net/live/segment-001.ts"))
        assertTrue(routed.values.contains("https://cdn.example.net/keys/key.bin?sig=secret"))
        assertTrue(!rewritten.contains("sig=secret"))
        assertTrue(!rewritten.contains("token=private"))
    }

    @Test
    fun dashRewritesBaseUrlsAndDynamicSegmentTemplates() {
        val localOrigin = "http://127.0.0.1:43210"
        val routedUrls = mutableListOf<String>()
        val routedBases = mutableListOf<String>()
        fun route(url: String): String {
            routedUrls += url
            return "$localOrigin/u/${routedUrls.size}"
        }
        fun routeBase(url: String): String {
            routedBases += url
            return "$localOrigin/b/${routedBases.size}/"
        }

        val number = "${'$'}Number${'$'}"
        val manifest = """
            <MPD>
              <BaseURL>https://cdn.example.net/root/</BaseURL>
              <SegmentTemplate
                initialization="https://video.example.net/dash/init.mp4?token=opaque"
                media="https://video.example.net/dash/segment-$number$.m4s?token=opaque" />
            </MPD>
        """.trimIndent()

        val rewritten = FfmpegManifestProxyRewriter.rewriteDash(
            text = manifest,
            upstreamUrl = "https://video.example.net/dash/manifest.mpd",
            localOrigin = localOrigin,
            routeForUrl = ::route,
            routeForBase = ::routeBase,
        )

        assertTrue(rewritten.contains(localOrigin))
        assertTrue(routedBases.contains("https://cdn.example.net/root/"))
        assertTrue(routedBases.contains("https://video.example.net/dash/"))
        assertTrue(routedUrls.contains("https://video.example.net/dash/init.mp4?token=opaque"))
        assertTrue(rewritten.contains("segment-$number$.m4s?token=opaque"))
        assertTrue(!rewritten.contains("https://video.example.net/dash/init.mp4"))
    }
}
