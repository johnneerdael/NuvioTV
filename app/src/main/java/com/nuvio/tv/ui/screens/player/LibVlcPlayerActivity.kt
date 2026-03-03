package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.AudioManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

class LibVlcPlayerActivity : ComponentActivity(), IVLCVout.Callback, IVLCVout.OnNewVideoLayoutListener, SurfaceHolder.Callback {

    private enum class AudioBackendMode {
        DEFAULT,
        OPENSLES,
    }

    companion object {
        const val EXTRA_STREAM_URL = "libvlc_stream_url"
        const val EXTRA_TITLE = "libvlc_title"
        private const val TAG = "LibVlcActivity"
    }

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var loadingView: ProgressBar? = null
    private var statusView: TextView? = null
    private var playbackStarted = false
    private var videoOutputReady = false
    private var surfaceReady = false
    private var viewsAttached = false
    private var playRequested = false
    private var pendingStreamUrl: String? = null
    private var lastLoggedTimeMs = Long.MIN_VALUE
    private var lastLoggedPosition = Float.NaN
    private var hostLabel = "unknown"
    private var audioBackendMode = AudioBackendMode.DEFAULT
    private var audioBackendRetried = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioRetryRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (streamUrl.isBlank()) {
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val videoSurface = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            text = if (title.isNotBlank()) "Opening in LibVLC\n$title" else "Opening in LibVLC"
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply {
                bottomMargin = 96
            }
        }
        root.addView(videoSurface)
        root.addView(progress)
        root.addView(status)
        setContentView(root)

        surfaceView = videoSurface
        loadingView = progress
        statusView = status

        val options = arrayListOf(
            "--network-caching=5000",
            "--file-caching=5000",
            "--avcodec-hw=any",
            "--drop-late-frames",
            "--skip-frames"
        )
        val host = runCatching { Uri.parse(streamUrl).host ?: "unknown" }
            .getOrDefault("unknown")
        hostLabel = host
        Log.i(TAG, "Starting dedicated LibVLC activity host=$host")

        val vlc = LibVLC(this, options)
        pendingStreamUrl = streamUrl

        libVlc = vlc
        mediaPlayer = buildPlayer(vlc)

        videoSurface.holder.addCallback(this)
    }

    override fun onDestroy() {
        cancelAudioRetryCheck()
        releaseCurrentPlayer()
        runCatching { libVlc?.release() }
        libVlc = null
        runCatching { surfaceView?.holder?.removeCallback(this) }
        surfaceView = null
        loadingView = null
        statusView = null
        super.onDestroy()
    }

    override fun onSurfacesCreated(vlcVout: IVLCVout?) {
        Log.i(TAG, "vout surfaces created")
        videoOutputReady = true
        mediaPlayer?.setVideoTrackEnabled(true)
        disableSpuTrack()
        val targetSurface = surfaceView
        if (targetSurface != null) {
            runCatching {
                mediaPlayer?.vlcVout?.setWindowSize(targetSurface.width, targetSurface.height)
                Log.i(TAG, "setWindowSize width=${targetSurface.width} height=${targetSurface.height}")
            }.onFailure {
                Log.w(TAG, "setWindowSize failed", it)
            }
        }
        showLoading(false, null)
    }

    override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {
        Log.i(TAG, "vout surfaces destroyed")
    }

    override fun onNewVideoLayout(
        vlcVout: IVLCVout,
        width: Int,
        height: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sarNum: Int,
        sarDen: Int
        ) {
        Log.i(
            TAG,
            "newVideoLayout width=$width height=$height visibleWidth=$visibleWidth visibleHeight=$visibleHeight sar=$sarNum/$sarDen"
        )
        videoOutputReady = true
        disableSpuTrack()
        showLoading(false, null)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceCreated")
        surfaceReady = true
        attachAndStartPlayback()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surfaceChanged width=$width height=$height")
        runCatching {
            mediaPlayer?.vlcVout?.setWindowSize(width, height)
            Log.i(TAG, "surfaceChanged setWindowSize width=$width height=$height")
        }.onFailure {
            Log.w(TAG, "surfaceChanged setWindowSize failed", it)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceDestroyed")
        surfaceReady = false
    }

    private fun attachAndStartPlayback() {
        val player = mediaPlayer ?: return
        val vlc = libVlc ?: return
        val targetSurface = surfaceView ?: return
        val streamUrl = pendingStreamUrl ?: return
        if (!surfaceReady) return

        if (!viewsAttached) {
            player.vlcVout.setVideoView(targetSurface)
            player.vlcVout.attachViews(this)
            viewsAttached = true
            Log.i(TAG, "attached explicit surface view")
        }
        if (!player.hasMedia()) {
            val media = Media(vlc, Uri.parse(streamUrl)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":sub-language=none")
                addOption(":no-sub-autodetect-file")
            }
            player.media = media
            media.release()
            player.setVideoTrackEnabled(true)
            disableSpuTrack()
        }
        if (!playRequested) {
            playRequested = true
            player.play()
            Log.i(TAG, "play requested after surface ready")
        }
    }

    private fun buildPlayer(vlc: LibVLC): MediaPlayer {
        val player = MediaPlayer(vlc)
        player.vlcVout.addCallback(this)
        player.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0)
        player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        runCatching { player.setAudioDigitalOutputEnabled(false) }
        when (audioBackendMode) {
            AudioBackendMode.DEFAULT -> Log.i(TAG, "audioOutput=default")
            AudioBackendMode.OPENSLES -> runCatching {
                player.setAudioOutput("opensles")
                Log.i(TAG, "audioOutput=opensles")
            }.onFailure {
                Log.w(TAG, "audioOutput=opensles failed", it)
            }
        }
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    Log.i(TAG, "event=opening host=$hostLabel")
                    playbackStarted = false
                    videoOutputReady = false
                    showLoading(true, "Opening in LibVLC")
                }
                MediaPlayer.Event.Buffering -> {
                    Log.i(TAG, "event=buffering host=$hostLabel value=${event.buffering}")
                    if (!playbackStarted && !videoOutputReady) {
                        showLoading(true, "Buffering ${event.buffering.toInt()}%")
                    }
                }
                MediaPlayer.Event.Playing -> {
                    Log.i(TAG, "event=playing host=$hostLabel")
                    playbackStarted = true
                    showLoading(false, null)
                    scheduleAudioRetryCheck()
                }
                MediaPlayer.Event.TimeChanged -> {
                    val time = event.timeChanged
                    if (kotlin.math.abs(time - lastLoggedTimeMs) >= 1000L || lastLoggedTimeMs == Long.MIN_VALUE) {
                        lastLoggedTimeMs = time
                        Log.i(TAG, "event=time host=$hostLabel ms=$time")
                    }
                }
                MediaPlayer.Event.PositionChanged -> {
                    val position = event.positionChanged
                    if (lastLoggedPosition.isNaN() || kotlin.math.abs(position - lastLoggedPosition) >= 0.01f) {
                        lastLoggedPosition = position
                        Log.i(TAG, "event=position host=$hostLabel value=$position")
                    }
                }
                MediaPlayer.Event.LengthChanged -> {
                    Log.i(TAG, "event=length host=$hostLabel value=${event.lengthChanged}")
                }
                MediaPlayer.Event.Vout -> {
                    Log.i(TAG, "event=vout host=$hostLabel count=${event.voutCount}")
                    if (event.voutCount > 0) {
                        videoOutputReady = true
                        disableSpuTrack()
                        showLoading(false, null)
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "event=error host=$hostLabel")
                    if (!audioBackendRetried && audioBackendMode == AudioBackendMode.DEFAULT) {
                        restartWithAudioBackend(AudioBackendMode.OPENSLES, "encountered-error")
                    } else {
                        showLoading(false, "LibVLC playback failed")
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    Log.i(TAG, "event=end host=$hostLabel")
                    finish()
                }
            }
        }
        return player
    }

    private fun scheduleAudioRetryCheck() {
        cancelAudioRetryCheck()
        if (audioBackendRetried || audioBackendMode != AudioBackendMode.DEFAULT) return
        val runnable = Runnable {
            if (audioBackendRetried || audioBackendMode != AudioBackendMode.DEFAULT || isFinishing || isDestroyed) {
                return@Runnable
            }
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val musicActive = audioManager?.isMusicActive == true
            val playbackAdvanced = lastLoggedTimeMs >= 3000L
            Log.i(
                TAG,
                "audioRetryCheck backend=$audioBackendMode musicActive=$musicActive playbackAdvanced=$playbackAdvanced timeMs=$lastLoggedTimeMs"
            )
            if (!musicActive && playbackAdvanced) {
                restartWithAudioBackend(AudioBackendMode.OPENSLES, "music-inactive-after-playing")
            }
        }
        audioRetryRunnable = runnable
        mainHandler.postDelayed(runnable, 4500L)
    }

    private fun cancelAudioRetryCheck() {
        val runnable = audioRetryRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        audioRetryRunnable = null
    }

    private fun restartWithAudioBackend(mode: AudioBackendMode, reason: String) {
        if (audioBackendRetried && mode == AudioBackendMode.OPENSLES) return
        if (libVlc == null) return
        Log.i(TAG, "restartWithAudioBackend mode=$mode reason=$reason")
        cancelAudioRetryCheck()
        audioBackendMode = mode
        if (mode == AudioBackendMode.OPENSLES) {
            audioBackendRetried = true
        }
        releaseCurrentPlayer()
        mediaPlayer = buildPlayer(libVlc ?: return)
        playbackStarted = false
        videoOutputReady = false
        viewsAttached = false
        playRequested = false
        lastLoggedTimeMs = Long.MIN_VALUE
        lastLoggedPosition = Float.NaN
        showLoading(true, "Retrying audio output...")
        attachAndStartPlayback()
    }

    private fun releaseCurrentPlayer() {
        val player = mediaPlayer ?: return
        runCatching { player.setEventListener(null) }
        runCatching { player.vlcVout.removeCallback(this) }
        runCatching { player.stop() }
        if (viewsAttached) {
            runCatching { player.detachViews() }
        }
        runCatching { player.release() }
        mediaPlayer = null
    }

    private fun disableSpuTrack() {
        runCatching {
            mediaPlayer?.setSpuTrack(-1)
            Log.i(TAG, "spuTrackDisabled")
        }.onFailure {
            Log.w(TAG, "spuTrack disable failed", it)
        }
    }

    private fun showLoading(loading: Boolean, message: String?) {
        runOnUiThread {
            loadingView?.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
            statusView?.visibility = if (message.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            if (!message.isNullOrBlank()) {
                statusView?.text = message
            }
        }
    }
}
