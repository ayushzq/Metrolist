/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import java.util.Locale

private fun countryName(code: String): String = Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(Locale.getDefault()).ifBlank { code.uppercase(Locale.ROOT) }

/**
 * Trending shelf: a "For you" tab (all chosen countries, ordered by the user's taste) and one tab per
 * chosen country. "Customise" lets the user pick the countries and the artists they want to hear first.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TrendingForYouSection(
    modifier: Modifier = Modifier,
    viewModel: TrendingViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val tracks by viewModel.tracks.collectAsState()
    val countries by viewModel.countries.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val pickedArtists by viewModel.pickedArtists.collectAsState()
    val availableArtists by viewModel.availableArtists.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val scope = rememberCoroutineScope()
    var showCustomise by remember { mutableStateOf(false) }

    if (tracks.isEmpty() && isLoading) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.trending_for_you),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showCustomise = true }) {
                Text(stringResource(R.string.trending_customise))
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "tab_for_you") {
                FilterChip(
                    selected = selectedTab == TrendingViewModel.TAB_FOR_YOU,
                    onClick = { viewModel.selectTab(TrendingViewModel.TAB_FOR_YOU) },
                    label = { Text(stringResource(R.string.trending_tab_for_you)) },
                )
            }
            items(countries, key = { "tab_$it" }) { code ->
                FilterChip(
                    selected = selectedTab == code,
                    onClick = { viewModel.selectTab(code) },
                    label = { Text(countryName(code)) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(tracks, key = { index, track -> "trending_${track.country}_${index}_${track.title}" }) { _, track ->
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
                            track.pickedArtist != null -> stringResource(R.string.trending_reason_picked, track.pickedArtist)
                            track.matchedArtist != null -> stringResource(R.string.trending_reason_artist, track.matchedArtist)
                            track.likedGenre && track.genre != null -> stringResource(R.string.trending_reason_genre, track.genre)
                            else -> countryName(track.country)
                        }
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

    if (showCustomise) {
        var chosenCountries by remember { mutableStateOf(countries.toSet()) }
        var chosenArtists by remember { mutableStateOf(pickedArtists) }
        AlertDialog(
            onDismissRequest = { showCustomise = false },
            title = { Text(stringResource(R.string.trending_customise)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.trending_countries),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { chosenCountries = TrendingViewModel.ALL_COUNTRIES.toSet() }) {
                            Text(stringResource(R.string.trending_select_all))
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TrendingViewModel.ALL_COUNTRIES.forEach { code ->
                            FilterChip(
                                selected = code in chosenCountries,
                                onClick = {
                                    chosenCountries =
                                        if (code in chosenCountries) chosenCountries - code else chosenCountries + code
                                },
                                label = { Text(countryName(code)) },
                            )
                        }
                    }
                    if (availableArtists.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.trending_artists_first),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            availableArtists.forEach { artist ->
                                FilterChip(
                                    selected = artist in chosenArtists,
                                    onClick = {
                                        chosenArtists =
                                            if (artist in chosenArtists) chosenArtists - artist else chosenArtists + artist
                                    },
                                    label = { Text(artist) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Keep the order of the picker list so the country tabs stay stable.
                        viewModel.applyChoices(
                            TrendingViewModel.ALL_COUNTRIES.filter { it in chosenCountries },
                            chosenArtists,
                        )
                        showCustomise = false
                    },
                ) {
                    Text(stringResource(R.string.trending_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomise = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
