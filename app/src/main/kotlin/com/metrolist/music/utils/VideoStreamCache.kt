/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.VideoQuality
import com.metrolist.music.constants.VideoQualityKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps resolved video streams so the player never repeats the slow part (counterpart lookup +
 * stream extraction) for a track it has already seen:
 *  - resolved stream URLs are cached in memory until shortly before they expire,
 *  - the song -> music-video id pairing is remembered on disk, so it survives app restarts,
 *  - the service can [prefetch] the current and next track before the player is even opened.
 */
object VideoStreamCache {
    class Resolved(
        val videoId: String,
        val stream: InnerTubeXPlayer.VideoStreamData,
        val expiresAtMs: Long,
    )

    private const val MAX_ENTRIES = 12
    private const val PREFS_NAME = "video_counterparts"
    private const val MAX_STORED_PAIRS = 3000
    private const val COUNTERPART_TIMEOUT_MS = 6_000L
    private const val DEFAULT_TTL_MS = 20 * 60_000L
    private const val MAX_TTL_MS = 4 * 3_600_000L
    private const val EXPIRY_MARGIN_MS = 5 * 60_000L
    private val EXPIRE_PARAM = Regex("[?&]expire=(\\d+)")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = LinkedHashMap<String, Resolved>()
    private val inflight = ConcurrentHashMap<String, Deferred<Result<Resolved>>>()

    /** The user's fixed height cap, or 0 when set to Auto. */
    private fun fixedHeight(context: Context): Int {
        val name = context.dataStore.get(VideoQualityKey, VideoQuality.AUTO.name)
        return VideoQuality.values().firstOrNull { it.name == name }?.maxHeight ?: 0
    }

    /**
     * Height to request. A fixed setting wins; in Auto it follows the measured connection speed
     * (the same estimate ExoPlayer keeps for the audio), capped at 480p on metered networks.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun maxHeightFor(context: Context): Int {
        val fixed = fixedHeight(context)
        if (fixed > 0) return fixed
        val appContext = context.applicationContext
        val bitsPerSecond = DefaultBandwidthMeter.getSingletonInstance(appContext).bitrateEstimate
        val bySpeed =
            when {
                bitsPerSecond < 2_500_000L -> 360
                bitsPerSecond < 5_000_000L -> 480
                bitsPerSecond < 10_000_000L -> 720
                else -> 1080
            }
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (connectivityManager.isActiveNetworkMetered) minOf(bySpeed, 480) else bySpeed
    }

    // Auto shares one cache slot so a changing speed estimate never causes a cache miss;
    // a fixed quality gets its own slot.
    private fun key(
        context: Context,
        mediaId: String,
        followCounterpart: Boolean,
    ) = "$mediaId|$followCounterpart|${fixedHeight(context)}"

    fun peek(
        context: Context,
        mediaId: String,
        followCounterpart: Boolean,
    ): Resolved? {
        val k = key(context, mediaId, followCounterpart)
        return synchronized(entries) {
            val entry = entries[k]
            if (entry != null && entry.expiresAtMs <= System.currentTimeMillis()) {
                entries.remove(k)
                null
            } else {
                entry
            }
        }
    }

    fun invalidate(
        context: Context,
        mediaId: String,
        followCounterpart: Boolean,
    ) {
        val k = key(context, mediaId, followCounterpart)
        synchronized(entries) { entries.remove(k) }
    }

    suspend fun resolve(
        context: Context,
        mediaId: String,
        followCounterpart: Boolean,
        allowHls: Boolean,
    ): Result<Resolved> {
        peek(context, mediaId, followCounterpart)?.let { return Result.success(it) }
        val appContext = context.applicationContext
        val k = key(appContext, mediaId, followCounterpart)
        val deferred =
            inflight.computeIfAbsent(k) {
                scope
                    .async { doResolve(appContext, k, mediaId, followCounterpart, allowHls) }
                    .also { d -> d.invokeOnCompletion { inflight.remove(k) } }
            }
        return deferred.await()
    }

    /** Fire-and-forget warm-up, e.g. when a track starts playing or is next in the queue. */
    fun prefetch(
        context: Context,
        mediaId: String,
        followCounterpart: Boolean,
    ) {
        val appContext = context.applicationContext
        if (peek(appContext, mediaId, followCounterpart) != null) return
        scope.launch { resolve(appContext, mediaId, followCounterpart, allowHls = false) }
    }

    private suspend fun doResolve(
        context: Context,
        cacheKey: String,
        mediaId: String,
        followCounterpart: Boolean,
        allowHls: Boolean,
    ): Result<Resolved> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // An audio-only track's own video is just artwork; its music video is the paired counterpart.
        val videoId = if (followCounterpart) counterpartId(context, mediaId) ?: mediaId else mediaId
        val result =
            InnerTubeXPlayer.videoStreamForPlayback(
                videoId = videoId,
                maxVideoHeight = maxHeightFor(context),
                connectivityManager = connectivityManager,
                allowHls = allowHls,
            )
        val stream =
            result.getOrNull()
                ?: return Result.failure(result.exceptionOrNull() ?: IllegalStateException("No video stream"))
        val resolved = Resolved(videoId, stream, expiryOf(stream.url))
        synchronized(entries) {
            entries[cacheKey] = resolved
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        }
        return Result.success(resolved)
    }

    private fun expiryOf(url: String): Long {
        val now = System.currentTimeMillis()
        val expireEpochMs =
            EXPIRE_PARAM
                .find(url)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?.times(1000)
        return if (expireEpochMs != null) minOf(expireEpochMs - EXPIRY_MARGIN_MS, now + MAX_TTL_MS) else now + DEFAULT_TTL_MS
    }

    private suspend fun counterpartId(
        context: Context,
        mediaId: String,
    ): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(mediaId)) return prefs.getString(mediaId, null)?.takeIf { it.isNotEmpty() }

        val result = withTimeoutOrNull(COUNTERPART_TIMEOUT_MS) { YouTube.videoCounterpartId(mediaId) } ?: return null
        result.onSuccess { id ->
            val editor = prefs.edit()
            if (prefs.all.size > MAX_STORED_PAIRS) editor.clear()
            // An empty value records "this track has no counterpart" so it is not looked up again.
            editor.putString(mediaId, id.orEmpty())
            editor.apply()
        }
        return result.getOrNull()
    }
}
