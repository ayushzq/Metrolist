/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import com.metrolist.innertube.YouTube
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
 *  - the service can [prefetch] the current track in the background before the player is opened.
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

    private fun key(
        mediaId: String,
        followCounterpart: Boolean,
    ) = "$mediaId|$followCounterpart"

    /** Short start-up, little data: 360p on metered networks, 480p otherwise. */
    fun defaultMaxHeight(connectivityManager: ConnectivityManager): Int = if (connectivityManager.isActiveNetworkMetered) 360 else 480

    fun peek(
        mediaId: String,
        followCounterpart: Boolean,
    ): Resolved? {
        val k = key(mediaId, followCounterpart)
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
        mediaId: String,
        followCounterpart: Boolean,
    ) {
        synchronized(entries) { entries.remove(key(mediaId, followCounterpart)) }
    }

    suspend fun resolve(
        context: Context,
        mediaId: String,
        followCounterpart: Boolean,
        maxHeight: Int,
        allowHls: Boolean,
    ): Result<Resolved> {
        peek(mediaId, followCounterpart)?.let { return Result.success(it) }
        val k = key(mediaId, followCounterpart)
        val appContext = context.applicationContext
        val deferred =
            inflight.computeIfAbsent(k) {
                scope
                    .async { doResolve(appContext, mediaId, followCounterpart, maxHeight, allowHls) }
                    .also { d -> d.invokeOnCompletion { inflight.remove(k) } }
            }
        return deferred.await()
    }

    /** Fire-and-forget warm-up, e.g. when a new track starts playing. */
    fun prefetch(
        context: Context,
        mediaId: String,
        followCounterpart: Boolean,
    ) {
        if (peek(mediaId, followCounterpart) != null) return
        val appContext = context.applicationContext
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        scope.launch {
            resolve(appContext, mediaId, followCounterpart, defaultMaxHeight(connectivityManager), allowHls = false)
        }
    }

    private suspend fun doResolve(
        context: Context,
        mediaId: String,
        followCounterpart: Boolean,
        maxHeight: Int,
        allowHls: Boolean,
    ): Result<Resolved> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // An audio-only track's own video is just artwork; its music video is the paired counterpart.
        val videoId = if (followCounterpart) counterpartId(context, mediaId) ?: mediaId else mediaId
        val result = InnerTubeXPlayer.videoStreamForPlayback(videoId, maxHeight, connectivityManager, allowHls)
        val stream = result.getOrNull() ?: return Result.failure(result.exceptionOrNull() ?: IllegalStateException("No video stream"))
        val resolved = Resolved(videoId, stream, expiryOf(stream.url))
        synchronized(entries) {
            entries[key(mediaId, followCounterpart)] = resolved
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
