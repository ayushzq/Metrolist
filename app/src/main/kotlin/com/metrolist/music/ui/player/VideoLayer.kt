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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.innertube.YouTube
import com.metrolist.music.R
import com.metrolist.music.playback.buildVideoMediaSource
import com.metrolist.music.utils.InnerTubeXPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs

private const val TAG = "VideoLayer"
private const val SYNC_INTERVAL_MS = 250L
private const val HARD_SEEK_DRIFT_MS = 600L
private const val SOFT_CORRECT_DRIFT_MS = 120L
private const val SOFT_CORRECT_FACTOR = 0.05f
private const val MAX_HEIGHT_METERED = 360
private const val MAX_HEIGHT_UNMETERED = 720

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

    var stream by remember(mediaId, followCounterpart) { mutableStateOf<InnerTubeXPlayer.VideoStreamData?>(null) }
    var failed by remember(mediaId, followCounterpart) { mutableStateOf(false) }

    LaunchedEffect(mediaId, followCounterpart) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val maxHeight = if (connectivityManager.isActiveNetworkMetered) MAX_HEIGHT_METERED else MAX_HEIGHT_UNMETERED
        // An audio-only track's own video is just artwork; its music video is the paired counterpart.
        val videoId =
            if (followCounterpart) {
                withContext(Dispatchers.IO) { YouTube.videoCounterpartId(mediaId).getOrNull() } ?: mediaId
            } else {
                mediaId
            }
        withContext(Dispatchers.IO) {
            InnerTubeXPlayer.videoStreamForPlayback(videoId, maxHeight, connectivityManager)
        }.onSuccess { stream = it }
            .onFailure {
                Timber.tag(TAG).w(it, "Video stream unavailable for %s", mediaId)
                failed = true
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
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
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
                    Timber.tag(TAG).w(error, "Video playback error for %s", mediaId)
                    failed = true
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
                val boxRatio = maxWidth / maxHeight
                val fillWidth = if (cropVideo) videoRatio < boxRatio else videoRatio >= boxRatio
                val width = if (fillWidth) maxWidth else maxHeight * videoRatio
                val height = if (fillWidth) maxWidth / videoRatio else maxHeight
                AndroidView(
                    factory = { textureView },
                    modifier =
                        Modifier
                            .requiredSize(width, height)
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
                text = stringResource(R.string.video_unavailable),
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
