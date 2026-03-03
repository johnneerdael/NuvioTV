package com.nuvio.tv.ui.screens.player

internal fun PlayerRuntimeController.activeBackendController(): PlaybackBackendController? {
    return when (activePlaybackBackend) {
        com.nuvio.tv.core.player.PlaybackBackendKind.MEDIA3 -> activePlaybackController ?: _exoPlayer?.let(::Media3PlaybackBackend)
        com.nuvio.tv.core.player.PlaybackBackendKind.LIBVLC -> activePlaybackController
    }
}

internal fun PlayerRuntimeController.activeCurrentPositionMs(): Long =
    activeBackendController()?.currentPositionMs() ?: 0L

internal fun PlayerRuntimeController.activeDurationMs(): Long =
    activeBackendController()?.durationMs() ?: 0L

internal fun PlayerRuntimeController.activeBufferedPositionMs(): Long =
    activeBackendController()?.bufferedPositionMs() ?: activeCurrentPositionMs()

internal fun PlayerRuntimeController.activeIsPlaying(): Boolean =
    activeBackendController()?.isPlaying() == true

internal fun PlayerRuntimeController.activeIsLoading(): Boolean =
    activeBackendController()?.isLoading() == true

internal fun PlayerRuntimeController.activePlay() {
    activeBackendController()?.play()
}

internal fun PlayerRuntimeController.activePause() {
    activeBackendController()?.pause()
}

internal fun PlayerRuntimeController.activeSeekTo(positionMs: Long) {
    activeBackendController()?.seekTo(positionMs)
}

internal fun PlayerRuntimeController.activeSetPlaybackSpeed(speed: Float) {
    activeBackendController()?.setPlaybackSpeed(speed)
}
