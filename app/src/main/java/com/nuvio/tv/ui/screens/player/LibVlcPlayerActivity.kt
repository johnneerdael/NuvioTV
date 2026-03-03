package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.MutableLiveData
import com.nuvio.tv.core.player.LibVlcPlaybackConfig
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.DisplayManager
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs

class LibVlcPlayerActivity : ComponentActivity(), IVLCVout.Callback {

    companion object {
        const val EXTRA_STREAM_URL = "libvlc_stream_url"
        const val EXTRA_TITLE = "libvlc_title"
        const val EXTRA_SUBTITLE_URL = "libvlc_subtitle_url"
        private const val TAG = "LibVlcActivity"
    }

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var rootContainer: FrameLayout? = null
    private var videoLayout: VLCVideoLayout? = null
    private var displayManager: DisplayManager? = null
    private var loadingOverlay: FrameLayout? = null
    private var loadingView: ProgressBar? = null
    private var loadingTextView: TextView? = null
    private var controlsComposeView: ComposeView? = null
    private var playbackStarted = false
    private var videoOutputReady = false
    private var viewsAttached = false
    private var playRequested = false
    private var pendingStreamUrl: String? = null
    private var explicitSubtitleUrl: String? = null
    private var lastLoggedTimeMs = Long.MIN_VALUE
    private var lastLoggedPosition = Float.NaN
    private var hostLabel = "unknown"
    private var streamTitle = ""
    private var audioBackendMode = LibVlcPlaybackConfig.AudioOutputMode.DEFAULT
    private var audioFallbackOrder: List<LibVlcPlaybackConfig.AudioOutputMode> = emptyList()
    private var audioFallbackIndex = 0
    private var hardwareAccelerationMode = LibVlcPlaybackConfig.HardwareAccelerationMode.AUTOMATIC
    private var hardwareAccelerationRetried = false
    private var mediaRouterCallbackRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioRetryRunnable: Runnable? = null
    private var hideControlsRunnable: Runnable? = null
    private var renderWatchdogRunnable: Runnable? = null
    private var lastSurfaceRedrawAtMs = 0L
    private var lastTimeAtRenderWatchdogMs = Long.MIN_VALUE
    private var renderWatchdogNoRedrawCount = 0
    private val selectedRenderer = MutableLiveData<RendererItem?>(null)
    private var overlayVisibleState by mutableStateOf(true)
    private var overlayIsPlayingState by mutableStateOf(false)
    private var overlayCurrentPositionMs by mutableLongStateOf(0L)
    private var overlayDurationMs by mutableLongStateOf(0L)
    private var audioTracksState by mutableStateOf<List<TrackInfo>>(emptyList())
    private var subtitleTracksState by mutableStateOf<List<TrackInfo>>(emptyList())
    private var selectedAudioTrackIndexState by mutableStateOf(-1)
    private var selectedSubtitleTrackIndexState by mutableStateOf(-1)
    private var showAudioDialogState by mutableStateOf(false)
    private var showSubtitleDialogState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        actionBar?.hide()
        title = ""
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (streamUrl.isBlank()) {
            finish()
            return
        }
        streamTitle = title
        explicitSubtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL)?.takeIf { it.isNotBlank() }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val playerLayout = VLCVideoLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = false
        }
        val loadingOverlayView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(96, 0, 0, 0))
        }
        val progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val loadingText = TextView(this).apply {
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
        loadingOverlayView.addView(progress)
        loadingOverlayView.addView(loadingText)

        val controlsView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setContent {
                LibVlcControlsOverlay(
                    visible = overlayVisibleState,
                    title = streamTitle,
                    isPlaying = overlayIsPlayingState,
                    hasAudioTracks = audioTracksState.isNotEmpty(),
                    hasSubtitleTracks = subtitleTracksState.isNotEmpty() || !explicitSubtitleUrl.isNullOrBlank(),
                    currentPositionMs = overlayCurrentPositionMs,
                    durationMs = overlayDurationMs,
                    onBack = { finish() },
                    onShowAudioTracks = {
                        showAudioDialogState = true
                        showControlsTemporarily()
                    },
                    onShowSubtitleTracks = {
                        showSubtitleDialogState = true
                        showControlsTemporarily()
                    },
                    onSeekBackward = { seekBy(-10_000L) },
                    onPlayPause = { togglePlayback() },
                    onSeekForward = { seekBy(10_000L) }
                )

                if (showAudioDialogState) {
                    LibVlcTrackSelectionDialog(
                        title = "Audio",
                        tracks = audioTracksState,
                        selectedIndex = selectedAudioTrackIndexState,
                        onTrackSelected = { trackId ->
                            selectAudioTrack(trackId)
                            showAudioDialogState = false
                            showControlsTemporarily()
                        },
                        onDismiss = { showAudioDialogState = false }
                    )
                }

                if (showSubtitleDialogState) {
                    LibVlcTrackSelectionDialog(
                        title = "Subtitles",
                        tracks = subtitleTracksState,
                        selectedIndex = selectedSubtitleTrackIndexState,
                        noneOptionLabel = "Off",
                        onTrackSelected = { trackId ->
                            selectSubtitleTrack(trackId)
                            showSubtitleDialogState = false
                            showControlsTemporarily()
                        },
                        onDismiss = { showSubtitleDialogState = false }
                    )
                }
            }
        }

        root.addView(playerLayout)
        root.addView(loadingOverlayView)
        root.addView(controlsView)
        setContentView(root)
        rootContainer = root
        onBackPressedDispatcher.addCallback(this) {
            when {
                showAudioDialogState -> showAudioDialogState = false
                showSubtitleDialogState -> showSubtitleDialogState = false
                else -> finish()
            }
        }

        root.setOnClickListener { toggleControlsOverlay() }
        playerLayout.setOnClickListener { toggleControlsOverlay() }

        videoLayout = playerLayout
        loadingOverlay = loadingOverlayView
        loadingView = progress
        loadingTextView = loadingText
        controlsComposeView = controlsView
        audioBackendMode = LibVlcPlaybackConfig.loadPreferredAudioOutput(this)
        audioFallbackOrder = LibVlcPlaybackConfig.buildAudioFallbackOrder(audioBackendMode)
        audioFallbackIndex = audioFallbackOrder.indexOf(audioBackendMode).coerceAtLeast(0)
        hardwareAccelerationMode = LibVlcPlaybackConfig.loadHardwareAccelerationMode(this)
        val options = LibVlcPlaybackConfig.buildLibVlcOptions(this, audioBackendMode)
        hostLabel = runCatching { Uri.parse(streamUrl).host ?: "unknown" }.getOrDefault("unknown")
        Log.i(
            TAG,
            "Starting dedicated LibVLC activity host=$hostLabel audio=$audioBackendMode " +
                "hw=$hardwareAccelerationMode subtitle=${!explicitSubtitleUrl.isNullOrBlank()}"
        )

        val vlc = LibVLC(this, options)
        pendingStreamUrl = streamUrl

        displayManager = DisplayManager(this, selectedRenderer, false, false, false)
        libVlc = vlc
        mediaPlayer = buildPlayer(vlc)
        root.post {
            attachAndStartPlayback()
        }
        showControlsTemporarily()
    }

    override fun onDestroy() {
        cancelAudioRetryCheck()
        cancelControlsHide()
        cancelRenderWatchdog()
        releaseCurrentPlayer()
        if (mediaRouterCallbackRegistered) {
            runCatching { displayManager?.removeMediaRouterCallback() }
            mediaRouterCallbackRegistered = false
        }
        runCatching { displayManager?.release() }
        displayManager = null
        runCatching { libVlc?.release() }
        libVlc = null
        rootContainer = null
        videoLayout = null
        loadingOverlay = null
        loadingView = null
        loadingTextView = null
        controlsComposeView = null
        super.onDestroy()
    }

    override fun onSurfacesCreated(vlcVout: IVLCVout?) {
        Log.i(TAG, "vout surfaces created")
        videoOutputReady = true
        lastSurfaceRedrawAtMs = SystemClock.elapsedRealtime()
        mediaPlayer?.let { player ->
            resetPopupStyleVideoOutput(player)
            player.setVideoTrackEnabled(true)
            player.updateVideoSurfaces()
            flushPlayerIfPossible(player)
        }
        disableSpuTrack()
        showLoading(false, null)
        showControlsTemporarily()
    }

    override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {
        Log.i(TAG, "vout surfaces destroyed")
        viewsAttached = false
        videoOutputReady = false
        runCatching { vlcVout?.removeCallback(this) }
    }

    private fun attachAndStartPlayback() {
        val player = mediaPlayer ?: return
        val vlc = libVlc ?: return
        val targetLayout = videoLayout ?: return
        val streamUrl = pendingStreamUrl ?: return

        if (!viewsAttached) {
            val attachDisplayManager = displayManagerForAttach()
            runCatching { player.detachViews() }
            player.attachViews(targetLayout, attachDisplayManager, true, false)
            player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            viewsAttached = true
            Log.i(
                TAG,
                "attached VLCVideoLayout host via VideoHelper " +
                    "displayManager=${attachDisplayManager != null} subtitlesSurface=true"
            )
        }
        if (!player.hasMedia()) {
            val media = Media(vlc, Uri.parse(streamUrl)).apply {
                LibVlcPlaybackConfig.applyMediaOptions(
                    media = this,
                    mode = hardwareAccelerationMode,
                    allowSubtitleAutoload = false
                )
            }
            player.media = media
            media.release()
            player.setVideoTrackEnabled(true)
            attachSidecarSubtitleIfNeeded(player)
            disableSpuTrack()
        }
        if (!playRequested) {
            playRequested = true
            player.play()
            Log.i(TAG, "play requested after host attach")
        }
    }

    private fun buildPlayer(vlc: LibVLC): MediaPlayer {
        val player = MediaPlayer(vlc)
        player.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0)
        runCatching { player.setAudioDigitalOutputEnabled(false) }
        runCatching { player.setRenderer(selectedRenderer.value) }
            .onFailure { Log.w(TAG, "setRenderer failed", it) }
        when (audioBackendMode) {
            LibVlcPlaybackConfig.AudioOutputMode.DEFAULT -> Log.i(TAG, "audioOutput=default")
            LibVlcPlaybackConfig.AudioOutputMode.OPENSLES,
            LibVlcPlaybackConfig.AudioOutputMode.AUDIOTRACK -> runCatching {
                player.setAudioOutput(audioBackendMode.libVlcValue)
                Log.i(TAG, "audioOutput=${audioBackendMode.persistedValue}")
            }.onFailure {
                Log.w(TAG, "audioOutput=${audioBackendMode.persistedValue} failed", it)
            }
        }
        runCatching { player.vlcVout.addCallback(this) }
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
                    overlayIsPlayingState = true
                    ensureMediaRouterCallbackRegistered()
                    showLoading(false, null)
                    showControlsTemporarily()
                    refreshTrackState()
                    logTrackState()
                    startRenderWatchdog()
                    scheduleAudioRetryCheck()
                }
                MediaPlayer.Event.TimeChanged -> {
                    val time = event.timeChanged
                    if (abs(time - lastLoggedTimeMs) >= 950L || lastLoggedTimeMs == Long.MIN_VALUE) {
                        lastLoggedTimeMs = time
                        overlayCurrentPositionMs = time
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
                    overlayDurationMs = event.lengthChanged
                    Log.i(TAG, "event=length host=$hostLabel value=${event.lengthChanged}")
                }
                MediaPlayer.Event.Paused -> {
                    overlayIsPlayingState = false
                    Log.i(TAG, "event=paused host=$hostLabel")
                    showControlsTemporarily()
                }
                MediaPlayer.Event.Stopped -> {
                    overlayIsPlayingState = false
                    Log.i(TAG, "event=stopped host=$hostLabel")
                }
                MediaPlayer.Event.Vout -> {
                    Log.i(TAG, "event=vout host=$hostLabel count=${event.voutCount}")
                    if (event.voutCount > 0) {
                        lastSurfaceRedrawAtMs = SystemClock.elapsedRealtime()
                        videoOutputReady = true
                        runCatching { player.updateVideoSurfaces() }
                            .onFailure { Log.w(TAG, "updateVideoSurfaces failed", it) }
                        refreshTrackState()
                        disableSpuTrack()
                        logTrackState()
                        showLoading(false, null)
                        showControlsTemporarily()
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "event=error host=$hostLabel")
                    if (!restartWithNextAudioOutput("encountered-error")) {
                        overlayIsPlayingState = false
                        showLoading(false, "LibVLC playback failed")
                    } else {
                        Unit
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

    private fun displayManagerForAttach(): DisplayManager? {
        val manager = displayManager ?: return null
        val isPrimary = runCatching { manager.isPrimary }.getOrDefault(true)
        val isOnRenderer = runCatching { manager.isOnRenderer() }.getOrDefault(false)
        val displayType = runCatching { manager.displayType.toString() }.getOrDefault("unknown")
        val hasPresentation = runCatching { manager.presentation != null }.getOrDefault(false)
        val usePrimaryAttachPath = isPrimary && !isOnRenderer && !hasPresentation
        if (!usePrimaryAttachPath) {
            Log.w(
                TAG,
                "forcing local primary attach path " +
                    "isPrimary=$isPrimary isOnRenderer=$isOnRenderer " +
                    "displayType=$displayType hasPresentation=$hasPresentation"
            )
            return null
        }
        Log.i(
            TAG,
            "using DisplayManager primary attach path " +
                "isPrimary=$isPrimary isOnRenderer=$isOnRenderer " +
                "displayType=$displayType hasPresentation=$hasPresentation"
        )
        return manager
    }

    private fun ensureMediaRouterCallbackRegistered() {
        if (mediaRouterCallbackRegistered) return
        val registered = runCatching { displayManager?.setMediaRouterCallback() == true }
            .getOrDefault(false)
        mediaRouterCallbackRegistered = registered
        Log.i(TAG, "mediaRouterCallbackRegistered=$registered")
    }

    private fun scheduleAudioRetryCheck() {
        cancelAudioRetryCheck()
        val runnable = Runnable {
            if (isFinishing || isDestroyed) {
                return@Runnable
            }
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val musicActive = audioManager?.isMusicActive == true
            val playbackAdvanced = lastLoggedTimeMs >= 3000L
            Log.i(
                TAG,
                "audioRetryCheck backend=$audioBackendMode musicActive=$musicActive " +
                    "playbackAdvanced=$playbackAdvanced timeMs=$lastLoggedTimeMs"
            )
            if (musicActive && playbackAdvanced) {
                LibVlcPlaybackConfig.markAudioOutputWorking(this, audioBackendMode)
            } else if (!musicActive && playbackAdvanced) {
                restartWithNextAudioOutput("music-inactive-after-playing")
            }
        }
        audioRetryRunnable = runnable
        mainHandler.postDelayed(runnable, 4500L)
    }

    private fun cancelAudioRetryCheck() {
        audioRetryRunnable?.let(mainHandler::removeCallbacks)
        audioRetryRunnable = null
    }

    private fun restartWithNextAudioOutput(reason: String): Boolean {
        val nextMode = audioFallbackOrder.getOrNull(audioFallbackIndex + 1) ?: return false
        audioFallbackIndex += 1
        restartWithAudioBackend(nextMode, reason)
        return true
    }

    private fun restartWithAudioBackend(mode: LibVlcPlaybackConfig.AudioOutputMode, reason: String) {
        if (libVlc == null) return
        Log.i(TAG, "restartWithAudioBackend mode=$mode reason=$reason")
        cancelAudioRetryCheck()
        audioBackendMode = mode
        releaseCurrentPlayer()
        mediaPlayer = buildPlayer(libVlc ?: return)
        playbackStarted = false
        overlayIsPlayingState = false
        videoOutputReady = false
        viewsAttached = false
        playRequested = false
        lastLoggedTimeMs = Long.MIN_VALUE
        lastLoggedPosition = Float.NaN
        renderWatchdogNoRedrawCount = 0
        showLoading(true, "Retrying audio output...")
        attachAndStartPlayback()
    }

    private fun restartWithHardwareAcceleration(
        mode: LibVlcPlaybackConfig.HardwareAccelerationMode,
        reason: String
    ) {
        if (libVlc == null) return
        Log.i(TAG, "restartWithHardwareAcceleration mode=$mode reason=$reason")
        cancelAudioRetryCheck()
        hardwareAccelerationRetried = true
        hardwareAccelerationMode = mode
        releaseCurrentPlayer()
        mediaPlayer = buildPlayer(libVlc ?: return)
        playbackStarted = false
        overlayIsPlayingState = false
        videoOutputReady = false
        viewsAttached = false
        playRequested = false
        lastLoggedTimeMs = Long.MIN_VALUE
        lastLoggedPosition = Float.NaN
        renderWatchdogNoRedrawCount = 0
        showLoading(true, "Retrying video output...")
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
        viewsAttached = false
        mediaPlayer = null
    }

    private fun disableSpuTrack() {
        if (!explicitSubtitleUrl.isNullOrBlank()) return
        runCatching {
            mediaPlayer?.setSpuTrack(-1)
            Log.i(TAG, "spuTrackDisabled")
        }.onFailure {
            Log.w(TAG, "spuTrack disable failed", it)
        }
    }

    private fun showLoading(loading: Boolean, message: String?) {
        runOnUiThread {
            loadingOverlay?.visibility = if (loading || !message.isNullOrBlank()) View.VISIBLE else View.GONE
            loadingView?.visibility = if (loading) View.VISIBLE else View.GONE
            loadingTextView?.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
            if (!message.isNullOrBlank()) {
                loadingTextView?.text = message
            }
        }
    }

    private fun showControlsTemporarily() {
        overlayVisibleState = true
        if (playbackStarted) {
            scheduleHideControls()
        } else {
            cancelControlsHide()
        }
    }

    private fun toggleControlsOverlay() {
        if (overlayVisibleState && playbackStarted) {
            overlayVisibleState = false
            cancelControlsHide()
        } else {
            showControlsTemporarily()
        }
    }

    private fun scheduleHideControls() {
        cancelControlsHide()
        val runnable = Runnable {
            overlayVisibleState = false
        }
        hideControlsRunnable = runnable
        mainHandler.postDelayed(runnable, 3000L)
    }

    private fun cancelControlsHide() {
        hideControlsRunnable?.let(mainHandler::removeCallbacks)
        hideControlsRunnable = null
    }

    private fun startRenderWatchdog() {
        cancelRenderWatchdog()
        val runnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed || !playbackStarted) return
                val now = SystemClock.elapsedRealtime()
                val timeMs = lastLoggedTimeMs
                if (timeMs > 0L && timeMs > lastTimeAtRenderWatchdogMs) {
                    if (lastSurfaceRedrawAtMs > 0L && now - lastSurfaceRedrawAtMs > 5000L) {
                        renderWatchdogNoRedrawCount += 1
                        Log.w(
                            TAG,
                            "renderWatchdog playbackAdvancedWithoutRedraw count=$renderWatchdogNoRedrawCount " +
                                "timeMs=$timeMs staleMs=${now - lastSurfaceRedrawAtMs}"
                        )
                    } else {
                        renderWatchdogNoRedrawCount = 0
                    }
                }
                lastTimeAtRenderWatchdogMs = timeMs
                mainHandler.postDelayed(this, 5000L)
            }
        }
        renderWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, 5000L)
    }

    private fun cancelRenderWatchdog() {
        renderWatchdogRunnable?.let(mainHandler::removeCallbacks)
        renderWatchdogRunnable = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    showControlsTemporarily()
                    togglePlayback()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> showControlsTemporarily()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refreshTrackState() {
        val player = mediaPlayer ?: return
        audioTracksState = readTrackDescriptions(player, "getAudioTracks")
        subtitleTracksState = readTrackDescriptions(player, "getSpuTracks")
        selectedAudioTrackIndexState = readSelectedTrackId(player, "getAudioTrack")
        selectedSubtitleTrackIndexState = if (explicitSubtitleUrl.isNullOrBlank()) {
            readSelectedTrackId(player, "getSpuTrack")
        } else {
            // Sidecar selected by default when present.
            readSelectedTrackId(player, "getSpuTrack").takeIf { it >= 0 } ?: 0
        }
    }

    private fun togglePlayback() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                overlayIsPlayingState = false
            } else {
                player.play()
                overlayIsPlayingState = true
            }
        }
    }

    private fun seekBy(deltaMs: Long) {
        val player = mediaPlayer ?: return
        val current = player.time.coerceAtLeast(0L)
        val duration = overlayDurationMs.takeIf { it > 0L } ?: player.length.coerceAtLeast(0L)
        val target = (current + deltaMs).coerceAtLeast(0L).let { candidate ->
            if (duration > 0L) candidate.coerceAtMost(duration) else candidate
        }
        player.time = target
        overlayCurrentPositionMs = target
        showControlsTemporarily()
    }

    private fun selectAudioTrack(trackId: Int) {
        val player = mediaPlayer ?: return
        invokeTrackSetter(player, "setAudioTrack", trackId)
        selectedAudioTrackIndexState = trackId
        refreshTrackState()
    }

    private fun selectSubtitleTrack(trackId: Int) {
        val player = mediaPlayer ?: return
        invokeTrackSetter(player, "setSpuTrack", trackId)
        selectedSubtitleTrackIndexState = trackId
        refreshTrackState()
    }

    private fun attachSidecarSubtitleIfNeeded(player: MediaPlayer) {
        val subtitleUrl = explicitSubtitleUrl ?: return
        runCatching {
            player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(subtitleUrl), true)
            Log.i(TAG, "sidecarSubtitleAttached")
        }.onFailure {
            Log.w(TAG, "sidecarSubtitleAttach failed", it)
        }
    }

    private fun logTrackState() {
        val player = mediaPlayer ?: return
        val audioCount = runCatching { player.getAudioTracksCount() }.getOrDefault(-1)
        val spuCount = runCatching { player.getSpuTracksCount() }.getOrDefault(-1)
        val canPassthrough = runCatching { player.canDoPassthrough() }.getOrDefault(false)
        Log.i(
            TAG,
            "tracks audio=$audioCount spu=$spuCount passthrough=$canPassthrough"
        )
    }

    private fun readTrackDescriptions(player: MediaPlayer, methodName: String): List<TrackInfo> {
        val descriptions = runCatching {
            val method = player.javaClass.getMethod(methodName)
            method.invoke(player) as? Array<*>
        }.getOrNull() ?: return emptyList()

        return descriptions.mapNotNull { description ->
            if (description == null) return@mapNotNull null
            val id = readTrackId(description) ?: return@mapNotNull null
            val name = readTrackName(description) ?: "Track $id"
            TrackInfo(
                index = id,
                name = name,
                language = null
            )
        }
    }

    private fun readSelectedTrackId(player: MediaPlayer, methodName: String): Int {
        return runCatching {
            val method = player.javaClass.getMethod(methodName)
            (method.invoke(player) as? Number)?.toInt()
        }.getOrNull() ?: -1
    }

    private fun invokeTrackSetter(player: MediaPlayer, methodName: String, trackId: Int) {
        runCatching {
            val method = player.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
            method.invoke(player, trackId)
        }.onFailure {
            Log.w(TAG, "$methodName failed for trackId=$trackId", it)
        }
    }

    private fun readTrackId(description: Any): Int? {
        val directField = runCatching {
            description.javaClass.getField("id").get(description)
        }.getOrNull()
        if (directField is Number) return directField.toInt()

        val getterValue = runCatching {
            description.javaClass.methods.firstOrNull {
                it.name.equals("getId", ignoreCase = true) && it.parameterCount == 0
            }?.invoke(description)
        }.getOrNull()
        return (getterValue as? Number)?.toInt()
    }

    private fun readTrackName(description: Any): String? {
        val directField = runCatching {
            description.javaClass.getField("name").get(description)
        }.getOrNull()
        if (directField is String && directField.isNotBlank()) return directField

        val getterValue = runCatching {
            description.javaClass.methods.firstOrNull {
                it.name.equals("getName", ignoreCase = true) && it.parameterCount == 0
            }?.invoke(description)
        }.getOrNull()
        return (getterValue as? String)?.takeIf { it.isNotBlank() }
    }

    private fun resetPopupStyleVideoOutput(player: MediaPlayer) {
        runCatching { player.setAspectRatio(null) }
            .onFailure { Log.w(TAG, "setAspectRatio reset failed", it) }
        runCatching { player.setScale(0f) }
            .onFailure { Log.w(TAG, "setScale reset failed", it) }
    }

    private fun flushPlayerIfPossible(player: MediaPlayer) {
        runCatching {
            val method = player.javaClass.methods.firstOrNull {
                it.name == "flush" && it.parameterCount == 0
            } ?: return
            method.invoke(player)
            Log.i(TAG, "playerFlushed")
        }.onFailure {
            Log.w(TAG, "player flush failed", it)
        }
    }
}
