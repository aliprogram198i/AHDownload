package com.ahdownload.app.download

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegManifestHttpsBridgeTest {

    @Test
    fun nestedDashBaseUrlsResolveRelativeToParentAndRetainSignedQuery() {
        val fixture = NestedDashFixture()
        fixture.start()
        try {
            FfmpegManifestHttpsBridge(emptyMap(), null).use { bridge ->
                val source = "http://127.0.0.1:" + fixture.port +
                    "/root/manifest.mpd?sig=test-signature"
                val rewritten = java.net.URL(bridge.manifestUrl(source)).readText()

                val baseUrls = Regex(
                    """(?is)<(?:[\w.-]+:)?BaseURL\b[^>]*>(.*?)</(?:[\w.-]+:)?BaseURL\s*>""",
                ).findAll(rewritten).map { it.groupValues[1].trim() }.toList()
                assertEquals(
                    "Expected root, period, adaptation, and representation BaseURL values",
                    4,
                    baseUrls.size,
                )

                val representationBase = baseUrls.last()
                assertTrue(
                    "Representation BaseURL should be mapped to the loopback bridge",
                    representationBase.startsWith("http://127.0.0.1:") &&
                        representationBase.contains("/base/") &&
                        representationBase.endsWith("/"),
                )

                val segment = java.net.URL(representationBase + "segment-1.m4s").readText()
                assertEquals("segment-body", segment)
                assertEquals(
                    "/root/first/second/third/fourth/segment-1.m4s",
                    fixture.requests.last().substringBefore('?'),
                )
                assertEquals("sig=test-signature", fixture.requests.last().substringAfter('?', ""))
            }
        } finally {
            fixture.close()
        }
    }

    private class NestedDashFixture : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val requests = CopyOnWriteArrayList<String>()
        @Volatile private var stopped = false
        private var listener: Thread? = null

        fun start() {
            listener = thread(isDaemon = true, name = "ahdownload-dash-fixture") {
                while (!stopped) {
                    try {
                        val socket = server.accept()
                        thread(isDaemon = true, name = "ahdownload-dash-fixture-request") {
                            handle(socket)
                        }
                    } catch (_: Exception) {
                        if (stopped) break
                    }
                }
            }
        }

        private fun handle(socket: Socket) {
            socket.use { client ->
                client.soTimeout = 5_000
                val reader = BufferedReader(
                    InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1),
                )
                val requestLine = reader.readLine() ?: return
                val target = requestLine.split(' ').getOrNull(1) ?: return
                while (reader.readLine()?.isNotEmpty() == true) Unit
                requests += target

                val mediaTemplate = "\$Number\$"
                val mpd = """<?xml version="1.0" encoding="UTF-8"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><BaseURL>first/</BaseURL><Period><BaseURL>second/</BaseURL><AdaptationSet><BaseURL>third/</BaseURL><Representation id="v"><BaseURL>fourth/</BaseURL><SegmentTemplate initialization="init.mp4" media="segment-__TEMPLATE__.m4s"/></Representation></AdaptationSet></Period></MPD>"""
                    .replace("__TEMPLATE__", mediaTemplate)
                val response = when {
                    target.startsWith("/root/manifest.mpd?") ->
                        Triple(200, "application/dash+xml", mpd)
                    target.startsWith("/root/first/second/third/fourth/segment-1.m4s?") ->
                        Triple(200, "video/iso.segment", "segment-body")
                    else -> Triple(404, "text/plain", "not-found")
                }
                val bytes = response.third.toByteArray(StandardCharsets.UTF_8)
                val output = client.getOutputStream()
                val reason = if (response.first == 200) "OK" else "Not Found"
                output.write(
                    ("HTTP/1.1 " + response.first + " " + reason + "\r\n")
                        .toByteArray(StandardCharsets.ISO_8859_1),
                )
                output.write(
                    ("Content-Type: " + response.second + "\r\n")
                        .toByteArray(StandardCharsets.ISO_8859_1),
                )
                output.write(
                    ("Content-Length: " + bytes.size + "\r\n")
                        .toByteArray(StandardCharsets.ISO_8859_1),
                )
                output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.write(bytes)
                output.flush()
            }
        }

        override fun close() {
            stopped = true
            runCatching { server.close() }
            runCatching { listener?.join(300) }
        }
    }
}
