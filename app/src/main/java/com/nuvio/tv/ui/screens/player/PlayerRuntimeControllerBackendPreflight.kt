package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val PREFLIGHT_SNIFF_BYTES = 256 * 1024
private const val FALLBACK_PREFS_NAME = "playback_backend_fallbacks"
private const val PREF_KEY_MEDIA3_UNSUPPORTED_CLASSES = "media3_unsupported_classes"
private const val PREF_KEY_MEDIA3_UNSUPPORTED_URLS = "media3_unsupported_urls"
private const val PREF_KEY_MEDIA3_UNSUPPORTED_SIGNATURES = "media3_unsupported_signatures"
private const val PREF_KEY_MEDIA3_UNSUPPORTED_CAPABILITIES = "media3_unsupported_capabilities"

private enum class BackendFallbackClass(val storageKey: String) {
    MATROSKA_VC1("matroska-vc1"),
    MATROSKA_MPEG2("matroska-mpeg2");

    companion object {
        fun fromStorageKey(value: String?): BackendFallbackClass? =
            entries.firstOrNull { it.storageKey == value }
    }
}

internal suspend fun PlayerRuntimeController.shouldStartWithLibVlcPreflight(
    url: String,
    headers: Map<String, String>,
    media3UsesProxy: Boolean
): Boolean {
    if (!media3UsesProxy) return false
    val normalized = url.lowercase()
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
    if (normalized.contains(".m3u8") || normalized.contains(".mpd")) return false
    if (wasUrlPreviouslyMarkedMedia3Unsupported(url)) {
        Log.w(
            PlayerRuntimeController.TAG,
            "UNSUPPORTED_PREFLIGHT: using persisted blocked stream key host=${Uri.parse(url).host ?: "unknown"}"
        )
        return true
    }

    val result = withContext(Dispatchers.IO) {
        runCatching { sniffUnsupportedMatroskaCodec(url, headers) }
            .onFailure { error ->
                Log.w(
                    PlayerRuntimeController.TAG,
                    "UNSUPPORTED_PREFLIGHT: sniff failed host=${Uri.parse(url).host ?: "unknown"}",
                    error
                )
            }
            .getOrNull()
    } ?: return false

    if (!result.shouldUseLibVlc) return false
    result.fallbackClass?.let { rememberMedia3UnsupportedClass(it) }
    rememberCurrentDecoderCapabilityAsUnsupported(result.capabilityKey)
    rememberMedia3UnsupportedUrl(url)

    Log.w(
        PlayerRuntimeController.TAG,
        "UNSUPPORTED_PREFLIGHT: blocking Media3 startup reason=${result.reason} " +
            "host=${Uri.parse(url).host ?: "unknown"}"
    )
    return true
}

private data class BackendPreflightResult(
    val shouldUseLibVlc: Boolean,
    val reason: String,
    val fallbackClass: BackendFallbackClass? = null,
    val capabilityKey: String? = null
)

private fun sniffUnsupportedMatroskaCodec(
    url: String,
    headers: Map<String, String>
): BackendPreflightResult {
    val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val request = Request.Builder()
        .url(url)
        .header("Range", "bytes=0-${PREFLIGHT_SNIFF_BYTES - 1}")
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        .apply {
            headers.forEach { (name, value) ->
                if (!name.equals("Range", ignoreCase = true)) {
                    header(name, value)
                }
            }
        }
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful && response.code !in setOf(200, 206)) {
            return BackendPreflightResult(false, "http-${response.code}")
        }
        val body = response.body ?: return BackendPreflightResult(false, "empty-body")
        val bytes = body.bytes()
        if (bytes.size < 4) return BackendPreflightResult(false, "insufficient-bytes")
        val isMatroska =
            bytes[0] == 0x1A.toByte() &&
                bytes[1] == 0x45.toByte() &&
                bytes[2] == 0xDF.toByte() &&
                bytes[3] == 0xA3.toByte()
        if (!isMatroska) return BackendPreflightResult(false, "not-matroska")

        val headerText = String(bytes, StandardCharsets.ISO_8859_1)
        val hasVc1Marker =
            headerText.contains("V_MS/VFW/FOURCC") &&
                (
                    headerText.contains("WVC1") ||
                        headerText.contains("WMV3") ||
                        headerText.contains("VC-1")
                    )

        val hasMpeg2Marker =
            headerText.contains("V_MPEG2") ||
                headerText.contains("MPEG-2", ignoreCase = true)

        return when {
            hasVc1Marker -> BackendPreflightResult(
                shouldUseLibVlc = true,
                reason = "matroska-vc1",
                fallbackClass = BackendFallbackClass.MATROSKA_VC1,
                capabilityKey = "video/wvc1|vc1"
            )
            hasMpeg2Marker -> BackendPreflightResult(
                shouldUseLibVlc = true,
                reason = "matroska-mpeg2",
                fallbackClass = BackendFallbackClass.MATROSKA_MPEG2,
                capabilityKey = "video/mpeg2|mpeg2"
            )
            else -> BackendPreflightResult(false, "matroska-supported-or-unknown")
        }
    }
}

private fun PlayerRuntimeController.rememberMedia3UnsupportedClass(fallbackClass: BackendFallbackClass) {
    val prefs = context.getSharedPreferences(FALLBACK_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val existing = prefs.getStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_CLASSES, emptySet()).orEmpty().toMutableSet()
    if (existing.add(fallbackClass.storageKey)) {
        prefs.edit().putStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_CLASSES, existing).apply()
        Log.i(
            PlayerRuntimeController.TAG,
            "UNSUPPORTED_PREFLIGHT: persisted Media3-unsupported class=${fallbackClass.storageKey}"
        )
    }
}

internal fun PlayerRuntimeController.rememberCurrentStreamAsMedia3Unsupported() {
    rememberMedia3UnsupportedUrl(currentStreamUrl)
}

internal fun PlayerRuntimeController.rememberCurrentDecoderSignatureAsUnsupported(signature: String?) {
    val normalized = signature?.takeIf { it.isNotBlank() } ?: return
    val prefs = context.getSharedPreferences(FALLBACK_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val existing = prefs.getStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_SIGNATURES, emptySet()).orEmpty().toMutableSet()
    if (existing.add(normalized)) {
        val trimmed = if (existing.size > 128) {
            existing.toList().takeLast(128).toSet()
        } else {
            existing
        }
        prefs.edit().putStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_SIGNATURES, trimmed).apply()
        Log.i(PlayerRuntimeController.TAG, "UNSUPPORTED_PREFLIGHT: persisted decoder signature=$normalized")
    }
}

internal fun PlayerRuntimeController.rememberCurrentDecoderCapabilityAsUnsupported(capabilityKey: String?) {
    val normalized = capabilityKey?.takeIf { it.isNotBlank() } ?: return
    val prefs = context.getSharedPreferences(FALLBACK_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val existing = prefs.getStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_CAPABILITIES, emptySet()).orEmpty().toMutableSet()
    if (existing.add(normalized)) {
        val trimmed = if (existing.size > 128) {
            existing.toList().takeLast(128).toSet()
        } else {
            existing
        }
        prefs.edit().putStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_CAPABILITIES, trimmed).apply()
        Log.i(PlayerRuntimeController.TAG, "UNSUPPORTED_PREFLIGHT: persisted decoder capability=$normalized")
    }
}

internal fun PlayerRuntimeController.hasRememberedMedia3UnsupportedCapability(capabilityKey: String?): Boolean {
    val normalized = capabilityKey?.takeIf { it.isNotBlank() } ?: return false
    val prefs = context.getSharedPreferences(FALLBACK_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    return prefs.getStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_CAPABILITIES, emptySet()).orEmpty().contains(normalized)
}

internal fun PlayerRuntimeController.hasRememberedMedia3UnsupportedSignature(signature: String?): Boolean {
    val normalized = signature?.takeIf { it.isNotBlank() } ?: return false
    val prefs = context.getSharedPreferences(FALLBACK_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    return prefs.getStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_SIGNATURES, emptySet()).orEmpty().contains(normalized)
}

private fun PlayerRuntimeController.rememberMedia3UnsupportedUrl(url: String) {
    val key = url.fallbackUrlKey() ?: return
    val prefs = context.getSharedPreferences(FALLBACK_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val existing = prefs.getStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_URLS, emptySet()).orEmpty().toMutableSet()
    if (existing.add(key)) {
        val trimmed = if (existing.size > 128) {
            existing.toList().takeLast(128).toSet()
        } else {
            existing
        }
        prefs.edit().putStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_URLS, trimmed).apply()
        Log.i(PlayerRuntimeController.TAG, "UNSUPPORTED_PREFLIGHT: persisted stream fallback key=$key")
    }
}

private fun PlayerRuntimeController.wasUrlPreviouslyMarkedMedia3Unsupported(url: String): Boolean {
    val key = url.fallbackUrlKey() ?: return false
    val prefs = context.getSharedPreferences(FALLBACK_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    return prefs.getStringSet(PREF_KEY_MEDIA3_UNSUPPORTED_URLS, emptySet()).orEmpty().contains(key)
}

private fun String.fallbackUrlKey(): String? {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.lowercase().orEmpty()
    val port = uri.port.takeIf { it > 0 }?.toString().orEmpty()
    val path = uri.path.orEmpty()
    if (scheme.isBlank() || host.isBlank()) return null
    return "$scheme|$host|$port|$path"
}
