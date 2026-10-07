package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistPanelVideoRenderer(
    val title: Runs?,
    val lengthText: Runs?,
    val longBylineText: Runs?,
    val shortBylineText: Runs?,
    val badges: List<Badges>?,
    val videoId: String?,
    val playlistSetVideoId: String?,
    val selected: Boolean,
    val thumbnail: Thumbnails,
    val unplayableText: Runs?,
    val menu: Menu?,
    val navigationEndpoint: NavigationEndpoint,
    val counterpart: List<Counterpart>? = null,
) {
    /** The other half of a song/video pair: the video for an audio track and vice versa. */
    @Serializable
    data class Counterpart(
        val counterpartRenderer: CounterpartRenderer? = null,
    )

    @Serializable
    data class CounterpartRenderer(
        val playlistPanelVideoRenderer: CounterpartVideo? = null,
    )

    @Serializable
    data class CounterpartVideo(
        val videoId: String? = null,
    )
}
