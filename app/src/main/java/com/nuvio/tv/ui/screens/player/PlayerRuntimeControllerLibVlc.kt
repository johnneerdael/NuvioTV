package com.nuvio.tv.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import com.nuvio.tv.core.player.PlaybackBackendKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.DisplayManager
import org.videolan.libvlc.util.VLCVideoLayout

private const val LIBVLC_SEEK_APPLY_DELAY_MS = 350L

internal fun PlayerRuntimeController.attachLibVlcVideoLayout(videoLayout: VLCVideoLayout) {
    libVlcVideoLayout = videoLayout
    maybeStartAttachedLibVlcPlayback()
}

internal fun PlayerRuntimeController.detachLibVlcVideoLayout(videoLayout: VLCVideoLayout?) {
    if (videoLayout == null) return
    if (libVlcVideoLayout !== videoLayout) return
    libVlcPlayer?.let { player ->
        runCatching { player.detachViews() }
    }
    libVlcViewsAttached = false
    libVlcVideoLayout = null
}

internal fun PlayerRuntimeController.handoffCurrentStreamToLibVlc(
    fromPositionMs: Long,
    reason: String
) {
    if (activePlaybackBackend == PlaybackBackendKind.LIBVLC) return
    val proxyUrl = rebindPlaybackProxySessionForBackend(
        backend = PlaybackBackendKind.LIBVLC,
        directPassthrough = true
    ) ?: activePlaybackProxyUrl
    if (proxyUrl.isNullOrBlank()) {
        Log.w(PlayerRuntimeController.TAG, "LIBVLC: handoff skipped because proxy URL is unavailable")
        return
    }

    Log.w(
        PlayerRuntimeController.TAG,
        "LIBVLC: handing off playback reason=$reason host=${Uri.parse(currentStreamUrl).host ?: "unknown"} " +
            "fromPositionMs=$fromPositionMs"
    )

    cancelFirstFrameWatchdog()
    releaseCurrentMedia3BackendOnly()
    hasRenderedFirstFrame = false
    libVlcViewsAttached = false

    val options = arrayListOf(
        "--network-caching=1000",
        "--file-caching=1000",
        "--avcodec-hw=none",
        "--drop-late-frames",
        "--skip-frames"
    )
    val libVlcInstance = LibVLC(context, options)
    val player = MediaPlayer(libVlcInstance)
    val host = Uri.parse(currentStreamUrl).host ?: "unknown"

    runCatching { player.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0) }
    runCatching { player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT) }
    Log.i(PlayerRuntimeController.TAG, "LIBVLC: audio output=default")

    val voutCallback = object : IVLCVout.Callback {
        override fun onSurfacesCreated(vlcVout: IVLCVout?) {
            Log.i(PlayerRuntimeController.TAG, "LIBVLC: vout surfaces created host=$host")
        }

        override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {
            Log.i(PlayerRuntimeController.TAG, "LIBVLC: vout surfaces destroyed host=$host")
        }
    }
    runCatching {
        player.vlcVout.addCallback(voutCallback)
    }.onFailure { error ->
        Log.w(PlayerRuntimeController.TAG, "LIBVLC: failed to register vout callback", error)
    }

    player.setEventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                Log.i(PlayerRuntimeController.TAG, "LIBVLC: event=opening host=$host")
                _uiState.update { it.copy(isBuffering = true, showLoadingOverlay = it.loadingOverlayEnabled) }
            }
            MediaPlayer.Event.Buffering -> {
                Log.i(PlayerRuntimeController.TAG, "LIBVLC: event=buffering host=$host value=${event.buffering}")
                _uiState.update { it.copy(isBuffering = true, showLoadingOverlay = it.loadingOverlayEnabled) }
            }
            MediaPlayer.Event.Playing -> {
                Log.i(PlayerRuntimeController.TAG, "LIBVLC: event=playing host=$host")
                hasRenderedFirstFrame = true
                _uiState.update {
                    it.copy(
                        isPlaying = true,
                        isBuffering = false,
                        showLoadingOverlay = false,
                        showControls = true,
                        error = null
                    )
                }
            }
            MediaPlayer.Event.Vout -> {
                Log.i(PlayerRuntimeController.TAG, "LIBVLC: event=vout host=$host count=${event.voutCount}")
            }
            MediaPlayer.Event.Paused -> {
                Log.i(PlayerRuntimeController.TAG, "LIBVLC: event=paused")
                _uiState.update { it.copy(isPlaying = false, isBuffering = false) }
            }
            MediaPlayer.Event.Stopped -> {
                Log.i(PlayerRuntimeController.TAG, "LIBVLC: event=stopped")
                _uiState.update { it.copy(isPlaying = false, isBuffering = false) }
            }
            MediaPlayer.Event.EndReached -> {
                Log.i(PlayerRuntimeController.TAG, "LIBVLC: event=end")
                _uiState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        playbackEnded = true,
                        showLoadingOverlay = false
                    )
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                Log.e(PlayerRuntimeController.TAG, "LIBVLC: event=error host=$host")
                showUnsupportedPlaybackOptions(
                    reason = "libvlc-error",
                    detail = "event-error",
                    title = "LibVLC playback failed",
                    message = "LibVLC could not start this stream. Try an external player instead."
                )
            }
            else -> {
                Log.i(PlayerRuntimeController.TAG, "LIBVLC: event=${describeLibVlcEvent(event)} host=$host")
            }
        }
    }

    libVlc = libVlcInstance
    libVlcPlayer = player
    libVlcVoutCallback = voutCallback
    libVlcDisplayManager?.release()
    libVlcDisplayManager = null
    activePlaybackController = LibVlcPlaybackBackend(player)
    updatePlaybackBackend(PlaybackBackendKind.LIBVLC)
    immediateLibVlcHandoffRequestedForCurrentPlayback = true
    _uiState.update {
        it.copy(
            playbackBackend = PlaybackBackendKind.LIBVLC,
            isBuffering = true,
            showLoadingOverlay = it.loadingOverlayEnabled,
            error = null
        )
    }

    val media = Media(libVlcInstance, Uri.parse(proxyUrl)).apply {
        setHWDecoderEnabled(false, false)
    }
    player.media = media
    media.release()
    pendingLibVlcAutoStart = true
    pendingLibVlcSeekPositionMs = fromPositionMs.takeIf { it > 0L }
    maybeStartAttachedLibVlcPlayback()
}

internal fun PlayerRuntimeController.releaseLibVlcBackendOnly() {
    libVlcPlayer?.let { player ->
        runCatching {
            player.setEventListener(null)
        }
        libVlcVoutCallback?.let { callback ->
            runCatching { player.vlcVout.removeCallback(callback) }
        }
        runCatching { player.detachViews() }
        runCatching { activePlaybackController?.takeIf { it.kind == PlaybackBackendKind.LIBVLC }?.release() }
        if (activePlaybackController?.kind == PlaybackBackendKind.LIBVLC) {
            activePlaybackController = null
        }
    }
    libVlcPlayer = null
    libVlc?.release()
    libVlc = null
    libVlcDisplayManager?.release()
    libVlcDisplayManager = null
    libVlcVideoLayout = null
    libVlcVoutCallback = null
    libVlcViewsAttached = false
    pendingLibVlcAutoStart = false
    pendingLibVlcSeekPositionMs = null
}

internal fun PlayerRuntimeController.releaseCurrentMedia3BackendOnly() {
    cancelPauseOverlay()
    notifyAudioSessionUpdate(false)
    runCatching {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
    }
    runCatching {
        currentMediaSession?.release()
        currentMediaSession = null
    }
    _exoPlayer?.let { player ->
        if (activePlaybackController?.kind == PlaybackBackendKind.MEDIA3) {
            activePlaybackController = null
        }
        runCatching { player.release() }
    }
    _exoPlayer = null
}

private fun PlayerRuntimeController.maybeStartAttachedLibVlcPlayback() {
    val player = libVlcPlayer ?: return
    val videoLayout = libVlcVideoLayout ?: return
    videoLayout.post {
        if (libVlcPlayer !== player || libVlcVideoLayout !== videoLayout) return@post
        if (libVlcViewsAttached) {
            startPendingLibVlcPlaybackIfNeeded()
            return@post
        }
        val displayManager = resolveLibVlcDisplayManager(videoLayout)
        runCatching {
            player.attachViews(videoLayout, displayManager, true, false)
            libVlcViewsAttached = true
            Log.i(
                PlayerRuntimeController.TAG,
                "LIBVLC: video layout attached width=${videoLayout.width} height=${videoLayout.height} " +
                    "displayManager=${displayManager != null}"
            )
        }.onFailure { error ->
            Log.w(PlayerRuntimeController.TAG, "LIBVLC: video layout attach failed", error)
            libVlcViewsAttached = false
            return@post
        }
        _uiState.update {
            it.copy(
                showLoadingOverlay = false,
                showControls = true
            )
        }
        startPendingLibVlcPlaybackIfNeeded()
    }
}

private fun PlayerRuntimeController.startPendingLibVlcPlaybackIfNeeded() {
    val player = libVlcPlayer ?: return
    if (!pendingLibVlcAutoStart) return
    pendingLibVlcAutoStart = false
    Log.i(PlayerRuntimeController.TAG, "LIBVLC: starting playback")
    player.play()
    val seekPosition = pendingLibVlcSeekPositionMs
    pendingLibVlcSeekPositionMs = null
    if (seekPosition != null && seekPosition > 0L) {
        scope.launch {
            delay(LIBVLC_SEEK_APPLY_DELAY_MS)
            libVlcPlayer?.time = seekPosition
        }
    }
}

private fun PlayerRuntimeController.resolveLibVlcDisplayManager(
    videoLayout: VLCVideoLayout
): DisplayManager? {
    libVlcDisplayManager?.let { return it }
    val activity = currentHostActivity() ?: videoLayout.context.findHostActivity()
    if (activity == null) {
        Log.w(PlayerRuntimeController.TAG, "LIBVLC: no host activity available for DisplayManager")
        return null
    }
    return runCatching {
        DisplayManager(activity, libVlcRendererLiveData, false, false, false).also {
            libVlcDisplayManager = it
        }
    }.onFailure { error ->
        Log.w(PlayerRuntimeController.TAG, "LIBVLC: failed to create DisplayManager", error)
    }.getOrNull()
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findHostActivity()
    else -> null
}

private fun describeLibVlcEvent(event: MediaPlayer.Event): String =
    when (event.type) {
        MediaPlayer.Event.Opening -> "opening"
        MediaPlayer.Event.Buffering -> "buffering(${event.buffering})"
        MediaPlayer.Event.Playing -> "playing"
        MediaPlayer.Event.Paused -> "paused"
        MediaPlayer.Event.Stopped -> "stopped"
        MediaPlayer.Event.EndReached -> "end"
        MediaPlayer.Event.EncounteredError -> "error"
        MediaPlayer.Event.Vout -> "vout(${event.voutCount})"
        MediaPlayer.Event.TimeChanged -> "time(${event.timeChanged})"
        MediaPlayer.Event.PositionChanged -> "position(${event.positionChanged})"
        MediaPlayer.Event.SeekableChanged -> "seekable(${event.seekable})"
        MediaPlayer.Event.PausableChanged -> "pausable(${event.pausable})"
        else -> "type=${event.type}"
    }
