/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

/**
 * Plays the video track of [mediaId] muted on top of the artwork while the regular audio player
 * stays the single source of truth for position, play/pause and speed. Leaving the foreground
 * releases the video player, so background playback is audio-only and costs nothing extra.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoLayer(
    mediaId: String,
    audioPlayer: ExoPlayer,
    cropVideo: Boolean,
    followCounterpart: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isForeground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val scope = rememberCoroutineScope()
    val connectivityManager = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    val videoMaxHeight = VideoStreamCache.defaultMaxHeight(connectivityManager)
    val cached = remember(mediaId, followCounterpart) { VideoStreamCache.peek(mediaId, followCounterpart) }

    var stream by remember(mediaId, followCounterpart) { mutableStateOf(cached?.stream) }
    var resolvedVideoId by remember(mediaId, followCounterpart) { mutableStateOf(cached?.videoId) }
    var attempt by remember(mediaId, followCounterpart) { mutableIntStateOf(0) }
    var failureDetail by remember(mediaId, followCounterpart) { mutableStateOf<String?>(null) }
    val failed = failureDetail != null

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
        VideoStreamCache.invalidate(mediaId, followCounterpart)
        if (attempt < MAX_ATTEMPTS - 1) {
            attempt = if (extractionFailed) MAX_ATTEMPTS - 1 else attempt + 1
        } else {
            failureDetail = detail
        }
    }

    LaunchedEffect(mediaId, followCounterpart, attempt) {
        if (attempt > 0) stream = null
        VideoStreamCache
            .resolve(
                context = context,
                mediaId = mediaId,
                followCounterpart = followCounterpart,
                maxHeight = videoMaxHeight,
                allowHls = attempt >= MAX_ATTEMPTS - 1,
            ).onSuccess {
                resolvedVideoId = it.videoId
                stream = it.stream
            }.onFailure {
                Timber.tag(TAG).w(it, "Video stream unavailable for %s", mediaId)
                onStreamFailure(it.message ?: it::class.java.simpleName, null, null, extractionFailed = true)
            }
    }

    var videoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var hasFrame by remember { mutableStateOf(false) }
    var videoRatio by remember { mutableFloatStateOf(16f / 9f) }

    DisposableEffect(stream, isForeground) {
        val resolved = stream
        if (resolved == null || !isForeground) {
            videoPlayer = null
            hasFrame = false
            return@DisposableEffect onDispose {}
        }

        val player =
            ExoPlayer
                .Builder(context)
                // Start as soon as ~0.5s is buffered; the video is muted and follows the audio anyway.
                .setLoadControl(
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(2_000, 15_000, 500, 1_500)
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
                    Timber.tag(TAG).w(error, "Video playback error for %s (client=%s)", mediaId, resolved.clientName)
                    val httpCode =
                        generateSequence<Throwable>(error) { it.cause }
                            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()
                            ?.responseCode
                    onStreamFailure(
                        detail = (if (httpCode != null) "HTTP $httpCode" else error.errorCodeName) + " via ${resolved.clientName}",
                        clientName = resolved.clientName,
                        httpCode = httpCode,
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

    LaunchedEffect(videoPlayer) {
        if (videoPlayer == null) return@LaunchedEffect
        val rendered = withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) { snapshotFlow { hasFrame }.first { it } }
        if (rendered == null) {
            onStreamFailure("No picture after ${FIRST_FRAME_TIMEOUT_MS / 1000}s via ${stream?.clientName}", stream?.clientName, null)
        }
    }

    LaunchedEffect(videoPlayer) {
        val video = videoPlayer ?: return@LaunchedEffect
        while (isActive) {
            val audioReady = audioPlayer.playbackState == Player.STATE_READY
            val baseSpeed = audioPlayer.playbackParameters.speed
            val drift = video.currentPosition - audioPlayer.currentPosition

            video.playWhenReady = audioPlayer.playWhenReady && audioReady

            if (audioReady && abs(drift) > HARD_SEEK_DRIFT_MS) {
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

    Box(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val player = videoPlayer
        if (player != null && !failed) {
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
                            .alpha(if (hasFrame) 1f else 0f),
                )
            }
        }

        AnimatedVisibility(
            visible = !failed && (stream == null || (videoPlayer != null && !hasFrame)),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                color = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }

        AnimatedVisibility(
            visible = failed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        ) {
            Text(
                text = stringResource(R.string.video_unavailable) + (failureDetail?.let { "\n($it)" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier =
                    Modifier
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
