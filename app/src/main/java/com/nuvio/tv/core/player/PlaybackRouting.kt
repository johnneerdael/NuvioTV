package com.nuvio.tv.core.player

enum class PlaybackBackendKind {
    MEDIA3,
    LIBVLC
}

internal enum class PlaybackStreamKind {
    PROGRESSIVE,
    HLS,
    DASH
}

internal data class PlaybackTransferOptions(
    val useParallelConnections: Boolean,
    val parallelConnectionCount: Int,
    val parallelChunkSizeMb: Int,
    val vodCacheModeName: String,
    val vodCacheSizeMb: Int
)

internal data class PlaybackProxySource(
    val upstreamUrl: String,
    val requestHeaders: Map<String, String>,
    val streamKind: PlaybackStreamKind,
    val transferOptions: PlaybackTransferOptions
)

internal data class PlaybackProxyRegistration(
    val sessionId: String,
    val localUrl: String
)

internal data class PlaybackRoute(
    val upstreamUrl: String,
    val upstreamHeaders: Map<String, String>,
    val media3Url: String,
    val media3Headers: Map<String, String>,
    val proxySessionId: String?,
    val proxyUrl: String?,
    val media3UsesProxy: Boolean
)
