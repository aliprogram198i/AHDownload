package com.ahdownload.app.download

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import org.xml.sax.InputSource
import org.w3c.dom.Node
import org.w3c.dom.Element
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.XMLConstants
import java.io.StringWriter
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adapts upstream HTTPS HLS/DASH to loopback HTTP for FFmpeg builds without the
 * native HTTPS protocol. Android's TLS-capable HttpURLConnection fetches remote
 * resources; media bodies stream through and byte-range requests are forwarded.
 */
internal class FfmpegManifestHttpsBridge(
    private val requestHeaders: Map<String, String>,
    private val cookie: String?,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : Closeable {
    private data class Target(val url: String, val forceManifest: Boolean = false)
    private enum class ManifestKind { HLS, DASH }

    private val closed = AtomicBoolean(false)
    private val ids = AtomicInteger(1)
    private val targets = ConcurrentHashMap<String, Target>()
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32)
    }
    private val executor = Executors.newCachedThreadPool { Thread(it, "ahdownload-manifest-proxy").apply { isDaemon = true } }
    private val root = "http://127.0.0.1:${server.localPort}"

    private val acceptThread = Thread({
        while (!closed.get()) {
            try {
                val client = server.accept()
                executor.execute { handleClient(client) }
            } catch (_: SocketException) {
                if (closed.get()) break
            } catch (_: IOException) {
                if (closed.get()) break
            }
        }
    }, "ahdownload-manifest-proxy-accept").apply { isDaemon = true; start() }

    fun manifestUrl(remoteUrl: String): String = addResource(remoteUrl, true)

    /** Used by the runtime smoke test to check an actual HTTPS-to-HTTP bridge. */
    fun resourceUrl(remoteUrl: String): String = addResource(remoteUrl, false)

    private fun addResource(url: String, forceManifest: Boolean): String {
        requireHttpUrl(url)
        val id = ids.getAndIncrement().toString(36)
        targets[id] = Target(url, forceManifest)

        // FFmpeg's HLS demuxer checks segment extensions against an allowlist.
        // Keep a safe extension hint on opaque proxy paths so .ts/.m4s/.mp4
        // segments remain recognizable without disabling that security check.
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val leaf = path.substringAfterLast('/')
        val extension = leaf.substringAfterLast('.', "")
            .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        val suffix = extension?.let { "/resource.$it" }.orEmpty()
        return "$root/resource/$id$suffix"
    }

    private fun addBase(url: String): String {
        requireHttpUrl(url)
        val id = ids.getAndIncrement().toString(36)
        targets[id] = Target(url)
        return "$root/base/$id/"
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1))
            val pieces = (reader.readLine() ?: return).split(' ', limit = 3)
            if (pieces.size < 2) { writeSimpleResponse(client, 400, "Bad Request"); return }
            val method = pieces[0].uppercase()
            if (method != "GET" && method != "HEAD") { writeSimpleResponse(client, 405, "Method Not Allowed"); return }

            val incoming = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim()
                if (name.equals("Range", true) || name.equals("If-Range", true)) incoming[name] = line.substring(colon + 1).trim()
            }

            try {
                val local = URI(pieces[1])
                val parts = local.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
                val id = parts.getOrNull(1)
                val target = id?.let(targets::get)
                val remoteUrl = when {
                    parts.size >= 2 && parts[0] == "resource" -> target?.url
                    parts.size >= 2 && parts[0] == "base" && target != null ->
                        resolveBase(target.url, parts.drop(2).joinToString("/"), local.rawQuery)
                    else -> null
                }
                if (remoteUrl == null) { writeSimpleResponse(client, 404, "Not Found"); return }
                fetch(client, method, remoteUrl, incoming, target?.forceManifest == true)
            } catch (_: Exception) {
                // Never echo signed URLs, query values, or cookies to the native process.
                writeSimpleResponse(client, 502, "Upstream Fetch Failed")
            }
        }
    }

    private fun fetch(
        socket: Socket,
        method: String,
        remoteUrl: String,
        incoming: Map<String, String>,
        forceManifest: Boolean,
    ) {
        requireHttpUrl(remoteUrl)
        val connection = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = method
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Encoding", "identity")
            requestHeaders.forEach { (name, value) -> if (forwardHeader(name)) setRequestProperty(name, value) }
            cookie?.takeIf(String::isNotBlank)?.let { setRequestProperty("Cookie", it) }
            incoming.forEach { (name, value) ->
                if (name.equals("Range", true) || name.equals("If-Range", true)) setRequestProperty(name, value)
            }
        }
        try {
            val status = connection.responseCode
            val body = if (method == "HEAD") null else runCatching {
                if (status >= 400) connection.errorStream else connection.inputStream
            }.getOrNull()
            val type = connection.getHeaderField("Content-Type").orEmpty()
            val finalUrl = connection.url.toString()

            if (status in 200..299 && body != null) {
                val input = BufferedInputStream(body)
                input.mark(SNIFF_BYTES)
                val prefix = ByteArray(SNIFF_BYTES)
                val count = runCatching { input.read(prefix) }.getOrDefault(-1)
                input.reset()
                val sample = if (count > 0) String(prefix, 0, count, StandardCharsets.UTF_8)
                    .trimStart('\uFEFF', ' ', '\t', '\r', '\n') else ""
                val kind = manifestKind(finalUrl, type, sample, forceManifest)
                if (kind != null) {
                    val text = String(readBounded(input), StandardCharsets.UTF_8)
                    val rewritten = when (kind) {
                        ManifestKind.HLS -> rewriteHls(text, finalUrl)
                        ManifestKind.DASH -> rewriteDash(text, finalUrl)
                    }.toByteArray(StandardCharsets.UTF_8)
                    writeHeaders(socket, status, if (kind == ManifestKind.HLS) "application/vnd.apple.mpegurl" else "application/dash+xml", rewritten.size.toLong(), emptyMap())
                    socket.getOutputStream().apply { write(rewritten); flush() }
                } else {
                    writeHeaders(socket, status, type, connection.contentLengthLong, responseHeaders(connection))
                    input.copyTo(socket.getOutputStream(), BUFFER_BYTES)
                    socket.getOutputStream().flush()
                }
            } else {
                writeHeaders(socket, status, type, connection.contentLengthLong, responseHeaders(connection))
                if (method != "HEAD") body?.use { it.copyTo(socket.getOutputStream(), BUFFER_BYTES) }
                socket.getOutputStream().flush()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun manifestKind(url: String, contentType: String, sample: String, force: Boolean): ManifestKind? {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") ||
                contentType.contains("mpegurl", true) || sample.startsWith("#EXTM3U") -> ManifestKind.HLS
            path.endsWith(".mpd") || contentType.contains("dash+xml", true) || MPD_TAG.containsMatchIn(sample) -> ManifestKind.DASH
            force && sample.startsWith("#EXTM3U") -> ManifestKind.HLS
            force && MPD_TAG.containsMatchIn(sample) -> ManifestKind.DASH
            else -> null
        }
    }

    /**
     * Resolve a child reference while retaining the signed query on a manifest URL
     * when the child is relative and does not supply its own query.
     */
    private fun resolveManifestReference(base: URI, reference: URI): URI {
        val resolved = base.resolve(reference)
        if (
            reference.isAbsolute ||
            reference.rawAuthority != null ||
            resolved.rawQuery != null ||
            base.rawQuery.isNullOrBlank()
        ) {
            return resolved
        }

        val resolvedText = resolved.toString()
        val fragmentIndex = resolvedText.indexOf('#')
        val location = if (fragmentIndex >= 0) resolvedText.substring(0, fragmentIndex) else resolvedText
        val fragment = if (fragmentIndex >= 0) resolvedText.substring(fragmentIndex) else ""
        return URI("$location?${base.rawQuery}$fragment")
    }

    private fun rewriteHls(text: String, finalUrl: String): String {
        val base = URI(finalUrl)
        return text.lineSequence().joinToString("\n") { line ->
            when {
                line.trim().isEmpty() -> line
                line.trim().startsWith("#") -> HLS_URI.replace(line) { m ->
                    """URI="${addResource(resolveManifestReference(base, URI(m.groupValues[1])).toString(), false)}""""
                }
                else -> addResource(resolveManifestReference(base, URI(line.trim())).toString(), false)
            }
        }
    }

    /**
     * DASH relatives are resolved against a loopback BaseURL. Absolute BaseURL
     * and media references are rewritten to opaque local URLs; templates retain
     * their $Number$ / $RepresentationID$ placeholders for FFmpeg to expand.
     */
    private fun rewriteDash(text: String, finalUrl: String): String {
        val manifestUri = URI(finalUrl)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        }
        val document = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
        } catch (error: Exception) {
            throw IOException("DASH manifest could not be parsed safely", error)
        }
        val root = document.documentElement ?: throw IOException("DASH manifest has no document root")
        if (!localElementName(root).equals("MPD", ignoreCase = true)) {
            throw IOException("DASH manifest root element is not MPD")
        }

        // Add a directory base for manifests whose segments use relative URLs only.
        if (directElementChildren(root).none { localElementName(it).equals("BaseURL", true) }) {
            val defaultBase = resolveManifestReference(manifestUri, URI("."))
            val prefix = root.prefix?.takeIf { it.isNotBlank() }
            val qualifiedName = prefix?.let { "$it:BaseURL" } ?: "BaseURL"
            val baseElement = document.createElementNS(root.namespaceURI, qualifiedName).apply {
                textContent = defaultBase.toString()
            }
            val insertionPoint = directElementChildren(root)
                .firstOrNull { !localElementName(it).equals("ProgramInformation", true) }
            if (insertionPoint == null) root.appendChild(baseElement)
            else root.insertBefore(baseElement, insertionPoint)
        }

        rewriteDashElement(root, manifestUri)

        return try {
            val transformerFactory = TransformerFactory.newInstance()
            runCatching {
                transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            }
            runCatching { transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "") }
            val transformer = transformerFactory.newTransformer().apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                setOutputProperty(OutputKeys.INDENT, "no")
            }
            StringWriter().also { writer ->
                transformer.transform(DOMSource(document), StreamResult(writer))
            }.toString()
        } catch (error: Exception) {
            throw IOException("DASH manifest could not be rewritten safely", error)
        }
    }

    /**
     * Resolve BaseURL hierarchically. A Representation-level "480p/" must be
     * resolved against its AdaptationSet's effective URL, not the MPD URL.
     */
    private fun rewriteDashElement(element: Element, inheritedBase: URI) {
        val baseElements = directElementChildren(element)
            .filter { localElementName(it).equals("BaseURL", ignoreCase = true) }
        var effectiveBase = inheritedBase
        var selectedBase: URI? = null

        // Multiple BaseURL values represent alternatives at the same level. Map
        // each alternative against the parent's base, while using the first as
        // the deterministic base for its descendants.
        for (baseElement in baseElements) {
            val raw = baseElement.textContent.trim()
            val resolved = runCatching {
                if (raw.isEmpty()) inheritedBase
                else resolveManifestReference(inheritedBase, URI(raw))
            }.getOrNull()
            if (resolved != null && isHttpUri(resolved) && !isBridgeUri(resolved)) {
                baseElement.textContent = addBase(resolved.toString())
                if (selectedBase == null) selectedBase = resolved
            } else if (resolved != null && selectedBase == null) {
                selectedBase = resolved
            }
        }
        effectiveBase = selectedBase ?: effectiveBase

        directElementChildren(element)
            .filterNot { localElementName(it).equals("BaseURL", ignoreCase = true) }
            .forEach { child ->
                rewriteAbsoluteDashAttributes(child)
                if (
                    localElementName(child).equals("Location", ignoreCase = true) ||
                    localElementName(child).equals("PatchLocation", ignoreCase = true)
                ) {
                    val raw = child.textContent.trim()
                    val resolved = runCatching {
                        resolveManifestReference(effectiveBase, URI(raw))
                    }.getOrNull()
                    if (resolved != null && isHttpUri(resolved) && !isBridgeUri(resolved)) {
                        child.textContent = addResource(resolved.toString(), false)
                    }
                }
                rewriteDashElement(child, effectiveBase)
            }
    }

    private fun rewriteAbsoluteDashAttributes(element: Element) {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val name = (attribute.localName ?: attribute.nodeName.substringAfter(':')).lowercase()
            if (name !in DASH_REFERENCE_ATTRIBUTES) continue

            val raw = attribute.nodeValue?.trim().orEmpty()
            val uri = runCatching { URI(raw) }.getOrNull() ?: continue
            if (!isHttpUri(uri) || isBridgeUri(uri)) continue

            attribute.nodeValue = if ('$' in raw) templateUrl(uri) else addResource(uri.toString(), false)
        }
    }

    private fun directElementChildren(node: Node): List<Element> {
        val children = mutableListOf<Element>()
        var child = node.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && child is Element) children += child
            child = child.nextSibling
        }
        return children
    }

    private fun localElementName(element: Element): String =
        element.localName ?: element.nodeName.substringAfter(':')

    private fun isHttpUri(uri: URI): Boolean =
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank()

    private fun isBridgeUri(uri: URI): Boolean =
        uri.host.equals("127.0.0.1", ignoreCase = true) && uri.port == server.localPort

    private fun templateUrl(remote: URI): String {
        val path = remote.rawPath.orEmpty()
        val slash = path.lastIndexOf('/')
        val directory = if (slash >= 0) path.substring(0, slash + 1) else "/"
        val name = if (slash >= 0) path.substring(slash + 1) else path
        val local = addBase("${remote.scheme}://${remote.rawAuthority}$directory") + name
        return remote.rawQuery?.let { "$local?$it" } ?: local
    }

    private fun resolveBase(baseUrl: String, suffix: String, query: String?): String {
        val base = URI(baseUrl)
        val relative = suffix + if (query.isNullOrBlank()) "" else "?$query"
        val resolved = base.resolve(URI(relative))
        val pairs = linkedMapOf<String, String>()
        fun add(raw: String?) {
            raw?.split('&')?.filter(String::isNotEmpty)?.forEach { part ->
                val key = part.substringBefore('=')
                pairs.remove(key)
                pairs[key] = part
            }
        }
        add(base.rawQuery)
        add(query)
        return buildString {
            append(resolved.scheme).append("://").append(resolved.rawAuthority).append(resolved.rawPath.orEmpty())
            val merged = pairs.values.joinToString("&")
            if (merged.isNotBlank()) append('?').append(merged)
        }.also(::requireHttpUrl)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_MANIFEST_BYTES) throw IOException("Manifest exceeds safe size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun responseHeaders(connection: HttpURLConnection): Map<String, String> = buildMap {
        listOf("Content-Range", "Accept-Ranges", "Last-Modified", "ETag").forEach { name ->
            connection.getHeaderField(name)?.takeIf(String::isNotBlank)?.let { put(name, it) }
        }
    }

    private fun writeHeaders(socket: Socket, status: Int, type: String, length: Long, extra: Map<String, String>) {
        val reason = when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 301 -> "Moved Permanently"; 302 -> "Found"
            304 -> "Not Modified"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"
            500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
            else -> "Response"
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        if (type.isNotBlank()) output.write("Content-Type: $type\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        if (length >= 0) output.write("Content-Length: $length\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        extra.forEach { (name, value) ->
            if (name.matches(Regex("[A-Za-z0-9-]+")) && !value.contains('\r') && !value.contains('\n')) {
                output.write("$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            }
        }
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun writeSimpleResponse(socket: Socket, status: Int, reason: String) {
        val body = "Manifest proxy request failed".toByteArray(StandardCharsets.UTF_8)
        runCatching {
            writeHeaders(socket, status, "text/plain; charset=utf-8", body.size.toLong(), emptyMap())
            socket.getOutputStream().apply { write(body); flush() }
        }
    }

    private fun forwardHeader(name: String): Boolean =
        name.equals("Referer", true) || name.equals("Origin", true) ||
            name.equals("Accept", true) || name.equals("Accept-Language", true) ||
            name.startsWith("Sec-Fetch-", true) || name.startsWith("Sec-CH-UA", true) ||
            name.equals("X-Goog-Visitor-Id", true) || name.equals("X-YouTube-Client-Name", true) ||
            name.equals("X-YouTube-Client-Version", true)

    private fun requireHttpUrl(url: String) {
        val scheme = runCatching { URI(url).scheme }.getOrNull()
        require(scheme.equals("http", true) || scheme.equals("https", true)) {
            "Only HTTP(S) resources are supported by the manifest bridge"
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        executor.shutdownNow()
        targets.clear()
        @Suppress("UNUSED_VARIABLE") val keepReference = acceptThread
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 60_000
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val SNIFF_BYTES = 1024
        const val BUFFER_BYTES = 32 * 1024
        const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        val HLS_URI = Regex("""(?i)\bURI="([^"]+)"""")
        val DASH_REFERENCE_ATTRIBUTES = setOf("media", "initialization", "sourceurl", "href", "index")
        val MPD_TAG = Regex("<(?:[a-zA-Z0-9_-]+:)?MPD(?:\\s|>)", RegexOption.IGNORE_CASE)
    }
}
