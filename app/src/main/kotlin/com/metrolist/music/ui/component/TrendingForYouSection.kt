/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.viewmodels.TrendingViewModel
import kotlinx.coroutines.launch

/** Horizontal shelf of trending songs, ordered by how well they fit what the user listens to. */
@Composable
fun TrendingForYouSection(
    modifier: Modifier = Modifier,
    viewModel: TrendingViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val tracks by viewModel.tracks.collectAsState()
    if (tracks.isEmpty()) return
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.trending_for_you),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(tracks, key = { index, track -> "trending_${index}_${track.title}" }) { _, track ->
                Column(
                    modifier =
                        Modifier
                            .width(150.dp)
                            .clickable {
                                scope.launch {
                                    val song = viewModel.resolve(track) ?: return@launch
                                    playerConnection.playQueue(
                                        YouTubeQueue(
                                            WatchEndpoint(videoId = song.id),
                                            song.toMediaMetadata(),
                                        ),
                                    )
                                }
                            },
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(150.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(150.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val reason =
                        when {
                            track.matchedArtist != null -> stringResource(R.string.trending_reason_artist, track.matchedArtist)
                            track.likedGenre && track.genre != null -> stringResource(R.string.trending_reason_genre, track.genre)
                            else -> null
                        }
                    if (reason != null) {
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
