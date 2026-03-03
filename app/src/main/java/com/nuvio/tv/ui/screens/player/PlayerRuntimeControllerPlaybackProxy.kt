package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.PlaybackBackendKind
import com.nuvio.tv.core.player.PlaybackProxySource
import com.nuvio.tv.core.player.PlaybackRoute
import com.nuvio.tv.core.player.PlaybackStreamKind
import com.nuvio.tv.core.player.PlaybackTransferOptions
import com.nuvio.tv.core.server.PlaybackProxyServer
import com.nuvio.tv.data.local.VodCacheSizeMode

internal fun PlayerRuntimeController.preparePlaybackRoute(
    url: String,
    headers: Map<String, String>
): PlaybackRoute {
    releasePlaybackProxySession()

    val streamKind = classifyPlaybackStreamKind(url)
    if (streamKind == null) {
        Log.d(
            PlayerRuntimeController.TAG,
            "PLAYBACK_PROXY: bypassed for non-http stream host=${url.safeHost()}"
        )
        return PlaybackRoute(
            upstreamUrl = url,
            upstreamHeaders = headers,
            media3Url = url,
            media3Headers = headers,
            proxySessionId = null,
            proxyUrl = null,
            media3UsesProxy = false
        )
    }

    val proxyServer = PlaybackProxyServer.getOrCreate(context.applicationContext)
    if (proxyServer == null) {
        Log.w(PlayerRuntimeController.TAG, "PLAYBACK_PROXY: unavailable, using direct playback host=${url.safeHost()}")
        return PlaybackRoute(
            upstreamUrl = url,
            upstreamHeaders = headers,
            media3Url = url,
            media3Headers = headers,
            proxySessionId = null,
            proxyUrl = null,
            media3UsesProxy = false
        )
    }

    val registration = proxyServer.registerSession(
        source = PlaybackProxySource(
            upstreamUrl = url,
            requestHeaders = headers,
            streamKind = streamKind,
            transferOptions = PlaybackTransferOptions(
                useParallelConnections = mediaSourceFactory.useParallelConnections,
                parallelConnectionCount = mediaSourceFactory.parallelConnectionCount,
                parallelChunkSizeMb = mediaSourceFactory.parallelChunkSizeMb,
                vodCacheModeName = when (mediaSourceFactory.vodCacheSizeMode) {
                    VodCacheSizeMode.AUTO -> "auto"
                    VodCacheSizeMode.MANUAL -> "manual"
                },
                vodCacheSizeMb = mediaSourceFactory.vodCacheSizeMb
            )
        ),
        backend = PlaybackBackendKind.MEDIA3
    )

    activePlaybackProxySessionId = registration.sessionId
    activePlaybackProxyUrl = registration.localUrl
    activePlaybackBackend = PlaybackBackendKind.MEDIA3

    Log.i(
        PlayerRuntimeController.TAG,
        "PLAYBACK_PROXY: session=${registration.sessionId} " +
            "media3UsesProxy=true local=${registration.localUrl} host=${url.safeHost()}"
    )

    return PlaybackRoute(
        upstreamUrl = url,
        upstreamHeaders = headers,
        media3Url = registration.localUrl,
        media3Headers = emptyMap(),
        proxySessionId = registration.sessionId,
        proxyUrl = registration.localUrl,
        media3UsesProxy = true
    )
}

internal fun PlayerRuntimeController.updatePlaybackBackend(backend: PlaybackBackendKind) {
    activePlaybackBackend = backend
    val sessionId = activePlaybackProxySessionId ?: return
    PlaybackProxyServer.getExisting()?.updateBackend(sessionId, backend)
}

internal fun PlayerRuntimeController.rebindPlaybackProxySessionForBackend(
    backend: PlaybackBackendKind,
    directPassthrough: Boolean
): String? {
    val streamKind = classifyPlaybackStreamKind(currentStreamUrl) ?: return activePlaybackProxyUrl
    val proxyServer = PlaybackProxyServer.getOrCreate(context.applicationContext) ?: return activePlaybackProxyUrl
    val previousSessionId = activePlaybackProxySessionId
    val registration = proxyServer.registerSession(
        source = PlaybackProxySource(
            upstreamUrl = currentStreamUrl,
            requestHeaders = currentHeaders,
            streamKind = streamKind,
            transferOptions = PlaybackTransferOptions(
                useParallelConnections = mediaSourceFactory.useParallelConnections,
                parallelConnectionCount = mediaSourceFactory.parallelConnectionCount,
                parallelChunkSizeMb = mediaSourceFactory.parallelChunkSizeMb,
                vodCacheModeName = when (mediaSourceFactory.vodCacheSizeMode) {
                    VodCacheSizeMode.AUTO -> "auto"
                    VodCacheSizeMode.MANUAL -> "manual"
                },
                vodCacheSizeMb = mediaSourceFactory.vodCacheSizeMb
            )
        ),
        backend = backend
    )
    if (directPassthrough) {
        proxyServer.setDirectPassthroughMode(registration.sessionId, true)
    }
    previousSessionId?.takeIf { it != registration.sessionId }?.let { proxyServer.unregisterSession(it) }
    activePlaybackProxySessionId = registration.sessionId
    activePlaybackProxyUrl = registration.localUrl
    activePlaybackBackend = backend
    Log.i(
        PlayerRuntimeController.TAG,
        "PLAYBACK_PROXY: rebound session=${registration.sessionId} backend=$backend " +
            "directPassthrough=$directPassthrough local=${registration.localUrl} host=${currentStreamUrl.safeHost()}"
    )
    return registration.localUrl
}

internal fun PlayerRuntimeController.releasePlaybackProxySession() {
    PlaybackProxyServer.getExisting()?.unregisterSession(activePlaybackProxySessionId)
    activePlaybackProxySessionId = null
    activePlaybackProxyUrl = null
    activePlaybackBackend = PlaybackBackendKind.MEDIA3
}

private fun String.safeHost(): String = runCatching {
    java.net.URI(this).host ?: "unknown"
}.getOrDefault("unknown")

private fun classifyPlaybackStreamKind(url: String): PlaybackStreamKind? {
    if (PlaybackProxyServer.isPlaybackProxyUrl(url)) return null
    val normalized = url.lowercase()
    val isHttp = normalized.startsWith("http://") || normalized.startsWith("https://")
    if (!isHttp) return null
    val isHls = normalized.contains(".m3u8") ||
        normalized.contains("/playlist") ||
        normalized.contains("/hls") ||
        normalized.contains("m3u8")
    val isDash = normalized.contains(".mpd") || normalized.contains("/dash")
    return when {
        isHls -> PlaybackStreamKind.HLS
        isDash -> PlaybackStreamKind.DASH
        else -> PlaybackStreamKind.PROGRESSIVE
    }
}
