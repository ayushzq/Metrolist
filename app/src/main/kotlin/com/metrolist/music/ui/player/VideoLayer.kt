/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.R
import com.metrolist.music.playback.buildVideoMediaSource
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.utils.VideoStreamCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.abs

private const val TAG = "VideoLayer"
private const val SYNC_INTERVAL_MS = 250L
private const val HARD_SEEK_DRIFT_MS = 600L
private const val SOFT_CORRECT_DRIFT_MS = 120L
private const val SOFT_CORRECT_FACTOR = 0.05f
private const val MAX_ATTEMPTS = 3
private const val FIRST_FRAME_TIMEOUT_MS = 10_000L

// A stream from a client that hands out slow or stalled URLs is given up on quickly, so the next
// attempt starts after ~5s instead of ~10s; only the last attempt gets the full time.
private const val EARLY_FRAME_TIMEOUT_MS = 5_000L

// If the first stream has not shown a picture after this long, a second stream from a different
// client is resolved and played in parallel; whichever draws a frame first wins.
private const val HEDGE_DELAY_MS = 2_500L

// How long a prepared video player is kept alive after the app goes to the background, so coming
// back shows the picture again almost instantly instead of resolving and buffering from scratch.
private const val BACKGROUND_KEEP_ALIVE_MS = 60_000L

/**
 * Plays the video track of [mediaId] muted on top of the artwork while the regular audio player
 * stays the single source of truth for position, play/pause and speed.
 *
 * Starting is a race: the first stream starts immediately and, if it has not drawn a picture after
 * [HEDGE_DELAY_MS], a stream from another client is started in parallel. The first one to render a
 * frame wins, the other is released, and the winner is remembered for next time.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoLayer(
    mediaId: String,
    audioPlayer: ExoPlayer,
    cropVideo: Boolean,
    followCounterpart: Boolean,
    modifier: Modifier = Modifier,
    onFrameChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isForeground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val scope = rememberCoroutineScope()
    val videoMaxHeight = remember(mediaId) { VideoStreamCache.maxHeightFor(context) }
    val cached = remember(mediaId, followCounterpart) { VideoStreamCache.peek(context, mediaId, followCounterpart) }

    var stream by remember(mediaId, followCounterpart) { mutableStateOf(cached?.stream) }
    var resolvedVideoId by remember(mediaId, followCounterpart) { mutableStateOf(cached?.videoId) }
    var attempt by remember(mediaId, followCounterpart) { mutableIntStateOf(0) }
    var failureDetail by remember(mediaId, followCounterpart) { mutableStateOf<String?>(null) }
    var retryNonce by remember(mediaId, followCounterpart) { mutableIntStateOf(0) }
    val failed = failureDetail != null

    // Race state; it starts over whenever the primary stream changes.
    var altResolved by remember(mediaId, followCounterpart, stream, retryNonce) {
        mutableStateOf<VideoStreamCache.Resolved?>(null)
    }
    var primaryDead by remember(mediaId, followCounterpart, stream, retryNonce) { mutableStateOf(false) }
    var altDead by remember(mediaId, followCounterpart, stream, retryNonce) { mutableStateOf(false) }

    // 0 = nobody has drawn a frame yet, 1 = the first stream won, 2 = the parallel stream won
    var winner by remember(mediaId, followCounterpart, stream, retryNonce) { mutableIntStateOf(0) }

    // Keeps the prepared players alive for a short while in the background (see BACKGROUND_KEEP_ALIVE_MS).
    val isForegroundState = rememberUpdatedState(isForeground)
    var keepVideo by remember { mutableStateOf(true) }
    LaunchedEffect(isForeground) {
        if (isForeground) {
            keepVideo = true
        } else {
            delay(BACKGROUND_KEEP_ALIVE_MS)
            keepVideo = false
        }
    }

    // Each failure blacklists the client that served the bad stream, so the next attempt uses another
    // one. The last attempt also allows an HLS manifest. 403s additionally refresh the cipher tables.
    fun onStreamFailure(
        detail: String,
        clientName: String?,
        httpCode: Int?,
        extractionFailed: Boolean = false,
    ) {
        val id = resolvedVideoId
        if (id != null && clientName != null) InnerTubeXPlayer.markStreamClientFailed(id, clientName)
        if (httpCode == 403) scope.launch { InnerTubeXPlayer.refreshAfterStreamRejection() }
        VideoStreamCache.invalidate(context, mediaId, followCounterpart)
        if (attempt < MAX_ATTEMPTS - 1) {
            attempt = if (extractionFailed) MAX_ATTEMPTS - 1 else attempt + 1
        } else {
            failureDetail = detail
        }
    }

    fun onSlotError(
        isAlt: Boolean,
        detail: String,
        clientName: String,
        httpCode: Int?,
    ) {
        val id = if (isAlt) altResolved?.videoId else resolvedVideoId
        if (id != null) InnerTubeXPlayer.markStreamClientFailed(id, clientName)
        if (httpCode == 403) scope.launch { InnerTubeXPlayer.refreshAfterStreamRejection() }
        if (isAlt) altDead = true else primaryDead = true
        val altAlive = altResolved != null && !altDead
        val primaryAlive = stream != null && !primaryDead
        // Only when every stream is gone does the normal retry chain (next client) take over.
        if (!altAlive && !primaryAlive) onStreamFailure(detail, null, null)
    }

    fun onSlotFrame(isAlt: Boolean) {
        if (winner != 0) return
        winner = if (isAlt) 2 else 1
        if (isAlt) {
            // The parallel client was faster: remember it and steer clear of the slow one next time.
            altResolved?.let { VideoStreamCache.store(context, mediaId, followCounterpart, it) }
            val id = resolvedVideoId
            val slowClient = stream?.clientName
            if (id != null && slowClient != null) InnerTubeXPlayer.markStreamClientFailed(id, slowClient)
        }
    }

    LaunchedEffect(mediaId, followCounterpart, attempt, retryNonce) {
        if (attempt > 0) stream = null
        VideoStreamCache
            .resolve(
                context = context,
                mediaId = mediaId,
                followCounterpart = followCounterpart,
                allowHls = attempt >= MAX_ATTEMPTS - 1,
            ).onSuccess {
                resolvedVideoId = it.videoId
                stream = it.stream
            }.onFailure {
                Timber.tag(TAG).w(it, "Video stream unavailable for %s", mediaId)
                onStreamFailure(it.message ?: it::class.java.simpleName, null, null, extractionFailed = true)
            }
    }

    // Start the parallel stream if the first one is slow to show a picture.
    LaunchedEffect(stream, retryNonce, keepVideo) {
        val primary = stream ?: return@LaunchedEffect
        if (!keepVideo) return@LaunchedEffect
        delay(HEDGE_DELAY_MS)
        if (winner == 0 && !primaryDead && altResolved == null && failureDetail == null && isForegroundState.value) {
            VideoStreamCache
                .resolveExcluding(context, mediaId, followCounterpart, setOf(primary.clientName))
                .onSuccess { if (it.stream.url != primary.url) altResolved = it }
        }
    }

    var loadingSeconds by remember(mediaId, followCounterpart) { mutableIntStateOf(0) }

    // Shows how long the video has been loading and in which stage, so a slow step is visible.
    LaunchedEffect(mediaId, followCounterpart, winner, failed) {
        loadingSeconds = 0
        while (winner == 0 && !failed) {
            delay(1_000L)
            loadingSeconds += 1
        }
    }

    // Nobody drew a picture in time: give up on this attempt and move on to the next client.
    LaunchedEffect(stream, keepVideo, isForeground, retryNonce) {
        val primary = stream ?: return@LaunchedEffect
        if (!keepVideo || !isForeground) return@LaunchedEffect
        val timeoutMs = if (attempt >= MAX_ATTEMPTS - 1) FIRST_FRAME_TIMEOUT_MS else EARLY_FRAME_TIMEOUT_MS
        val rendered = withTimeoutOrNull(timeoutMs) { snapshotFlow { winner != 0 }.first { it } }
        if (rendered == null) {
            altResolved?.let { alt -> InnerTubeXPlayer.markStreamClientFailed(alt.videoId, alt.stream.clientName) }
            onStreamFailure("No picture after ${timeoutMs / 1000}s via ${primary.clientName}", primary.clientName, null)
        }
    }

    LaunchedEffect(winner) { onFrameChange(winner != 0) }

    Box(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        VideoSlot(
            stream = if (failed || primaryDead || winner == 2) null else stream,
            audioPlayer = audioPlayer,
            videoMaxHeight = videoMaxHeight,
            keepVideo = keepVideo,
            isForegroundState = isForegroundState,
            cropVideo = cropVideo,
            visible = winner != 2,
            onFirstFrame = { onSlotFrame(isAlt = false) },
            onError = { detail, client, code -> onSlotError(false, detail, client, code) },
            modifier = Modifier.fillMaxSize(),
        )
        VideoSlot(
            stream = if (failed || altDead || winner == 1) null else altResolved?.stream,
            audioPlayer = audioPlayer,
            videoMaxHeight = videoMaxHeight,
            keepVideo = keepVideo,
            isForegroundState = isForegroundState,
            cropVideo = cropVideo,
            visible = winner != 1,
            onFirstFrame = { onSlotFrame(isAlt = true) },
            onError = { detail, client, code -> onSlotError(true, detail, client, code) },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = !failed && (stream == null || winner == 0),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    strokeWidth = 3.dp,
                    color = Color.White,
                    modifier = Modifier.size(36.dp),
                )
                if (loadingSeconds >= 2) {
                    Text(
                        text = (if (stream == null) "Finding video " else "Buffering ") + "${loadingSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier =
                            Modifier
                                .padding(top = 8.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = failed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        ) {
            Text(
                text =
                    stringResource(R.string.video_unavailable) +
                        (failureDetail?.let { "\n($it)" } ?: "") +
                        "\n" + stringResource(R.string.video_tap_to_retry),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable {
                            VideoStreamCache.invalidate(context, mediaId, followCounterpart)
                            stream = null
                            resolvedVideoId = null
                            failureDetail = null
                            attempt = 0
                            retryNonce += 1
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * One muted video player bound to one resolved [stream]. It follows [audioPlayer] and reports the
 * first rendered frame and playback errors to its parent; it renders nothing while [stream] is null.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSlot(
    stream: InnerTubeXPlayer.VideoStreamData?,
    audioPlayer: ExoPlayer,
    videoMaxHeight: Int,
    keepVideo: Boolean,
    isForegroundState: State<Boolean>,
    cropVideo: Boolean,
    visible: Boolean,
    onFirstFrame: () -> Unit,
    onError: (detail: String, clientName: String, httpCode: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var videoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var hasFrame by remember { mutableStateOf(false) }
    var videoRatio by remember { mutableFloatStateOf(16f / 9f) }
    val onFirstFrameState = rememberUpdatedState(onFirstFrame)
    val onErrorState = rememberUpdatedState(onError)

    DisposableEffect(stream, keepVideo) {
        val resolved = stream
        if (resolved == null || !keepVideo) {
            videoPlayer = null
            hasFrame = false
            return@DisposableEffect onDispose {}
        }

        val player =
            ExoPlayer
                .Builder(context)
                // Start as soon as ~0.25s is buffered; the video is muted and follows the audio anyway.
                .setLoadControl(
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(2_000, 15_000, 250, 1_000)
                        // Hard cap so a high-bitrate stream can never exhaust the heap.
                        .setTargetBufferBytes(8 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build(),
                ).build()
                .apply {
                    volume = 0f
                    trackSelectionParameters =
                        trackSelectionParameters
                            .buildUpon()
                            .setMaxVideoSize(Int.MAX_VALUE, videoMaxHeight)
                            .build()
                    playWhenReady = false
                    setMediaSource(buildVideoMediaSource(resolved))
                    seekTo(audioPlayer.currentPosition)
                    prepare()
                }
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    hasFrame = true
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Timber.tag(TAG).w(error, "Video playback error (client=%s)", resolved.clientName)
                    val httpCode =
                        generateSequence<Throwable>(error) { it.cause }
                            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()
                            ?.responseCode
                    onErrorState.value(
                        (if (httpCode != null) "HTTP $httpCode" else error.errorCodeName) + " via ${resolved.clientName}",
                        resolved.clientName,
                        httpCode,
                    )
                }
            }
        player.addListener(listener)

        val audioListener =
            object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                        reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                    ) {
                        player.seekTo(newPosition.positionMs)
                    }
                }
            }
        audioPlayer.addListener(audioListener)

        videoPlayer = player
        onDispose {
            audioPlayer.removeListener(audioListener)
            player.removeListener(listener)
            videoPlayer = null
            hasFrame = false
            player.release()
        }
    }

    LaunchedEffect(hasFrame) {
        if (hasFrame) onFirstFrameState.value()
    }

    LaunchedEffect(videoPlayer) {
        val video = videoPlayer ?: return@LaunchedEffect
        while (isActive) {
            val audioReady = audioPlayer.playbackState == Player.STATE_READY
            val baseSpeed = audioPlayer.playbackParameters.speed
            val drift = video.currentPosition - audioPlayer.currentPosition

            val foreground = isForegroundState.value
            video.playWhenReady = foreground && audioPlayer.playWhenReady && audioReady

            // In the background the video stays paused and is not chased along (saves data);
            // the first pass after returning re-syncs it with a hard seek.
            if (foreground && audioReady && abs(drift) > HARD_SEEK_DRIFT_MS) {
                video.seekTo(audioPlayer.currentPosition)
            }

            val targetSpeed =
                when {
                    !video.isPlaying -> baseSpeed
                    drift > SOFT_CORRECT_DRIFT_MS -> baseSpeed * (1f - SOFT_CORRECT_FACTOR)
                    drift < -SOFT_CORRECT_DRIFT_MS -> baseSpeed * (1f + SOFT_CORRECT_FACTOR)
                    else -> baseSpeed
                }
            if (video.playbackParameters.speed != targetSpeed) {
                video.setPlaybackSpeed(targetSpeed)
            }
            delay(SYNC_INTERVAL_MS)
        }
    }

    val videoAlpha by animateFloatAsState(
        targetValue = if (hasFrame && visible) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "videoFadeIn",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val player = videoPlayer
        if (player != null) {
            val textureView = remember(player) { TextureView(context) }
            DisposableEffect(player, textureView) {
                player.setVideoTextureView(textureView)
                onDispose { player.clearVideoTextureView(textureView) }
            }
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val boxWidth: Float = maxWidth.value
                val boxHeight: Float = maxHeight.value
                val ratio: Float = videoRatio
                val boxRatio: Float = boxWidth / boxHeight
                val fillWidth: Boolean = if (cropVideo) ratio < boxRatio else ratio >= boxRatio
                val width: Float = if (fillWidth) boxWidth else boxHeight * ratio
                val height: Float = if (fillWidth) boxWidth / ratio else boxHeight
                AndroidView(
                    factory = { textureView },
                    modifier =
                        Modifier
                            .requiredSize(width.dp, height.dp)
                            .alpha(videoAlpha),
                )
            }
        }
    }
}
