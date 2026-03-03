package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.player.PlaybackBackendKind
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer

internal interface PlaybackBackendController {
    val kind: PlaybackBackendKind
    fun currentPositionMs(): Long
    fun durationMs(): Long
    fun bufferedPositionMs(): Long
    fun isPlaying(): Boolean
    fun isLoading(): Boolean
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun release()
}

internal class Media3PlaybackBackend(
    private val player: ExoPlayer
) : PlaybackBackendController {
    override val kind: PlaybackBackendKind = PlaybackBackendKind.MEDIA3

    override fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    override fun durationMs(): Long = player.duration.coerceAtLeast(0L)

    override fun bufferedPositionMs(): Long = player.bufferedPosition.coerceAtLeast(0L)

    override fun isPlaying(): Boolean = player.isPlaying

    override fun isLoading(): Boolean = player.isLoading

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun setPlaybackSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    override fun release() = player.release()
}

internal class LibVlcPlaybackBackend(
    private val player: VlcMediaPlayer
) : PlaybackBackendController {
    override val kind: PlaybackBackendKind = PlaybackBackendKind.LIBVLC

    override fun currentPositionMs(): Long = player.time.coerceAtLeast(0L)

    override fun durationMs(): Long = player.length.coerceAtLeast(0L)

    override fun bufferedPositionMs(): Long {
        val length = durationMs()
        if (length <= 0L) return currentPositionMs()
        val percent = player.position.coerceIn(0f, 1f)
        return (length * percent).toLong()
    }

    override fun isPlaying(): Boolean = player.isPlaying

    override fun isLoading(): Boolean = !player.isPlaying

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) {
        player.time = positionMs.coerceAtLeast(0L)
    }

    override fun setPlaybackSpeed(speed: Float) {
        runCatching { player.rate = speed.coerceAtLeast(0.25f) }
    }

    override fun release() {
        player.stop()
        player.release()
    }
}
