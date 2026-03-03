package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

internal suspend fun PlayerRuntimeController.startPlaybackRouteOnActivePlayer(
    player: ExoPlayer,
    playbackRoute: com.nuvio.tv.core.player.PlaybackRoute,
    startPositionMs: Long? = null,
    subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList()
): Boolean {
    clearUnsupportedPlaybackOptions()
    if (shouldStartWithLibVlcPreflight(
            url = playbackRoute.upstreamUrl,
            headers = playbackRoute.upstreamHeaders,
            media3UsesProxy = playbackRoute.media3UsesProxy
        )
    ) {
        showUnsupportedPlaybackOptions(
            reason = "preflight-unsupported-video",
            detail = "preflight"
        )
        return true
    }

    val mediaSource = mediaSourceFactory.createMediaSource(
        url = playbackRoute.media3Url,
        headers = playbackRoute.media3Headers,
        subtitleConfigurations = subtitleConfigurations
    )

    if (startPositionMs != null && startPositionMs > 0L) {
        player.setMediaSource(mediaSource, startPositionMs)
    } else {
        player.setMediaSource(mediaSource)
    }
    media3PlaybackAwaitingValidation = true
    player.playWhenReady = false
    player.prepare()
    return false
}
