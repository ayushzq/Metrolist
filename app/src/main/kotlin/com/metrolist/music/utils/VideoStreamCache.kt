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
import com.metrolist.innertubex.extraction.ContentHints
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelChildren

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
    private const val VIDEO_PREFS = "video_stream_prefs"

    // Clients that answer a video request without a PO token, asked all at once (see raceClients).
    private val RACE_CLIENTS =
        listOf("WEB_EMBEDDED_PLAYER", "TVHTML5_DOWNGRADED", "WEB_KIDS", "ANDROID_VR_1_43_32", "VISIONOS")
    private const val RACE_TIMEOUT_MS = 8_000L
    private const val AUTO_RESOLVE_TIMEOUT_MS = 20_000L
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

    /**
     * Resolves a stream while skipping [excluded] clients, bypassing the cache. Used to race a second
     * client against a stream that is slow to start. Call [store] if the result turns out to win.
     */
    suspend fun resolveExcluding(
        context: Context,
        mediaId: String,
        followCounterpart: Boolean,
        excluded: Set<String>,
    ): Result<Resolved> {
        val appContext = context.applicationContext
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val videoId = if (followCounterpart) counterpartId(appContext, mediaId) ?: mediaId else mediaId
        val failedClients = InnerTubeXPlayer.failedStreamClients(videoId)
        val candidates = RACE_CLIENTS.filter { it !in excluded && it !in failedClients }
        val stream =
            raceClients(appContext, videoId, connectivityManager, candidates)
                ?: return Result.failure(IllegalStateException("No other video client answered"))
        return Result.success(Resolved(videoId, stream, expiryOf(stream.url)))
    }

    /**
     * Asks every client in [candidates] for a stream at the same time, each for exactly that client
     * (one request each, no PO token), and returns the first stream that comes back. Whether a stream
     * actually plays is decided by the player; clients that fail there are blacklisted and skipped.
     */
    private suspend fun raceClients(
        context: Context,
        videoId: String,
        connectivityManager: ConnectivityManager,
        candidates: List<String>,
    ): InnerTubeXPlayer.VideoStreamData? {
        if (candidates.isEmpty()) return null
        val height = maxHeightFor(context)
        return withTimeoutOrNull(RACE_TIMEOUT_MS) {
            coroutineScope {
                val answers = Channel<InnerTubeXPlayer.VideoStreamData?>(candidates.size)
                candidates.forEach { client ->
                    launch {
                        val stream =
                            runCatching {
                                InnerTubeXPlayer.videoStreamForPlayback(
                                    videoId = videoId,
                                    maxVideoHeight = height,
                                    connectivityManager = connectivityManager,
                                    allowHls = true,
                                    contentHints = ContentHints(playbackClientOverrideId = client),
                                )
                            }.getOrNull()?.getOrNull()
                        answers.trySend(stream)
                    }
                }
                var winner: InnerTubeXPlayer.VideoStreamData? = null
                for (i in candidates.indices) {
                    winner = answers.receive()
                    if (winner != null) break
                }
                this.coroutineContext.cancelChildren()
                winner
            }
        }
    }

    /** Makes [resolved] the cached stream for [mediaId], e.g. after it won a race. */
    fun store(
        context: Context,
        mediaId: String,
        followCounterpart: Boolean,
        resolved: Resolved,
    ) {
        val k = key(context.applicationContext, mediaId, followCounterpart)
        synchronized(entries) {
            entries[k] = resolved
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        }
    }

    /**
     * The client whose stream last drew a picture. Asking for exactly that client skips the slow
     * client-by-client search, so after the first success a video starts in about a second.
     */
    private fun preferredClient(context: Context): Pair<String, Boolean>? {
        val prefs = context.getSharedPreferences(VIDEO_PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString("client", null)?.takeIf { it.isNotBlank() } ?: return null
        return name to prefs.getBoolean("hls", false)
    }

    fun rememberWorkingClient(
        context: Context,
        clientName: String,
        isHls: Boolean,
    ) {
        context.applicationContext
            .getSharedPreferences(VIDEO_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("client", clientName)
            .putBoolean("hls", isHls)
            .apply()
    }

    fun forgetWorkingClient(
        context: Context,
        clientName: String,
    ) {
        val prefs = context.applicationContext.getSharedPreferences(VIDEO_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("client", null) == clientName) prefs.edit().remove("client").remove("hls").apply()
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
        // Race single-client requests instead of letting the extractor walk through its client list one
        // by one (that walk can take over a minute: PO token waits and an 8s timeout per client).
        val failedClients = InnerTubeXPlayer.failedStreamClients(videoId)
        val candidates =
            (listOfNotNull(preferredClient(context)?.first) + RACE_CLIENTS)
                .distinct()
                .filter { it !in failedClients }
        val raced = raceClients(context, videoId, connectivityManager, candidates)
        val result =
            if (raced != null) {
                Result.success(raced)
            } else {
                // Nothing answered in time: the normal extraction, but bounded, so it can never hang.
                withTimeoutOrNull(AUTO_RESOLVE_TIMEOUT_MS) {
                    InnerTubeXPlayer.videoStreamForPlayback(
                        videoId = videoId,
                        maxVideoHeight = maxHeightFor(context),
                        connectivityManager = connectivityManager,
                        allowHls = allowHls,
                    )
                } ?: Result.failure(IllegalStateException("Video lookup timed out"))
            }
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
