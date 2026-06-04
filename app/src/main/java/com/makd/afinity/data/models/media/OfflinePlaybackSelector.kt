package com.makd.afinity.data.models.media

fun AfinityShow.offlinePlaybackEpisode(): AfinityEpisode? =
    seasons
        .sortedBy { season -> season.indexNumber }
        .flatMap { season -> season.episodes.sortedBy { episode -> episode.indexNumber } }
        .selectOfflinePlaybackEpisode()

fun AfinitySeason.offlinePlaybackEpisode(): AfinityEpisode? =
    episodes
        .sortedBy { episode -> episode.indexNumber }
        .selectOfflinePlaybackEpisode()

private fun List<AfinityEpisode>.selectOfflinePlaybackEpisode(): AfinityEpisode? =
    firstOrNull { episode -> !episode.played && episode.playbackPositionTicks == 0L }
        ?: firstOrNull { episode -> !episode.played }
        ?: firstOrNull()
