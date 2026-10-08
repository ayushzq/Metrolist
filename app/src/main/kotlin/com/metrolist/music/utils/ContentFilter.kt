/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.constants.BlockedIdsKey
import com.metrolist.music.constants.BlockedKeywordsKey
import com.metrolist.music.constants.SmartContentFilterKey
import com.metrolist.music.db.entities.Song

/**
 * Keeps unwanted tracks out of home recommendations.
 *
 *  - Blocked ids: songs/artists the user chose "Don't recommend" for. Always applied.
 *  - Keyword filter: matches whole words/phrases in title and artist names. A built-in list of
 *    vulgar Hinglish/Bhojpuri terms plus the user's own words (Settings > Content).
 */
object ContentFilter {
    private const val MAX_BLOCKED_IDS = 2000

    // Matched as whole words/phrases after normalisation, so "lund" will not hit "blunder".
    private val DEFAULT_KEYWORDS =
        listOf(
            "muh me laga",
            "muhme laga",
            "muh me le",
            "muhme le",
            "muh mein le",
            "chusa",
            "chuso",
            "chusna",
            "lund",
            "lauda",
            "loda",
            "choot",
            "chut",
            "gaand",
            "bhosdi",
            "bhosda",
            "bhosadi",
            "madarchod",
            "behenchod",
            "randi",
            "raand",
            "chudai",
            "chodna",
        )

    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    private fun normalize(text: String): String = text.lowercase().replace(NON_WORD, " ").trim()

    class Rules(
        private val keywordFilterEnabled: Boolean,
        keywords: List<String>,
        private val blockedIds: Set<String>,
    ) {
        private val normalizedKeywords = keywords.map(::normalize).filter { it.isNotEmpty() }.distinct()

        fun isBlockedId(id: String?): Boolean = id != null && id in blockedIds

        fun isBlockedText(vararg texts: String?): Boolean {
            if (!keywordFilterEnabled || normalizedKeywords.isEmpty()) return false
            val haystack = " " + normalize(texts.filterNotNull().joinToString(" ")) + " "
            return normalizedKeywords.any { haystack.contains(" $it ") }
        }

        fun isBlocked(item: YTItem): Boolean =
            when (item) {
                is SongItem ->
                    isBlockedId(item.id) || item.artists.any { isBlockedId(it.id) } ||
                        isBlockedText(item.title, item.artists.joinToString(" ") { it.name })
                is AlbumItem ->
                    isBlockedId(item.id) || item.artists.orEmpty().any { isBlockedId(it.id) } ||
                        isBlockedText(item.title, item.artists.orEmpty().joinToString(" ") { it.name })
                is PlaylistItem -> isBlockedId(item.id) || isBlockedText(item.title, item.author?.name)
                is ArtistItem -> isBlockedId(item.id) || isBlockedText(item.title)
                else -> isBlockedId(item.id) || isBlockedText(item.title)
            }

        fun isBlocked(song: Song): Boolean =
            isBlockedId(song.id) || song.artists.any { isBlockedId(it.id) } ||
                isBlockedText(song.song.title, song.artists.joinToString(" ") { it.name })
    }

    /** Reads the current settings. Same blocking style as the other `dataStore.get` calls. */
    fun rules(context: Context): Rules {
        val store = context.dataStore
        val enabled = store.get(SmartContentFilterKey, true)
        val custom =
            store
                .get(BlockedKeywordsKey, "")
                .split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val ids =
            store
                .get(BlockedIdsKey, "")
                .split(',')
                .filter { it.isNotEmpty() }
                .toSet()
        return Rules(enabled, DEFAULT_KEYWORDS + custom, ids)
    }

    suspend fun block(
        context: Context,
        id: String,
    ) {
        context.safeDataStoreEdit { prefs ->
            val current = (prefs[BlockedIdsKey] ?: "").split(',').filter { it.isNotEmpty() }
            prefs[BlockedIdsKey] = (current + id).distinct().takeLast(MAX_BLOCKED_IDS).joinToString(",")
        }
    }
}

fun <T : YTItem> List<T>.filterBlockedItems(rules: ContentFilter.Rules): List<T> = filterNot { rules.isBlocked(it) }

fun List<Song>.filterBlockedSongs(rules: ContentFilter.Rules): List<Song> = filterNot { rules.isBlocked(it) }
