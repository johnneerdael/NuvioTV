package com.nuvio.tv.core.server

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.player.PlaybackBackendKind
import com.nuvio.tv.core.player.PlaybackProxyRegistration
import com.nuvio.tv.core.player.PlaybackProxySource
import com.nuvio.tv.core.player.PlaybackStreamKind
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.screens.player.ParallelRangeDataSource
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Protocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.text.Charsets.UTF_8
import kotlin.math.abs

private const val PROXY_BOOTSTRAP_WINDOW_BYTES = 4L * 1024L * 1024L
private const val PROXY_PIPE_BUFFER_BYTES = 512 * 1024
private const val PROXY_PREFETCH_SEGMENT_COUNT = 2
private const val PROXY_PREFETCH_READ_BYTES = 1024L * 1024L
private const val PROXY_SEEK_REBASE_THRESHOLD_BYTES = 8L * 1024L * 1024L

internal class PlaybackProxyServer private constructor(
    context: Context,
    port: Int
) {
    private val appContext = context.applicationContext
    internal val listeningPort: Int = port
    private val sessions = ConcurrentHashMap<String, SessionRecord>()
    private val requestExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "nuvio-playback-proxy-http").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    private val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }
    private val okHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(16, 10, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
    internal val transferExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "nuvio-playback-proxy-io").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var socketReadTimeoutMillis: Int = SOCKET_READ_TIMEOUT

    private enum class Method {
        GET,
        HEAD,
        OTHER
    }

    private data class IHTTPSession(
        val method: Method,
        val uri: String,
        val headers: Map<String, String>,
        val parameters: Map<String, List<String>>
    )

    internal interface IStatus {
        fun getRequestStatus(): Int
        fun getDescription(): String
    }

    private data class Response(
        val status: IStatus,
        val mimeType: String,
        val bodyStream: InputStream?,
        val bodyBytes: ByteArray? = null,
        val chunked: Boolean = false,
        val headers: MutableMap<String, MutableList<String>> = linkedMapOf(),
        var traceStartedAtMs: Long = 0L,
        var proxyMetricSummary: String? = null
    ) {
        fun addHeader(name: String, value: String) {
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }

        enum class Status(
            private val code: Int,
            private val reason: String
        ) : IStatus {
            NOT_FOUND(404, "Not Found"),
            METHOD_NOT_ALLOWED(405, "Method Not Allowed");

            override fun getRequestStatus(): Int = code

            override fun getDescription(): String = "$code $reason"
        }
    }

    private enum class CacheMode {
        BYPASS,
        PROGRESSIVE,
        HLS_SEGMENT,
        DASH_SEGMENT,
        SUBTITLE,
        KEY,
        ASSET
    }

    private data class CachePolicy(
        val mode: CacheMode,
        val allowCache: Boolean,
        val allowParallel: Boolean,
        val fragmentSizeBytes: Long,
        val probeBytes: Long
    )

    data class SessionRecord(
        val id: String,
        val source: PlaybackProxySource,
        @Volatile var backend: PlaybackBackendKind,
        @Volatile var directPassthroughMode: Boolean = false,
        @Volatile var bootstrapCompleted: Boolean = false,
        @Volatile var lastResolvedUpstreamUrl: String? = null,
        @Volatile var lastRangeStart: Long = -1L,
        val sessionClients: ConcurrentHashMap<String, OkHttpClient> = ConcurrentHashMap(),
        val rewriteCache: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
        val prefetchedAssets: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val metrics: ProxyMetrics = ProxyMetrics(),
        val createdAtMs: Long = System.currentTimeMillis()
    )

    fun registerSession(
        source: PlaybackProxySource,
        backend: PlaybackBackendKind
    ): PlaybackProxyRegistration {
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = SessionRecord(
            id = sessionId,
            source = source,
            backend = backend
        )
        val localPath = when (source.streamKind) {
            PlaybackStreamKind.PROGRESSIVE -> "$PLAY_PATH_PREFIX/$sessionId"
            PlaybackStreamKind.HLS -> "$PLAY_PATH_PREFIX/$sessionId/index.m3u8"
            PlaybackStreamKind.DASH -> "$PLAY_PATH_PREFIX/$sessionId/manifest.mpd"
        }
        val localUrl = "http://$LOOPBACK_HOST:$listeningPort$localPath"
        Log.d(
            TAG,
            "Registered playback proxy session=$sessionId backend=$backend host=${source.upstreamUrl.safeHost()}"
        )
        return PlaybackProxyRegistration(sessionId = sessionId, localUrl = localUrl)
    }

    fun updateBackend(sessionId: String, backend: PlaybackBackendKind) {
        sessions[sessionId]?.backend = backend
    }

    fun setDirectPassthroughMode(sessionId: String, enabled: Boolean) {
        sessions[sessionId]?.directPassthroughMode = enabled
    }

    fun unregisterSession(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        sessions.remove(sessionId)?.let {
            Log.d(TAG, "Unregistered playback proxy session=${it.id}")
        }
    }

    fun start(socketReadTimeoutMillis: Int = SOCKET_READ_TIMEOUT, daemon: Boolean = false) {
        if (serverSocket != null) return
        synchronized(this) {
            if (serverSocket != null) return
            val socket = ServerSocket(listeningPort, 50, InetAddress.getByName(LOOPBACK_HOST)).apply {
                reuseAddress = true
                soTimeout = 1_000
            }
            this.socketReadTimeoutMillis = socketReadTimeoutMillis
            serverSocket = socket
            acceptThread = Thread({
                acceptLoop(socket)
            }, "nuvio-playback-proxy-accept").apply {
                isDaemon = daemon
                start()
            }
        }
    }

    fun stop() {
        acceptThread?.interrupt()
        acceptThread = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                requestExecutor.execute {
                    handleClient(client)
                }
            } catch (_: java.net.SocketTimeoutException) {
                // accept loop heartbeat
            } catch (error: Exception) {
                if (!socket.isClosed) {
                    Log.w(TAG, "Playback proxy accept loop error", error)
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = socketReadTimeoutMillis
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val request = parseRequest(input)
                val response = if (request == null) {
                    newFixedLengthResponse(statusFor(400, "Bad Request"), MIME_PLAINTEXT, "Bad request")
                } else {
                    serve(request)
                }
                writeResponse(output, response, request?.method ?: Method.GET)
                output.flush()
            }
        } catch (error: java.net.SocketException) {
            val message = error.message?.lowercase().orEmpty()
            if (
                message.contains("broken pipe") ||
                message.contains("connection reset") ||
                message.contains("socket closed")
            ) {
                Log.i(TAG, "Playback proxy client disconnected during response write: ${error.message}")
            } else {
                Log.w(TAG, "Playback proxy socket error", error)
            }
        } catch (error: java.io.EOFException) {
            Log.i(TAG, "Playback proxy client disconnected before request/response completed")
        } catch (error: Exception) {
            Log.w(TAG, "Playback proxy client handler error", error)
        }
    }

    private fun parseRequest(input: BufferedInputStream): IHTTPSession? {
        val requestLine = readAsciiLine(input)?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = when (parts[0].uppercase()) {
            "GET" -> Method.GET
            "HEAD" -> Method.HEAD
            else -> Method.OTHER
        }
        val rawTarget = parts[1]
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isBlank()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            headers[name] = value
        }
        val targetUri = Uri.parse("http://$LOOPBACK_HOST$rawTarget")
        val params = linkedMapOf<String, MutableList<String>>()
        targetUri.queryParameterNames.forEach { name ->
            params[name] = targetUri.getQueryParameters(name).toMutableList()
        }
        return IHTTPSession(
            method = method,
            uri = targetUri.encodedPath ?: "/",
            headers = headers,
            parameters = params
        )
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val next = input.read()
            if (next < 0) {
                return if (buffer.isEmpty()) null else buffer.toString()
            }
            if (next == '\n'.code) {
                if (buffer.endsWith("\r")) {
                    buffer.setLength(buffer.length - 1)
                }
                return buffer.toString()
            }
            buffer.append(next.toChar())
        }
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        response: Response,
        method: Method
    ) {
        val bodyBytes = response.bodyBytes
        val hasBody = method != Method.HEAD
        val contentLengthHeader = response.headers.entries.firstOrNull {
            it.key.equals("Content-Length", ignoreCase = true)
        }?.value?.firstOrNull()?.toLongOrNull()
        val useChunked = hasBody && bodyBytes == null && (response.chunked || contentLengthHeader == null)

        writeHeaderLine(output, "HTTP/1.1 ${response.status.getDescription()}")
        writeHeaderLine(output, "Connection: close")
        if (response.mimeType.isNotBlank()) {
            writeHeaderLine(output, "Content-Type: ${response.mimeType}")
        }
        if (bodyBytes != null) {
            writeHeaderLine(output, "Content-Length: ${bodyBytes.size}")
        } else if (!useChunked && contentLengthHeader != null) {
            writeHeaderLine(output, "Content-Length: $contentLengthHeader")
        } else if (useChunked) {
            writeHeaderLine(output, "Transfer-Encoding: chunked")
        }
        response.headers.forEach { (name, values) ->
            if (name.equals("Content-Type", ignoreCase = true)) return@forEach
            if (name.equals("Content-Length", ignoreCase = true) && bodyBytes != null) return@forEach
            if (name.equals("Transfer-Encoding", ignoreCase = true) && useChunked) return@forEach
            values.forEach { value -> writeHeaderLine(output, "$name: $value") }
        }
        writeHeaderLine(output, "")
        output.flush()

        if (!hasBody) {
            response.bodyStream?.close()
            return
        }
        var firstByteLogged = false
        val transferMode = response.headers["X-Nuvio-Transfer"]?.firstOrNull()?.substringAfter("mode=", "unknown")
            ?: "unknown"
        if (bodyBytes != null) {
            if (!firstByteLogged && bodyBytes.isNotEmpty() && response.traceStartedAtMs > 0L) {
                Log.i(
                    "PlaybackProxyMetrics",
                    "PROXY_STREAM: mode=$transferMode loopbackFirstByteMs=" +
                        "${(System.currentTimeMillis() - response.traceStartedAtMs).coerceAtLeast(0L)}"
                )
                response.proxyMetricSummary?.let { summary ->
                    Log.i(
                        "PlaybackProxyMetrics",
                        "$summary loopbackFirstByteMs=" +
                            "${(System.currentTimeMillis() - response.traceStartedAtMs).coerceAtLeast(0L)}"
                    )
                }
                firstByteLogged = true
            }
            output.write(bodyBytes)
            response.bodyStream?.close()
            return
        }
        val stream = response.bodyStream ?: return
        stream.use { body ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = body.read(buffer)
                if (read <= 0) break
                if (!firstByteLogged && response.traceStartedAtMs > 0L) {
                    Log.i(
                        "PlaybackProxyMetrics",
                        "PROXY_STREAM: mode=$transferMode loopbackFirstByteMs=" +
                            "${(System.currentTimeMillis() - response.traceStartedAtMs).coerceAtLeast(0L)}"
                    )
                    response.proxyMetricSummary?.let { summary ->
                        Log.i(
                            "PlaybackProxyMetrics",
                            "$summary loopbackFirstByteMs=" +
                                "${(System.currentTimeMillis() - response.traceStartedAtMs).coerceAtLeast(0L)}"
                        )
                    }
                    firstByteLogged = true
                }
                if (useChunked) {
                    writeHeaderLine(output, read.toString(16))
                    output.write(buffer, 0, read)
                    writeHeaderLine(output, "")
                } else {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
            if (useChunked) {
                writeHeaderLine(output, "0")
                writeHeaderLine(output, "")
            }
        }
    }

    private fun writeHeaderLine(output: BufferedOutputStream, line: String) {
        output.write(line.toByteArray(UTF_8))
        output.write("\r\n".toByteArray(UTF_8))
    }

    private fun serve(session: IHTTPSession): Response {
        val isPlayRequest = session.uri.startsWith(PLAY_PATH_PREFIX)
        val isAssetRequest = session.uri.startsWith(ASSET_PATH_PREFIX)
        if (!isPlayRequest && !isAssetRequest) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Method not allowed"
            )
        }

        val sessionId = when {
            isPlayRequest -> session.uri.removePrefix("$PLAY_PATH_PREFIX/").substringBefore('/')
            else -> session.uri.removePrefix("$ASSET_PATH_PREFIX/").substringBefore('/')
        }
        val record = sessions[sessionId]
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Unknown playback session"
            )

        return runCatching {
            val targetUrl = if (isAssetRequest) {
                session.parameters["u"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: return newFixedLengthResponse(
                        statusFor(400, "Bad Request"),
                        MIME_PLAINTEXT,
                        "Missing upstream url"
                    )
            } else {
                record.source.upstreamUrl
            }

            if (session.method == Method.HEAD) {
                proxyHeadRequest(record, targetUrl)
            } else if (isPlayRequest && record.source.streamKind == PlaybackStreamKind.PROGRESSIVE) {
                proxyMediaRequest(session, record)
            } else {
                proxyAssetOrManifestRequest(session, record, targetUrl)
            }
        }.getOrElse { error ->
            if (
                error.findCause<SocketTimeoutException>() != null &&
                !record.directPassthroughMode &&
                record.backend == PlaybackBackendKind.MEDIA3
            ) {
                record.directPassthroughMode = true
                Log.w(
                    TAG,
                    "Switching playback proxy session=${record.id} to direct passthrough mode " +
                        "after startup timeout host=${record.source.upstreamUrl.safeHost()}"
                )
            }
            Log.e(TAG, "Playback proxy request failed for session=$sessionId", error)
            newFixedLengthResponse(statusFor(502, "Bad Gateway"), MIME_PLAINTEXT, "Upstream request failed")
        }
    }

    private fun proxyHeadRequest(record: SessionRecord, targetUrl: String): Response {
        val request = Request.Builder()
            .url(targetUrl)
            .method("HEAD", null)
            .apply {
                record.source.requestHeaders.forEach { (name, value) -> header(name, value) }
            }
            .build()
        val upstreamResponse = getHttpClientForUrl(record, targetUrl).newCall(request).execute()
        val mimeType = upstreamResponse.header("Content-Type") ?: "application/octet-stream"
        val response = newFixedLengthResponse(
            statusFor(upstreamResponse.code, upstreamResponse.message),
            mimeType,
            ""
        )
        upstreamResponse.headers.forEach { (name, value) ->
            response.addHeader(name, value)
        }
        response.addHeader("X-Nuvio-Playback-Proxy", "1")
        response.addHeader("X-Nuvio-Playback-Backend", record.backend.name.lowercase())
        upstreamResponse.close()
        return response
    }

    private fun proxyMediaRequest(
        incoming: IHTTPSession,
        record: SessionRecord
    ): Response {
        val range = parseRangeHeader(
            incoming.headers.entries.firstOrNull { it.key.equals("range", ignoreCase = true) }?.value
        )
        val requestStartedAtMs = System.currentTimeMillis()
        val streamingResponse = runCatching {
            openProgressiveResponse(record, range)
        }.recoverCatching { error ->
            if (
                error.findCause<SocketTimeoutException>() != null &&
                !record.directPassthroughMode &&
                record.backend == PlaybackBackendKind.MEDIA3
            ) {
                record.directPassthroughMode = true
                Log.w(
                    TAG,
                    "Switching playback proxy session=${record.id} to direct passthrough mode " +
                        "after startup timeout host=${record.source.upstreamUrl.safeHost()}"
                )
                openProgressiveResponse(record, range)
            } else {
                throw error
            }
        }.getOrThrow()
        val response = newStreamingResponse(
            statusFor(
                streamingResponse.statusCode,
                if (streamingResponse.statusCode == 206) "Partial Content" else "OK"
            ),
            streamingResponse.mimeType,
            streamingResponse.inputStream,
            chunked = streamingResponse.contentLength < 0L
        )
        response.addHeader("Accept-Ranges", "bytes")
        if (streamingResponse.contentLength >= 0L) {
            response.addHeader("Content-Length", streamingResponse.contentLength.toString())
        }
        if (range != null && streamingResponse.contentLength >= 0L) {
            val endInclusive = range.start + streamingResponse.contentLength - 1L
            response.addHeader(
                "Content-Range",
                "bytes ${range.start}-$endInclusive/${streamingResponse.totalLength ?: "*"}"
            )
        }
        response.addHeader("X-Nuvio-Playback-Proxy", "1")
        response.addHeader("X-Nuvio-Playback-Backend", record.backend.name.lowercase())
        response.addHeader(
            "X-Nuvio-Transfer",
            "mode=${streamingResponse.transferMode}," +
                "parallel=${record.source.transferOptions.useParallelConnections}," +
                "cache=${record.source.transferOptions.vodCacheModeName}"
        )
        response.addHeader("X-Nuvio-Proxy-Buffer", "${record.metrics.bufferedBytes.get()}")
        streamingResponse.responseHeaders.forEach { (name, values) ->
            if (name.equals("Content-Type", ignoreCase = true)) return@forEach
            if (name.equals("Content-Length", ignoreCase = true)) return@forEach
            if (name.equals("Content-Range", ignoreCase = true)) return@forEach
            if (name.equals("Transfer-Encoding", ignoreCase = true)) return@forEach
            values.forEach { value -> response.addHeader(name, value) }
        }
        logProxyMetrics(
            record = record,
            targetUrl = record.source.upstreamUrl,
            requestStartedAtMs = requestStartedAtMs,
            streamingResponse = streamingResponse,
            range = range
        )
        response.traceStartedAtMs = requestStartedAtMs
        response.proxyMetricSummary = buildProxyMetricSummary(
            record = record,
            targetUrl = record.source.upstreamUrl,
            requestStartedAtMs = requestStartedAtMs,
            streamingResponse = streamingResponse,
            range = range
        )
        return response
    }

    private fun proxyAssetOrManifestRequest(
        incoming: IHTTPSession,
        record: SessionRecord,
        targetUrl: String
    ): Response {
        val requestedRange = parseRangeHeader(
            incoming.headers.entries.firstOrNull { it.key.equals("range", ignoreCase = true) }?.value
        )
        val requestStartedAtMs = System.currentTimeMillis()
        val assetClass = classifyAssetClass(targetUrl)
        val range = if (assetClass == "key" || assetClass == "license") null else requestedRange
        val cachedRewrite = if (range == null && shouldRewriteManifest(targetUrl, null)) {
            record.rewriteCache[targetUrl]
        } else {
            null
        }
        if (cachedRewrite != null) {
            record.metrics.manifestRewriteCount.incrementAndGet()
            maybePrefetchManifestAssets(record, targetUrl, cachedRewrite)
            return newFixedLengthResponse(
                statusFor(200, "OK"),
                if (targetUrl.lowercase().endsWith(".mpd")) "application/dash+xml" else "application/vnd.apple.mpegurl",
                cachedRewrite
            ).apply {
                traceStartedAtMs = requestStartedAtMs
                addHeader("X-Nuvio-Playback-Proxy", "1")
                addHeader("X-Nuvio-Playback-Backend", record.backend.name.lowercase())
                addHeader("X-Nuvio-Transfer", "mode=manifest-cache")
                addHeader("X-Nuvio-Proxy-Asset-Class", "manifest")
                addHeader("Cache-Control", "no-store")
                proxyMetricSummary =
                    "PROXY_METRICS: req=${record.metrics.requestCount.incrementAndGet()} " +
                        "mode=manifest-cache kind=${record.source.streamKind.name.lowercase()} " +
                        "host=${targetUrl.safeHost()} upstreamOpenMs=0 loopbackReadyMs=" +
                        "${(System.currentTimeMillis() - requestStartedAtMs).coerceAtLeast(0L)} " +
                        "bytes=${cachedRewrite.length} range=0 buffered=${record.metrics.bufferedBytes.get()} " +
                        "served=${record.metrics.bytesServed.get()} cacheHit=${record.metrics.cacheHits.get()} " +
                        "cacheMiss=${record.metrics.cacheMisses.get()}"
            }
        }
        val upstreamResponse = if (shouldUseOptimizedAssetTransport(record, targetUrl)) {
            openOptimizedStreamingResponse(
                record = record,
                targetUrl = targetUrl,
                range = range,
                requestClass = assetClass
            )
        } else {
            openHttpStream(record, targetUrl, range, transferMode = "asset-direct")
        }
        val shouldRewriteManifest = shouldRewriteManifest(
            url = targetUrl,
            contentType = upstreamResponse.mimeType
        )
        if (shouldRewriteManifest) {
            val rewritten = upstreamResponse.inputStream.use { stream ->
                val manifest = stream.readBytes().toString(Charsets.UTF_8)
                rewriteManifest(record, targetUrl, manifest).also {
                    record.rewriteCache[targetUrl] = it
                }
            }
            record.metrics.manifestRewriteCount.incrementAndGet()
            maybePrefetchManifestAssets(record, targetUrl, rewritten)
            val response = newFixedLengthResponse(
                statusFor(200, "OK"),
                upstreamResponse.mimeType,
                rewritten
            )
            response.addHeader("X-Nuvio-Playback-Proxy", "1")
            response.addHeader("X-Nuvio-Playback-Backend", record.backend.name.lowercase())
            response.addHeader("X-Nuvio-Transfer", "mode=manifest")
            response.addHeader("X-Nuvio-Proxy-Asset-Class", "manifest")
            response.addHeader("Cache-Control", "no-store")
            response.traceStartedAtMs = requestStartedAtMs
            logProxyMetrics(
                record = record,
                targetUrl = targetUrl,
                requestStartedAtMs = requestStartedAtMs,
                streamingResponse = upstreamResponse,
                range = range
            )
            response.proxyMetricSummary = buildProxyMetricSummary(
                record = record,
                targetUrl = targetUrl,
                requestStartedAtMs = requestStartedAtMs,
                streamingResponse = upstreamResponse,
                range = range
            )
            return response
        }
        val response = newStreamingResponse(
            statusFor(
                upstreamResponse.statusCode,
                if (upstreamResponse.statusCode == 206) "Partial Content" else "OK"
            ),
            upstreamResponse.mimeType,
            upstreamResponse.inputStream,
            chunked = upstreamResponse.contentLength < 0L
        )
            response.addHeader("X-Nuvio-Playback-Proxy", "1")
            response.addHeader("X-Nuvio-Playback-Backend", record.backend.name.lowercase())
            response.addHeader("X-Nuvio-Transfer", "mode=${upstreamResponse.transferMode}")
            response.addHeader("X-Nuvio-Proxy-Asset-Class", assetClass)
            response.addHeader("X-Nuvio-Proxy-Buffer", "${record.metrics.bufferedBytes.get()}")
            response.addHeader("Accept-Ranges", "bytes")
            if (assetClass == "key" || assetClass == "license" || assetClass == "subtitle") {
                response.addHeader("Cache-Control", "no-store")
            }
        if (upstreamResponse.contentLength >= 0L) {
            response.addHeader("Content-Length", upstreamResponse.contentLength.toString())
        }
        if (range != null && upstreamResponse.contentLength >= 0L) {
            val endInclusive = range.start + upstreamResponse.contentLength - 1L
            response.addHeader(
                "Content-Range",
                "bytes ${range.start}-$endInclusive/${upstreamResponse.totalLength ?: "*"}"
            )
        }
        upstreamResponse.responseHeaders.forEach { (name, values) ->
            if (name.equals("Content-Type", ignoreCase = true)) return@forEach
            if (name.equals("Content-Length", ignoreCase = true)) return@forEach
            if (name.equals("Content-Range", ignoreCase = true)) return@forEach
            if (name.equals("Transfer-Encoding", ignoreCase = true)) return@forEach
            values.forEach { value -> response.addHeader(name, value) }
        }
        logProxyMetrics(
            record = record,
            targetUrl = targetUrl,
            requestStartedAtMs = requestStartedAtMs,
            streamingResponse = upstreamResponse,
            range = range
        )
        response.traceStartedAtMs = requestStartedAtMs
        response.proxyMetricSummary = buildProxyMetricSummary(
            record = record,
            targetUrl = targetUrl,
            requestStartedAtMs = requestStartedAtMs,
            streamingResponse = upstreamResponse,
            range = range
        )
        return response
    }

    private fun openProgressiveResponse(
        record: SessionRecord,
        range: RequestedRange?
    ): StreamingResponse {
        if (record.backend == PlaybackBackendKind.LIBVLC) {
            record.bootstrapCompleted = true
            return openHttpStream(
                record = record,
                targetUrl = record.source.upstreamUrl,
                range = range,
                transferMode = "libvlc-direct"
            ).also { response ->
                record.lastResolvedUpstreamUrl = record.source.upstreamUrl
                record.metrics.bufferedBytes.set(0L)
            }
        }
        if (!record.bootstrapCompleted && !record.directPassthroughMode) {
            val response = openHttpStream(
                record = record,
                targetUrl = record.source.upstreamUrl,
                range = range,
                transferMode = "bootstrap"
            )
            return createBufferedResponse(
                record = record,
                targetUrl = record.source.upstreamUrl,
                upstreamResponse = response,
                markBootstrapWindow = true
            )
        }
        return openOptimizedStreamingResponse(
            record = record,
            targetUrl = record.source.upstreamUrl,
            range = range,
            requestClass = "progressive"
        )
    }

    internal fun openOptimizedStreamingResponse(
        record: SessionRecord,
        targetUrl: String,
        range: RequestedRange?,
        requestClass: String
    ): StreamingResponse {
        val upstreamOpenStartedAtMs = System.currentTimeMillis()
        val dataSource = buildDataSource(
            record = record,
            targetUrl = targetUrl,
            range = range,
            requestClass = requestClass
        ).createDataSource()
        val dataSpecBuilder = DataSpec.Builder()
            .setUri(targetUrl)
            .setPosition(range?.start ?: 0L)
            .setKey("${record.source.streamKind.name.lowercase()}:$targetUrl")
        range?.length?.let { dataSpecBuilder.setLength(it) }
        val openedLength = dataSource.open(dataSpecBuilder.build())
        record.bootstrapCompleted = true
        record.lastResolvedUpstreamUrl = runCatching { dataSource.uri?.toString() }.getOrNull()
        val responseHeaders = dataSource.responseHeaders
        val mimeType = responseHeaders["Content-Type"]?.firstOrNull()
            ?: "application/octet-stream"
        val totalLength = responseHeaders["Content-Range"]
            ?.firstOrNull()
            ?.substringAfterLast('/')
            ?.toLongOrNull()
            ?: if (openedLength >= 0L) {
                (range?.start ?: 0L) + openedLength
            } else {
                null
            }

        val inputStream = object : InputStream() {
            private var closed = false

            override fun read(): Int {
                val single = ByteArray(1)
                val read = read(single, 0, 1)
                return if (read <= 0) -1 else single[0].toInt() and 0xFF
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val read = dataSource.read(buffer, offset, length)
                return if (read == C.RESULT_END_OF_INPUT) -1 else read
            }

            override fun close() {
                if (closed) return
                closed = true
                dataSource.close()
            }
        }

        return createBufferedResponse(
            record = record,
            targetUrl = targetUrl,
            upstreamResponse = StreamingResponse(
                statusCode = if (range != null) 206 else 200,
                mimeType = mimeType,
                contentLength = openedLength,
                totalLength = totalLength,
                responseHeaders = responseHeaders,
                transferMode = buildTransferMode(record, requestClass),
                upstreamOpenMs = (System.currentTimeMillis() - upstreamOpenStartedAtMs).coerceAtLeast(0L),
                inputStream = inputStream
            ),
            markBootstrapWindow = false
        )
    }

    private fun openHttpStream(
        record: SessionRecord,
        targetUrl: String,
        range: RequestedRange?,
        transferMode: String
    ): StreamingResponse {
        val upstreamOpenStartedAtMs = System.currentTimeMillis()
        val request = Request.Builder()
            .url(targetUrl)
            .get()
            .apply {
                record.source.requestHeaders.forEach { (name, value) ->
                    if (!name.equals("Range", ignoreCase = true)) {
                        header(name, value)
                    }
                }
                range?.let { requestedRange ->
                    val endInclusive = requestedRange.length?.let { requestedRange.start + it - 1L }
                    header(
                        "Range",
                        if (endInclusive != null) {
                            "bytes=${requestedRange.start}-$endInclusive"
                        } else {
                            "bytes=${requestedRange.start}-"
                        }
                    )
                }
            }
            .build()
        val response = getHttpClientForUrl(record, targetUrl).newCall(request).execute()
        if (!response.isSuccessful && response.code !in setOf(200, 206)) {
            response.close()
            throw IllegalStateException("Upstream returned HTTP ${response.code}")
        }
        val body = response.body ?: run {
            response.close()
            throw IllegalStateException("Upstream body missing")
        }
        val totalLength = response.header("Content-Range")
            ?.substringAfterLast('/')
            ?.toLongOrNull()
            ?: response.header("Content-Length")?.toLongOrNull()
        val stream = object : InputStream() {
            private val upstream = body.byteStream()
            private var closed = false

            override fun read(): Int = upstream.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = upstream.read(b, off, len)

            override fun close() {
                if (closed) return
                closed = true
                upstream.close()
                response.close()
            }
        }
        return StreamingResponse(
            statusCode = response.code,
            mimeType = response.header("Content-Type") ?: "application/octet-stream",
            contentLength = body.contentLength(),
            totalLength = totalLength,
            responseHeaders = response.headers.toMultimap(),
            transferMode = transferMode,
            upstreamOpenMs = (System.currentTimeMillis() - upstreamOpenStartedAtMs).coerceAtLeast(0L),
            inputStream = stream
        )
    }

    private fun buildDataSource(
        record: SessionRecord,
        targetUrl: String,
        range: RequestedRange?,
        requestClass: String
    ): DataSource.Factory {
        val okHttpFactory = buildOkHttpDataSourceFactory(record, targetUrl)
        val seekJump = if (range != null && record.lastRangeStart >= 0L) {
            kotlin.math.abs(range.start - record.lastRangeStart)
        } else {
            0L
        }
        record.lastRangeStart = range?.start ?: -1L
        val seekAwareDirect = seekJump >= PROXY_SEEK_REBASE_THRESHOLD_BYTES
        if (record.directPassthroughMode || seekAwareDirect) {
            return okHttpFactory
        }
        val policy = resolveCachePolicy(record, targetUrl, requestClass)
        if (!policy.allowCache) return okHttpFactory
        val upstreamFactory: DataSource.Factory =
            if (policy.allowParallel) {
                ParallelRangeDataSource.Factory(
                    okHttpFactory,
                    record.source.transferOptions.parallelConnectionCount,
                    record.source.transferOptions.parallelChunkSizeMb.toLong() * 1024L * 1024L
                )
            } else {
                okHttpFactory
            }

        val cache = getOrCreateSimpleCache(
            context = appContext,
            transfer = record.source.transferOptions
        )
        val cacheProbeLength = range?.length ?: policy.probeBytes
        val cacheKey = "${record.source.streamKind.name.lowercase()}:$targetUrl"
        val cacheHasData = runCatching {
            cache.isCached(cacheKey, range?.start ?: 0L, cacheProbeLength)
        }.getOrDefault(false)
        if (cacheHasData) {
            record.metrics.cacheHits.incrementAndGet()
        } else {
            record.metrics.cacheMisses.incrementAndGet()
        }
        val sinkFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(policy.fragmentSizeBytes)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(sinkFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun buildOkHttpDataSourceFactory(
        record: SessionRecord,
        targetUrl: String
    ): OkHttpDataSource.Factory {
        return OkHttpDataSource.Factory(getHttpClientForUrl(record, targetUrl)).apply {
            setDefaultRequestProperties(record.source.requestHeaders)
            setUserAgent(DEFAULT_USER_AGENT)
        }
    }

    private fun shouldUseOptimizedAssetTransport(record: SessionRecord, targetUrl: String): Boolean {
        if (record.directPassthroughMode) return false
        if (shouldRewriteManifest(targetUrl, null)) return false
        return resolveCachePolicy(record, targetUrl, classifyAssetClass(targetUrl)).allowCache
    }

    internal fun classifyAssetClass(targetUrl: String): String {
        val normalized = targetUrl.lowercase()
        val parsed = runCatching { Uri.parse(targetUrl) }.getOrNull()
        val path = parsed?.path?.lowercase() ?: normalized
        val query = parsed?.query?.lowercase().orEmpty()
        return when {
            path.endsWith(".key") || query.contains("key=") -> "key"
            normalized.contains("license") || query.contains("license") || query.contains("drm") -> "license"
            path.endsWith(".vtt") ||
                path.endsWith(".webvtt") ||
                path.endsWith(".ttml") ||
                path.endsWith(".dfxp") ||
                path.endsWith(".srt") ||
                path.endsWith(".sub") ||
                path.endsWith(".smi") ||
                path.endsWith(".sami") ||
                path.endsWith(".ass") ||
                path.endsWith(".ssa") ->
                "subtitle"
            path.endsWith(".m3u8") || path.endsWith(".mpd") -> "manifest"
            isLikelySegmentAsset(targetUrl) -> "segment"
            else -> "asset"
        }
    }

    private fun isLikelySegmentAsset(targetUrl: String): Boolean {
        val normalized = targetUrl.lowercase()
        return normalized.endsWith(".ts") ||
            normalized.endsWith(".m4s") ||
            normalized.endsWith(".m4v") ||
            normalized.endsWith(".m4a") ||
            normalized.endsWith(".cmfv") ||
            normalized.endsWith(".cmfa") ||
            normalized.endsWith(".mp4") ||
            normalized.endsWith(".aac")
    }

    private fun resolveCachePolicy(
        record: SessionRecord,
        targetUrl: String,
        requestClass: String
    ): CachePolicy {
        if (record.directPassthroughMode) {
            return CachePolicy(CacheMode.BYPASS, allowCache = false, allowParallel = false, 0L, 0L)
        }
        val mode = when {
            requestClass == "key" || requestClass == "license" ->
                CacheMode.KEY
            requestClass == "subtitle" ->
                CacheMode.SUBTITLE
            record.source.streamKind == PlaybackStreamKind.PROGRESSIVE ->
                CacheMode.PROGRESSIVE
            record.source.streamKind == PlaybackStreamKind.HLS && isLikelySegmentAsset(targetUrl) ->
                CacheMode.HLS_SEGMENT
            record.source.streamKind == PlaybackStreamKind.DASH && isLikelySegmentAsset(targetUrl) ->
                CacheMode.DASH_SEGMENT
            requestClass == "asset" ->
                CacheMode.ASSET
            else ->
                CacheMode.BYPASS
        }
        return when (mode) {
            CacheMode.PROGRESSIVE -> CachePolicy(
                mode = mode,
                allowCache = true,
                allowParallel = record.source.transferOptions.useParallelConnections && record.bootstrapCompleted,
                fragmentSizeBytes = 2L * 1024L * 1024L,
                probeBytes = PROXY_PREFETCH_READ_BYTES
            )
            CacheMode.HLS_SEGMENT -> CachePolicy(
                mode = mode,
                allowCache = true,
                allowParallel = record.source.transferOptions.useParallelConnections && record.bootstrapCompleted,
                fragmentSizeBytes = 512L * 1024L,
                probeBytes = 512L * 1024L
            )
            CacheMode.DASH_SEGMENT -> CachePolicy(
                mode = mode,
                allowCache = true,
                allowParallel = record.source.transferOptions.useParallelConnections && record.bootstrapCompleted,
                fragmentSizeBytes = 768L * 1024L,
                probeBytes = 768L * 1024L
            )
            CacheMode.SUBTITLE -> CachePolicy(
                mode = mode,
                allowCache = true,
                allowParallel = false,
                fragmentSizeBytes = 128L * 1024L,
                probeBytes = 128L * 1024L
            )
            CacheMode.ASSET -> CachePolicy(
                mode = mode,
                allowCache = false,
                allowParallel = false,
                fragmentSizeBytes = 0L,
                probeBytes = 0L
            )
            CacheMode.KEY,
            CacheMode.BYPASS -> CachePolicy(
                mode = mode,
                allowCache = false,
                allowParallel = false,
                fragmentSizeBytes = 0L,
                probeBytes = 0L
            )
        }
    }

    private fun buildTransferMode(record: SessionRecord, requestClass: String): String {
        val policy = resolveCachePolicy(record, record.lastResolvedUpstreamUrl ?: record.source.upstreamUrl, requestClass)
        return when {
            record.directPassthroughMode -> "direct"
            policy.mode == CacheMode.PROGRESSIVE && policy.allowParallel -> "cache+parallel"
            policy.mode == CacheMode.HLS_SEGMENT && policy.allowParallel -> "hls-segment-cache+parallel"
            policy.mode == CacheMode.DASH_SEGMENT && policy.allowParallel -> "dash-segment-cache+parallel"
            policy.mode == CacheMode.HLS_SEGMENT -> "hls-segment-cache"
            policy.mode == CacheMode.DASH_SEGMENT -> "dash-segment-cache"
            policy.mode == CacheMode.SUBTITLE -> "subtitle-cache"
            requestClass == "manifest" -> "manifest"
            requestClass == "key" || requestClass == "license" -> "key"
            else -> "cache"
        }
    }

    private fun getHttpClientForUrl(record: SessionRecord, targetUrl: String): OkHttpClient {
        val hostKey = targetUrl.safeHost().ifBlank { "default" }
        record.sessionClients[hostKey]?.let { return it }
        synchronized(record.sessionClients) {
            record.sessionClients[hostKey]?.let { return it }
            val builder = okHttpClient.newBuilder()
                .dispatcher(Dispatcher().apply {
                    maxRequests = 32
                    maxRequestsPerHost = 16
                })
                .connectionPool(okHttpClient.connectionPool)
            if (record.backend == PlaybackBackendKind.LIBVLC) {
                builder.protocols(listOf(Protocol.HTTP_1_1))
            }
            return builder.build()
                .also { record.sessionClients[hostKey] = it }
        }
    }

    private fun newFixedLengthResponse(
        status: IStatus,
        mimeType: String,
        text: String
    ): Response {
        val bytes = text.toByteArray(UTF_8)
        return Response(
            status = status,
            mimeType = mimeType,
            bodyStream = null,
            bodyBytes = bytes
        )
    }

    private fun newStreamingResponse(
        status: IStatus,
        mimeType: String,
        inputStream: InputStream,
        chunked: Boolean
    ): Response {
        return Response(
            status = status,
            mimeType = mimeType,
            bodyStream = inputStream,
            chunked = chunked
        )
    }

    companion object {
        private const val TAG = "PlaybackProxy"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val PLAY_PATH_PREFIX = "/play"
        private const val ASSET_PATH_PREFIX = "/asset"
        private const val MIME_PLAINTEXT = "text/plain; charset=utf-8"
        private const val PROXY_CACHE_DIR = "playback_proxy_cache"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MB = 1024L * 1024L
        private const val BOOTSTRAP_WINDOW_BYTES = 4L * 1024L * 1024L
        private const val PIPE_BUFFER_BYTES = 512 * 1024
        private const val PREFETCH_SEGMENT_COUNT = 2
        private const val PREFETCH_READ_BYTES = 1024L * 1024L
        private const val SEEK_REBASE_THRESHOLD_BYTES = 8L * 1024L * 1024L
        private const val SOCKET_READ_TIMEOUT = 15_000
        private const val AUTO_CACHE_STEP_BYTES = 64L * 1024L * 1024L
        private const val AUTO_CACHE_RECONFIGURE_DELTA_BYTES = 256L * 1024L * 1024L
        private const val VOD_CACHE_FREE_SPACE_RESERVE_BYTES = 1024L * 1024L * 1024L
        private const val MIN_RUNTIME_VOD_CACHE_BYTES = 1L * 1024L * 1024L

        @Volatile private var sharedServer: PlaybackProxyServer? = null
        @Volatile private var sharedSimpleCache: SimpleCache? = null
        @Volatile private var configuredCacheMaxBytes: Long = -1L
        @Volatile private var lastResolvedAutoCacheMaxBytes: Long = -1L
        @Volatile private var lastDeferredReconfigureTargetBytes: Long = -1L

        fun getExisting(): PlaybackProxyServer? = sharedServer

        fun getOrCreate(
            context: Context,
            startPort: Int = 18080,
            maxAttempts: Int = 20
        ): PlaybackProxyServer? {
            sharedServer?.let { return it }
            synchronized(this) {
                sharedServer?.let { return it }
                for (port in startPort until startPort + maxAttempts) {
                    try {
                        val server = PlaybackProxyServer(context, port)
                        server.start(SOCKET_READ_TIMEOUT, false)
                        Log.i(TAG, "Playback proxy started on port=$port cacheDir=${context.cacheDir.absolutePath}")
                        sharedServer = server
                        return server
                    } catch (error: Exception) {
                        Log.w(TAG, "Unable to start playback proxy on port=$port", error)
                    }
                }
                Log.e(TAG, "Unable to start playback proxy on any port")
                return null
            }
        }

        fun isPlaybackProxyUrl(url: String): Boolean {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            if (uri.host != LOOPBACK_HOST) return false
            val path = uri.path ?: return false
            return path.startsWith(PLAY_PATH_PREFIX) || path.startsWith(ASSET_PATH_PREFIX)
        }

        fun getProxyCacheLogState(upstreamUrl: String?): String? {
            val server = sharedServer ?: return null
            val cache = sharedSimpleCache ?: return null
            if (upstreamUrl.isNullOrBlank()) return null
            val activeSession = server.sessions.values.firstOrNull { it.source.upstreamUrl == upstreamUrl }
            val streamKeys = buildList {
                add(upstreamUrl)
                activeSession?.lastResolvedUpstreamUrl
                    ?.takeIf { it.isNotBlank() && it != upstreamUrl }
                    ?.let(::add)
            }.distinct()
            val streamBytes = runCatching {
                streamKeys.sumOf { key ->
                    cache.getCachedSpans(key).sumOf { span -> span.length.coerceAtLeast(0L) }
                }
            }.getOrDefault(0L)
            val totalBytes = runCatching { cache.cacheSpace }.getOrDefault(0L)
            val capBytes = configuredCacheMaxBytes.takeIf { it > 0L } ?: return null
            val mode = activeSession?.source?.transferOptions?.vodCacheModeName ?: "proxy"
            val bufferedBytes = activeSession?.metrics?.bufferedBytes?.get() ?: 0L
            return "vod=$mode total=${totalBytes / MB}/${capBytes / MB}MB " +
                "stream=${streamBytes / MB}MB active=${activeSession != null} " +
                "proxyBuffer=${bufferedBytes / 1024L}KB"
        }

        private fun getOrCreateSimpleCache(
            context: Context,
            transfer: com.nuvio.tv.core.player.PlaybackTransferOptions
        ): SimpleCache {
            val maxBytes = resolveCacheMaxBytes(context, transfer)
            sharedSimpleCache?.let {
                if (configuredCacheMaxBytes != maxBytes) {
                    maybeLogDeferredReconfigure(maxBytes)
                }
                return it
            }
            synchronized(this) {
                sharedSimpleCache?.let { existing ->
                    if (configuredCacheMaxBytes != maxBytes) {
                        maybeLogDeferredReconfigure(maxBytes)
                    }
                    return existing
                }
                val cacheDir = File(context.cacheDir, PROXY_CACHE_DIR).apply { mkdirs() }
                return SimpleCache(
                    cacheDir,
                    LeastRecentlyUsedCacheEvictor(maxBytes)
                ).also {
                    sharedSimpleCache = it
                    configuredCacheMaxBytes = maxBytes
                    lastDeferredReconfigureTargetBytes = -1L
                    Log.i(TAG, "Playback proxy cache initialized cap=${maxBytes / 1024L / 1024L}MB")
                }
            }
        }

        private fun resolveCacheMaxBytes(
            context: Context,
            transfer: com.nuvio.tv.core.player.PlaybackTransferOptions
        ): Long {
            val minBytes = PlayerSettings.MIN_VOD_CACHE_SIZE_MB.toLong() * 1024L * 1024L
            val maxBytes = PlayerSettings.MAX_VOD_CACHE_SIZE_MB.toLong() * 1024L * 1024L
            val runtimeMaxBytes = resolveRuntimeUpperBoundBytes(context, maxBytes)
            val manualBytes = transfer.vodCacheSizeMb
                .coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, PlayerSettings.MAX_VOD_CACHE_SIZE_MB)
                .toLong() * 1024L * 1024L
            val resolvedManualBytes = manualBytes.coerceAtMost(runtimeMaxBytes)
            if (transfer.vodCacheModeName == "manual") {
                lastResolvedAutoCacheMaxBytes = -1L
                return resolvedManualBytes
            }

            val freeSpaceBytes = context.cacheDir.usableSpace
            if (freeSpaceBytes <= 0L) return resolvedManualBytes
            if (runtimeMaxBytes < minBytes) {
                lastResolvedAutoCacheMaxBytes = runtimeMaxBytes
                return runtimeMaxBytes
            }
            val autoBytes = freeSpaceBytes / 10L
            val quantizedAutoBytes = ((autoBytes.coerceIn(minBytes, runtimeMaxBytes) / AUTO_CACHE_STEP_BYTES)
                * AUTO_CACHE_STEP_BYTES).coerceIn(minBytes, runtimeMaxBytes)
            val previous = lastResolvedAutoCacheMaxBytes
            val resolved = if (previous > 0L &&
                abs(quantizedAutoBytes - previous) < AUTO_CACHE_RECONFIGURE_DELTA_BYTES
            ) {
                previous
            } else {
                quantizedAutoBytes
            }
            return resolved.coerceAtMost(runtimeMaxBytes).coerceAtLeast(minBytes).also {
                lastResolvedAutoCacheMaxBytes = it
            }
        }

        private fun resolveRuntimeUpperBoundBytes(context: Context, hardMaxBytes: Long): Long {
            val freeSpaceBytes = context.cacheDir.usableSpace
            if (freeSpaceBytes <= 0L) return hardMaxBytes
            val headroomAdjusted = if (freeSpaceBytes > VOD_CACHE_FREE_SPACE_RESERVE_BYTES) {
                freeSpaceBytes - VOD_CACHE_FREE_SPACE_RESERVE_BYTES
            } else {
                (freeSpaceBytes * 8L) / 10L
            }
            return headroomAdjusted
                .coerceAtLeast(MIN_RUNTIME_VOD_CACHE_BYTES)
                .coerceAtMost(hardMaxBytes)
        }

        private fun maybeLogDeferredReconfigure(requestedMaxBytes: Long) {
            if (requestedMaxBytes <= 0L) return
            if (requestedMaxBytes == configuredCacheMaxBytes) return
            if (requestedMaxBytes == lastDeferredReconfigureTargetBytes) return
            lastDeferredReconfigureTargetBytes = requestedMaxBytes
            Log.i(
                TAG,
                "Deferring playback proxy cache cap change from " +
                    "${configuredCacheMaxBytes / 1024L / 1024L}MB to " +
                    "${requestedMaxBytes / 1024L / 1024L}MB until app restart."
            )
        }
    }
}

internal data class RequestedRange(
    val start: Long,
    val length: Long?
)

internal data class StreamingResponse(
    val statusCode: Int,
    val mimeType: String,
    val contentLength: Long,
    val totalLength: Long?,
    val responseHeaders: Map<String, List<String>>,
    val transferMode: String,
    val upstreamOpenMs: Long,
    val inputStream: InputStream
)

internal data class ProxyMetrics(
    val requestCount: AtomicLong = AtomicLong(0L),
    val bytesServed: AtomicLong = AtomicLong(0L),
    val bufferedBytes: AtomicLong = AtomicLong(0L),
    val cacheHits: AtomicLong = AtomicLong(0L),
    val cacheMisses: AtomicLong = AtomicLong(0L),
    val startupBypassCount: AtomicLong = AtomicLong(0L),
    val manifestRewriteCount: AtomicLong = AtomicLong(0L)
)

private fun PlaybackProxyServer.createBufferedResponse(
    record: PlaybackProxyServer.SessionRecord,
    targetUrl: String,
    upstreamResponse: StreamingResponse,
    markBootstrapWindow: Boolean
): StreamingResponse {
    val pipeOut = PipedOutputStream()
    val pipeIn = PipedInputStream(pipeOut, PROXY_PIPE_BUFFER_BYTES)
    transferExecutor.execute {
        var delivered = 0L
        val copyBuffer = ByteArray(64 * 1024)
        try {
            upstreamResponse.inputStream.use { input ->
                pipeOut.use { output ->
                    while (true) {
                        val read = input.read(copyBuffer)
                        if (read <= 0) break
                        output.write(copyBuffer, 0, read)
                        output.flush()
                        delivered += read
                        record.metrics.bytesServed.addAndGet(read.toLong())
                        record.metrics.bufferedBytes.set(pipeIn.available().toLong())
                        if (markBootstrapWindow &&
                            !record.bootstrapCompleted &&
                            delivered >= PROXY_BOOTSTRAP_WINDOW_BYTES
                        ) {
                            record.bootstrapCompleted = true
                        }
                    }
                }
            }
        } catch (error: IOException) {
            val message = error.message?.lowercase().orEmpty()
            if (message.contains("pipe closed") || message.contains("broken pipe")) {
                Log.i("PlaybackProxy", "Playback proxy buffered stream cancelled by client: ${error.message}")
            } else {
                Log.w("PlaybackProxy", "Playback proxy buffered stream error", error)
            }
        } catch (error: Exception) {
            Log.w("PlaybackProxy", "Playback proxy buffered transfer worker failed", error)
        } finally {
            runCatching { upstreamResponse.inputStream.close() }
            runCatching { pipeOut.close() }
            if (markBootstrapWindow) {
                record.bootstrapCompleted = true
            }
            record.lastResolvedUpstreamUrl = targetUrl
            record.metrics.bufferedBytes.set(0L)
        }
    }
    return upstreamResponse.copy(inputStream = pipeIn)
}

private fun PlaybackProxyServer.logProxyMetrics(
    record: PlaybackProxyServer.SessionRecord,
    targetUrl: String,
    requestStartedAtMs: Long,
    streamingResponse: StreamingResponse,
    range: RequestedRange?
) {
    val durationMs = (System.currentTimeMillis() - requestStartedAtMs).coerceAtLeast(0L)
    val requestIndex = record.metrics.requestCount.incrementAndGet()
    if (streamingResponse.transferMode == "bootstrap") {
        record.metrics.startupBypassCount.incrementAndGet()
    }
    Log.i(
        "PlaybackProxyMetrics",
        buildProxyMetricSummary(
            record = record,
            targetUrl = targetUrl,
            requestStartedAtMs = requestStartedAtMs,
            streamingResponse = streamingResponse,
            range = range,
            requestIndex = requestIndex
        )
    )
}

private fun PlaybackProxyServer.buildProxyMetricSummary(
    record: PlaybackProxyServer.SessionRecord,
    targetUrl: String,
    requestStartedAtMs: Long,
    streamingResponse: StreamingResponse,
    range: RequestedRange?,
    requestIndex: Long = record.metrics.requestCount.get()
): String {
    val durationMs = (System.currentTimeMillis() - requestStartedAtMs).coerceAtLeast(0L)
    return "PROXY_METRICS: req=$requestIndex " +
        "mode=${streamingResponse.transferMode} " +
        "kind=${record.source.streamKind.name.lowercase()} " +
        "host=${targetUrl.safeHost()} " +
        "upstreamOpenMs=${streamingResponse.upstreamOpenMs} " +
        "loopbackReadyMs=$durationMs " +
        "bytes=${streamingResponse.contentLength.coerceAtLeast(0L)} " +
        "range=${range?.start ?: 0L} " +
        "buffered=${record.metrics.bufferedBytes.get()} " +
        "served=${record.metrics.bytesServed.get()} " +
        "cacheHit=${record.metrics.cacheHits.get()} " +
        "cacheMiss=${record.metrics.cacheMisses.get()}"
}

private fun PlaybackProxyServer.maybePrefetchManifestAssets(
    record: PlaybackProxyServer.SessionRecord,
    manifestUrl: String,
    rewrittenManifest: String
) {
    val assetUrls = extractPrefetchTargetsFromManifest(
        manifestUrl = manifestUrl,
        rewrittenManifest = rewrittenManifest
    )
    assetUrls.take(PROXY_PREFETCH_SEGMENT_COUNT).forEach { assetUrl ->
        if (!record.prefetchedAssets.add(assetUrl)) return@forEach
        transferExecutor.execute {
            runCatching {
                val response = openOptimizedStreamingResponse(
                    record = record,
                    targetUrl = assetUrl,
                    range = RequestedRange(start = 0L, length = PROXY_PREFETCH_READ_BYTES),
                    requestClass = classifyAssetClass(assetUrl)
                )
                response.inputStream.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (stream.read(buffer) >= 0) {
                        // warm cache / connection path only
                    }
                }
            }.onFailure { error ->
                Log.d("PlaybackProxy", "Segment prefetch skipped for host=${assetUrl.safeHost()}", error)
            }
        }
    }
}

private fun PlaybackProxyServer.extractPrefetchTargetsFromManifest(
    manifestUrl: String,
    rewrittenManifest: String
): List<String> {
    val hlsLines = rewrittenManifest.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            runCatching { Uri.decode(line.substringAfter("u=", line)) }.getOrNull()
        }
        .filter { !shouldRewriteManifest(it, null) }
        .toList()
    if (hlsLines.isNotEmpty()) return hlsLines

    val dashMatches = Regex("/asset/[^?]+\\?u=([^\"'\\s<>]+)")
        .findAll(rewrittenManifest)
        .mapNotNull { match -> Uri.decode(match.groupValues[1]) }
        .filter { !shouldRewriteManifest(it, null) }
        .toList()
    if (dashMatches.isNotEmpty()) return dashMatches

    return emptyList()
}

private fun PlaybackProxyServer.shouldRewriteManifest(url: String, contentType: String?): Boolean {
    val normalizedUrl = url.lowercase()
    val normalizedContentType = contentType?.lowercase().orEmpty()
    return normalizedUrl.endsWith(".m3u8") ||
        normalizedUrl.endsWith(".mpd") ||
        normalizedContentType.contains("mpegurl") ||
        normalizedContentType.contains("vnd.apple.mpegurl") ||
        normalizedContentType.contains("dash+xml")
}

private fun PlaybackProxyServer.rewriteManifest(
    record: PlaybackProxyServer.SessionRecord,
    manifestUrl: String,
    manifest: String
): String {
    val normalizedUrl = manifestUrl.lowercase()
    return if (normalizedUrl.endsWith(".mpd") || manifest.contains("<MPD")) {
        rewriteDashManifest(record, manifestUrl, manifest)
    } else {
        rewriteHlsManifest(record, manifestUrl, manifest)
    }
}

private fun PlaybackProxyServer.rewriteHlsManifest(
    record: PlaybackProxyServer.SessionRecord,
    manifestUrl: String,
    manifest: String
): String {
    val uriAttributeRegex = Regex("URI=\"([^\"]+)\"")
    return manifest.lineSequence().joinToString("\n") { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) {
            rawLine
        } else if (line.startsWith("#")) {
            uriAttributeRegex.replace(rawLine) { match ->
                val rawTarget = match.groupValues[1]
                val rewritten = buildAssetReference(record.id, resolveUrl(manifestUrl, rawTarget))
                "URI=\"$rewritten\""
            }
        } else {
            buildAssetReference(record.id, resolveUrl(manifestUrl, line))
        }
    }
}

private fun PlaybackProxyServer.rewriteDashManifest(
    record: PlaybackProxyServer.SessionRecord,
    manifestUrl: String,
    manifest: String
): String {
    val attrRegex = Regex("(media|initialization|sourceURL|url|xlink:href)=\"([^\"]+)\"")
    val baseUrlRegex = Regex("<BaseURL>(.*?)</BaseURL>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val withAttributes = attrRegex.replace(manifest) { match ->
        val attrName = match.groupValues[1]
        val rawTarget = match.groupValues[2]
        val rewritten = buildAssetReference(record.id, resolveUrl(manifestUrl, rawTarget))
        "$attrName=\"$rewritten\""
    }
    return baseUrlRegex.replace(withAttributes) { match ->
        val rawTarget = match.groupValues[1].trim()
        val rewritten = buildAssetReference(record.id, resolveUrl(manifestUrl, rawTarget))
        "<BaseURL>$rewritten</BaseURL>"
    }
}

private fun PlaybackProxyServer.buildAssetReference(sessionId: String, resolvedUrl: String): String {
    return if (resolvedUrl.isProxyableNetworkUrl()) {
        buildLocalAssetUrl(sessionId, resolvedUrl)
    } else {
        resolvedUrl
    }
}

private fun PlaybackProxyServer.buildLocalAssetUrl(sessionId: String, upstreamUrl: String): String {
    return "http://127.0.0.1:$listeningPort/asset/$sessionId?u=${Uri.encode(upstreamUrl)}"
}

private fun resolveUrl(baseUrl: String, candidate: String): String {
    if (candidate.isBlank()) return candidate
    if (candidate.startsWith("data:", ignoreCase = true)) return candidate
    val parsed = runCatching { Uri.parse(candidate) }.getOrNull()
    if (parsed?.scheme?.isNotBlank() == true && !parsed.scheme.equals("http", true) && !parsed.scheme.equals("https", true)) {
        return candidate
    }
    if (candidate.startsWith("http://", ignoreCase = true) ||
        candidate.startsWith("https://", ignoreCase = true)
    ) {
        return candidate
    }
    return runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrDefault(candidate)
}

private fun parseRangeHeader(header: String?): RequestedRange? {
    if (header.isNullOrBlank()) return null
    if (!header.startsWith("bytes=", ignoreCase = true)) return null
    val spec = header.removePrefix("bytes=").substringBefore(',').trim()
    val startText = spec.substringBefore('-').trim()
    val endText = spec.substringAfter('-', "").trim()
    val start = startText.toLongOrNull() ?: return null
    val end = endText.toLongOrNull()
    val length = if (end != null && end >= start) {
        (end - start) + 1L
    } else {
        null
    }
    return RequestedRange(start = start, length = length)
}

private fun String.safeHost(): String = runCatching {
    java.net.URI(this).host ?: "unknown"
}.getOrDefault("unknown")

private fun String.isProxyableNetworkUrl(): Boolean {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return false
    val scheme = uri.scheme ?: return false
    return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private fun statusFor(code: Int, message: String): PlaybackProxyServer.IStatus =
    object : PlaybackProxyServer.IStatus {
        override fun getRequestStatus(): Int = code
        override fun getDescription(): String = "$code ${message.ifBlank { "Upstream" }}"
    }
