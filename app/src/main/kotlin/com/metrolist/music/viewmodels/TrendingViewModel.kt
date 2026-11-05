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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * One entry of a country chart.
 * [matchedArtist] is an artist the user listens to, [pickedArtist] one the user chose to hear first,
 * [likedGenre] a song that only shares a genre with such songs.
 */
data class TrendingTrack(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val genre: String?,
    val country: String,
    val rank: Int,
    val matchedArtist: String? = null,
    val pickedArtist: String? = null,
    val likedGenre: Boolean = false,
)

/**
 * Trending songs from Apple Music's free, keyless "most played" charts (rss.marketingtools.apple.com),
 * for every country the user picked. The shelf has a "For you" tab that blends all picked countries and
 * ranks songs by the user's taste (artists they play, artists they chose, matching genres), plus one tab
 * per country. A tapped entry is looked up on YouTube Music and played.
 */
@HiltViewModel
class TrendingViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
    ) : ViewModel() {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val countries = MutableStateFlow(loadCountries())
        val pickedArtists = MutableStateFlow(prefs.getStringSet(KEY_ARTISTS, emptySet()).orEmpty().toSet())
        val selectedTab = MutableStateFlow(prefs.getString(KEY_TAB, TAB_FOR_YOU) ?: TAB_FOR_YOU)
        val tracks = MutableStateFlow<List<TrendingTrack>>(emptyList())
        val availableArtists = MutableStateFlow<List<String>>(emptyList())
        val isLoading = MutableStateFlow(true)

        private var charts: Map<String, List<TrendingTrack>> = emptyMap()
        private var favouriteArtists: List<String> = emptyList()

        init {
            load()
        }

        private fun loadCountries(): List<String> {
            val saved = prefs.getString(KEY_COUNTRIES, null)?.split(',')?.filter { it.length == 2 }
            if (!saved.isNullOrEmpty()) return saved
            val device = Locale.getDefault().country.lowercase(Locale.ROOT)
            return listOf(device, "in").filter { it.length == 2 }.distinct()
        }

        fun load() {
            viewModelScope.launch(Dispatchers.IO) {
                isLoading.value = true
                val selected = countries.value
                charts =
                    selected
                        .map { cc -> async { cc to fetchChart(cc) } }
                        .awaitAll()
                        .mapNotNull { (cc, chart) -> chart?.let { cc to it } }
                        .toMap()
                favouriteArtists =
                    runCatching {
                        database
                            .mostPlayedArtists(LocalDateTime.of(2000, 1, 1, 0, 0), limit = 40)
                            .first()
                            .map { it.artist.name }
                            .filter { it.length >= 3 }
                    }.getOrDefault(emptyList())
                availableArtists.value =
                    charts.values
                        .flatten()
                        .flatMap { splitArtists(it.artist) }
                        .groupingBy { it }
                        .eachCount()
                        .entries
                        .sortedByDescending { it.value }
                        .map { it.key }
                        .take(40)
                // A saved country tab that is no longer selected falls back to "For you".
                if (selectedTab.value != TAB_FOR_YOU && selectedTab.value !in charts) selectedTab.value = TAB_FOR_YOU
                rebuild()
                isLoading.value = false
            }
        }

        fun selectTab(tab: String) {
            selectedTab.value = tab
            prefs.edit().putString(KEY_TAB, tab).apply()
            rebuild()
        }

        /** Saves the chosen countries and artists, then reloads the charts. */
        fun applyChoices(
            newCountries: List<String>,
            newArtists: Set<String>,
        ) {
            val picked = newCountries.filter { it.length == 2 }.distinct().ifEmpty { listOf("in") }
            countries.value = picked
            pickedArtists.value = newArtists
            prefs
                .edit()
                .putString(KEY_COUNTRIES, picked.joinToString(","))
                .putStringSet(KEY_ARTISTS, newArtists)
                .apply()
            load()
        }

        private fun rebuild() {
            val tab = selectedTab.value
            val picked = pickedArtists.value
            val all = charts.values.flatten()

            fun artistMatch(
                track: TrendingTrack,
                names: Collection<String>,
            ): String? {
                val line = track.artist.lowercase(Locale.ROOT)
                return names.firstOrNull { line.contains(it.lowercase(Locale.ROOT)) }
            }
            val withMatches =
                all.map {
                    it.copy(matchedArtist = artistMatch(it, favouriteArtists), pickedArtist = artistMatch(it, picked))
                }
            // Genres of the songs by artists the user likes describe their taste.
            val likedGenres =
                withMatches
                    .filter { it.matchedArtist != null || it.pickedArtist != null }
                    .mapNotNull { it.genre }
                    .toSet()
            val scored =
                withMatches.map {
                    it.copy(likedGenre = it.matchedArtist == null && it.pickedArtist == null && it.genre != null && it.genre in likedGenres)
                }

            tracks.value =
                if (tab == TAB_FOR_YOU) {
                    // Blend every picked country: best score first, then the best chart rank.
                    scored
                        .distinctBy { "${it.title.lowercase(Locale.ROOT)}|${it.artist.lowercase(Locale.ROOT)}" }
                        .sortedWith(
                            compareByDescending<TrendingTrack> {
                                when {
                                    it.pickedArtist != null -> 3
                                    it.matchedArtist != null -> 2
                                    it.likedGenre -> 1
                                    else -> 0
                                }
                            }.thenBy { it.rank },
                        ).take(30)
                } else {
                    // A country tab is that country's chart, in chart order.
                    scored.filter { it.country == tab }.sortedBy { it.rank }
                }
        }

        private fun splitArtists(line: String): List<String> =
            line
                .split(",", "&", " feat. ", " ft. ", " x ", ";")
                .map { it.trim() }
                .filter { it.length >= 3 }

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
                                country = country,
                                rank = i + 1,
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

        companion object {
            const val TAB_FOR_YOU = "for_you"
            private const val PREFS = "trending_prefs"
            private const val KEY_COUNTRIES = "countries"
            private const val KEY_ARTISTS = "artists"
            private const val KEY_TAB = "tab"

            /** Countries offered in the picker (Apple Music storefront codes). */
            val ALL_COUNTRIES =
                listOf(
                    "in", "us", "gb", "pk", "bd", "np", "lk", "ae", "sa", "ca", "au", "nz", "sg", "my",
                    "id", "ph", "jp", "kr", "br", "mx", "es", "fr", "de", "it", "tr", "ng", "za", "eg",
                )
        }
    }
