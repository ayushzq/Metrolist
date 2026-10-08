package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Badges(
    val musicInlineBadgeRenderer: MusicInlineBadgeRenderer?,
) {
    @Serializable
    data class MusicInlineBadgeRenderer(
        val icon: Icon,
    )
}

/** True when YouTube marks the channel with a verified / official-artist badge. */
fun List<Badges>?.hasVerifiedBadge(): Boolean =
    this?.any {
        val type = it.musicInlineBadgeRenderer?.icon?.iconType
        type != null && (type.contains("VERIFIED") || type.contains("OFFICIAL_ARTIST"))
    } ?: false
