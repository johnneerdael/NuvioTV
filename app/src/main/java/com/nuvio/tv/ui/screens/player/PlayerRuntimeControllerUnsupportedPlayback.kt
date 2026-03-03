package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.clearUnsupportedPlaybackOptions() {
    if (_uiState.value.unsupportedPlayback == null) return
    _uiState.update { it.copy(unsupportedPlayback = null) }
}

internal fun PlayerRuntimeController.showUnsupportedPlaybackOptions(
    reason: String,
    detail: String? = null,
    title: String = "Stream not supported in built-in player",
    message: String = "This stream is not supported by the built-in player. Open it in LibVLC or an external player instead."
) {
    cancelFirstFrameWatchdog()
    media3PlaybackAwaitingValidation = false
    hasRenderedFirstFrame = false
    Log.w(
        PlayerRuntimeController.TAG,
        "UNSUPPORTED_PLAYBACK: reason=$reason detail=${detail ?: "n/a"} " +
            "host=${runCatching { Uri.parse(currentStreamUrl).host ?: "unknown" }.getOrDefault("unknown")}"
    )
    releasePlayer()
    _uiState.update {
        it.copy(
            isPlaying = false,
            isBuffering = false,
            showLoadingOverlay = false,
            showPauseOverlay = false,
            showControls = false,
            showSeekOverlay = false,
            error = null,
            unsupportedPlayback = UnsupportedPlaybackInfo(
                title = title,
                message = message,
                canOpenInLibVlc = currentStreamUrl.isNotBlank(),
                canOpenInExternalPlayer = currentStreamUrl.isNotBlank()
            )
        )
    }
}

internal fun PlayerRuntimeController.maybeAllowValidatedMedia3Playback() {
    if (!media3PlaybackAwaitingValidation) return
    if (_uiState.value.unsupportedPlayback != null) return
    val player = _exoPlayer ?: return
    media3PlaybackAwaitingValidation = false
    if (_uiState.value.showLoadingOverlay) {
        _uiState.update { it.copy(showLoadingOverlay = false, showControls = true) }
    }
    if (!userPausedManually) {
        if (!player.playWhenReady) {
            player.playWhenReady = true
        }
        player.play()
    }
    if (shouldEnforceAutoplayOnFirstReady) {
        shouldEnforceAutoplayOnFirstReady = false
    }
    if (player.playbackState == Player.STATE_READY) {
        _uiState.update { it.copy(showLoadingOverlay = false, showControls = true) }
        tryApplyPendingResumeProgress(player)
        _uiState.value.pendingSeekPosition?.let { position ->
            player.seekTo(position)
            _uiState.update { it.copy(pendingSeekPosition = null) }
        }
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
        maybeScheduleFirstFrameWatchdog()
    }
}
