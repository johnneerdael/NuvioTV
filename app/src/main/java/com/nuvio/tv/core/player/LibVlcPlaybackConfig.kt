package com.nuvio.tv.core.player

import android.content.Context
import android.os.Build
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCUtil

object LibVlcPlaybackConfig {
    private const val PREFS_NAME = "libvlc_fallback"
    private const val KEY_AUDIO_OUTPUT_OVERRIDE = "audio_output_override"
    private const val KEY_AUDIO_OUTPUT_WORKING_PREFIX = "audio_output_working_"
    private const val KEY_HARDWARE_ACCELERATION = "hardware_acceleration"
    private const val KEY_NETWORK_CACHING_MS = "network_caching_ms"
    private const val KEY_VIDEO_OUTPUT = "video_output"
    private const val KEY_PREFERRED_RESOLUTION = "preferred_resolution"
    private const val KEY_CUSTOM_OPTIONS = "custom_options"

    enum class AudioOutputMode(val persistedValue: String, val libVlcValue: String?) {
        DEFAULT("default", null),
        OPENSLES("opensles", "opensles"),
        AUDIOTRACK("audiotrack", "audiotrack");

        companion object {
            fun fromPersisted(value: String?): AudioOutputMode {
                return entries.firstOrNull { it.persistedValue.equals(value, ignoreCase = true) } ?: DEFAULT
            }
        }
    }

    enum class HardwareAccelerationMode(val persistedValue: String) {
        AUTOMATIC("automatic"),
        DISABLED("disabled"),
        FULL("full"),
        DECODING_ONLY("decoding_only");

        companion object {
            fun fromPersisted(value: String?): HardwareAccelerationMode {
                return entries.firstOrNull { it.persistedValue.equals(value, ignoreCase = true) } ?: AUTOMATIC
            }
        }
    }

    fun isCpuCompatible(context: Context): Boolean {
        return runCatching { VLCUtil.hasCompatibleCPU(context.applicationContext) }.getOrDefault(false)
    }

    fun buildLibVlcOptions(
        context: Context,
        audioOutputMode: AudioOutputMode
    ): ArrayList<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val options = ArrayList<String>(24)
        val networkCachingMs = prefs.getInt(KEY_NETWORK_CACHING_MS, 5_000).coerceIn(0, 60_000)
        val preferredResolution = prefs.getInt(KEY_PREFERRED_RESOLUTION, -1)
        val configuredVideoOutput = prefs.getString(KEY_VIDEO_OUTPUT, null)?.trim()?.lowercase()
        val customOptions = prefs.getString(KEY_CUSTOM_OPTIONS, null)

        options += "--audio-time-stretch"
        options += "--avcodec-skiploopfilter"
        options += "1"
        options += "--avcodec-skip-frame"
        options += "0"
        options += "--avcodec-skip-idct"
        options += "0"
        options += "--stats"
        if (networkCachingMs > 0) {
            options += "--network-caching=$networkCachingMs"
            options += "--file-caching=$networkCachingMs"
        }
        options += "--audio-resampler"
        options += "soxr"
        options += "--drop-late-frames"
        options += "--skip-frames"
        options += "--preferred-resolution=$preferredResolution"

        when (configuredVideoOutput) {
            "gles2" -> options += "--vout=gles2,none"
            "android_display" -> options += "--vout=android_display,none"
        }

        // Kept as a hidden debug override; regular selection is still done on the MediaPlayer.
        if (audioOutputMode != AudioOutputMode.DEFAULT) {
            audioOutputMode.libVlcValue?.let { options += "--aout=$it" }
        }

        customOptions
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { options += it }

        return options
    }

    fun applyMediaOptions(
        media: Media,
        mode: HardwareAccelerationMode,
        allowSubtitleAutoload: Boolean
    ) {
        when (mode) {
            HardwareAccelerationMode.AUTOMATIC -> Unit
            HardwareAccelerationMode.DISABLED -> media.setHWDecoderEnabled(false, false)
            HardwareAccelerationMode.FULL -> media.setHWDecoderEnabled(true, true)
            HardwareAccelerationMode.DECODING_ONLY -> {
                media.setHWDecoderEnabled(true, true)
                media.addOption(":no-mediacodec-dr")
                media.addOption(":no-omxil-dr")
            }
        }

        if (!allowSubtitleAutoload) {
            media.addOption(":sub-language=none")
            media.addOption(":no-sub-autodetect-file")
        }
    }

    fun loadPreferredAudioOutput(context: Context): AudioOutputMode {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val persisted = prefs.getString(deviceAudioOutputKey(), null)
            ?: prefs.getString(KEY_AUDIO_OUTPUT_OVERRIDE, null)
        return AudioOutputMode.fromPersisted(persisted)
    }

    fun markAudioOutputWorking(context: Context, mode: AudioOutputMode) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(deviceAudioOutputKey(), mode.persistedValue)
            .apply()
    }

    fun clearWorkingAudioOutput(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(deviceAudioOutputKey())
            .apply()
    }

    fun buildAudioFallbackOrder(initialMode: AudioOutputMode): List<AudioOutputMode> {
        val preferredOrder = listOf(initialMode, AudioOutputMode.DEFAULT, AudioOutputMode.OPENSLES, AudioOutputMode.AUDIOTRACK)
        return preferredOrder.distinct()
    }

    fun loadHardwareAccelerationMode(context: Context): HardwareAccelerationMode {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return HardwareAccelerationMode.fromPersisted(prefs.getString(KEY_HARDWARE_ACCELERATION, null))
    }

    private fun deviceAudioOutputKey(): String {
        return KEY_AUDIO_OUTPUT_WORKING_PREFIX + "${Build.MANUFACTURER}:${Build.MODEL}".lowercase()
    }
}
