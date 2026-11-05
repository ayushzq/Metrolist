/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.Locale
import javax.inject.Inject

/**
 * One entry of the trending list.
 * [matchedArtist] is set when the song is by an artist the user already listens to,
 * [likedGenre] when it only shares a genre with such songs.
 */
data class TrendingTrack(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val genre: String?,
    val matchedArtist: String?,
    val likedGenre: Boolean,
)

/**
 * "Trending for you": takes Apple Music's free, keyless "most played" chart for the user's country
 * (rss.marketingtools.apple.com) and puts the songs that fit the user's taste first. Taste comes from
 * the user's own most played artists; songs by those artists, and songs in the genres those artists
 * chart in, are ranked above the rest. A tapped entry is looked up on YouTube Music and played.
 */
@HiltViewModel
class TrendingViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
    ) : ViewModel() {
        val tracks = MutableStateFlow<List<TrendingTrack>>(emptyList())
        val isLoading = MutableStateFlow(true)

        init {
            load()
        }

        fun load() {
            viewModelScope.launch(Dispatchers.IO) {
                isLoading.value = true
                val deviceCountry = Locale.getDefault().country.lowercase(Locale.ROOT)
                val chart =
                    listOf(deviceCountry, "in", "us")
                        .filter { it.length == 2 }
                        .distinct()
                        .firstNotNullOfOrNull { fetchChart(it) }
                        .orEmpty()

                val favouriteArtists =
                    runCatching {
                        database
                            .mostPlayedArtists(LocalDateTime.of(2000, 1, 1, 0, 0), limit = 40)
                            .first()
                            .map { it.artist.name }
                            .filter { it.length >= 3 }
                    }.getOrDefault(emptyList())

                tracks.value = personalise(chart, favouriteArtists)
                isLoading.value = false
            }
        }

        private fun personalise(
            chart: List<TrendingTrack>,
            favouriteArtists: List<String>,
        ): List<TrendingTrack> {
            if (chart.isEmpty()) return emptyList()
            fun matchOf(track: TrendingTrack): String? {
                val artistLine = track.artist.lowercase(Locale.ROOT)
                return favouriteArtists.firstOrNull { artistLine.contains(it.lowercase(Locale.ROOT)) }
            }
            val withMatch = chart.map { it.copy(matchedArtist = matchOf(it)) }
            // Genres the user's own artists appear in on the chart describe their taste.
            val likedGenres = withMatch.filter { it.matchedArtist != null }.mapNotNull { it.genre }.toSet()
            return withMatch
                .map { it.copy(likedGenre = it.matchedArtist == null && it.genre != null && it.genre in likedGenres) }
                .withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<TrendingTrack>> {
                        when {
                            it.value.matchedArtist != null -> 2
                            it.value.likedGenre -> 1
                            else -> 0
                        }
                    }.thenBy { it.index },
                ).map { it.value }
                .take(25)
        }

        private fun fetchChart(country: String): List<TrendingTrack>? {
            var connection: HttpURLConnection? = null
            return try {
                connection =
                    (URL("https://rss.marketingtools.apple.com/api/v2/$country/music/most-played/50/songs.json").openConnection() as HttpURLConnection)
                        .apply {
                            connectTimeout = 8_000
                            readTimeout = 8_000
                            requestMethod = "GET"
                        }
                if (connection.responseCode != 200) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val results = JSONObject(body).getJSONObject("feed").getJSONArray("results")
                buildList {
                    for (i in 0 until results.length()) {
                        val entry = results.getJSONObject(i)
                        val title = entry.optString("name")
                        val artist = entry.optString("artistName")
                        if (title.isBlank() || artist.isBlank()) continue
                        add(
                            TrendingTrack(
                                title = title,
                                artist = artist,
                                artworkUrl = entry.optString("artworkUrl100").takeIf { it.isNotBlank() }?.replace("100x100", "400x400"),
                                genre =
                                    entry
                                        .optJSONArray("genres")
                                        ?.optJSONObject(0)
                                        ?.optString("name")
                                        ?.takeIf { it.isNotBlank() && !it.equals("Music", ignoreCase = true) },
                                matchedArtist = null,
                                likedGenre = false,
                            ),
                        )
                    }
                }.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }

        /** Finds the playable YouTube Music version of a chart entry. */
        suspend fun resolve(track: TrendingTrack): SongItem? =
            withContext(Dispatchers.IO) {
                YouTube
                    .search("${track.title} ${track.artist}", YouTube.SearchFilter.FILTER_SONG)
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    ?.firstOrNull()
            }
    }
