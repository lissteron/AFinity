package com.makd.afinity.data.models.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class OfflinePlaybackSelectorTest {
    private val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val seasonOneId = UUID.fromString("00000000-0000-0000-0000-000000000101")
    private val seasonTwoId = UUID.fromString("00000000-0000-0000-0000-000000000102")

    @Test
    fun seasonParentPlayPrefersFreshDownloadedEpisodeOverPreviousResume() {
        val resumed = episode(number = 2, seasonId = seasonOneId, progressTicks = 40_000L)
        val fresh = episode(number = 3, seasonId = seasonOneId)
        val season = season(index = 1, seasonId = seasonOneId, episodes = listOf(resumed, fresh))

        assertEquals(fresh.id, season.offlinePlaybackEpisode()?.id)
    }

    @Test
    fun seasonParentPlayFallsBackToResumeWhenNoFreshEpisodeExists() {
        val played = episode(number = 1, seasonId = seasonOneId, played = true)
        val resumed = episode(number = 2, seasonId = seasonOneId, progressTicks = 40_000L)
        val season = season(index = 1, seasonId = seasonOneId, episodes = listOf(played, resumed))

        assertEquals(resumed.id, season.offlinePlaybackEpisode()?.id)
    }

    @Test
    fun showParentPlayUsesSeasonAndEpisodeOrderForFreshDownloadedEpisodes() {
        val laterSeasonFresh = episode(number = 1, seasonId = seasonTwoId)
        val earlierSeasonFresh = episode(number = 4, seasonId = seasonOneId)
        val show =
            show(
                seasons =
                    listOf(
                        season(index = 2, seasonId = seasonTwoId, episodes = listOf(laterSeasonFresh)),
                        season(index = 1, seasonId = seasonOneId, episodes = listOf(earlierSeasonFresh)),
                    )
            )

        assertEquals(earlierSeasonFresh.id, show.offlinePlaybackEpisode()?.id)
    }

    @Test
    fun parentPlayFallsBackToFirstEpisodeWhenEverythingIsPlayed() {
        val first = episode(number = 1, seasonId = seasonOneId, played = true)
        val second = episode(number = 2, seasonId = seasonOneId, played = true)
        val season = season(index = 1, seasonId = seasonOneId, episodes = listOf(first, second))

        assertEquals(first.id, season.offlinePlaybackEpisode()?.id)
    }

    private fun episode(
        number: Int,
        seasonId: UUID,
        played: Boolean = false,
        progressTicks: Long = 0L,
    ): AfinityEpisode =
        AfinityEpisode(
            id = UUID.nameUUIDFromBytes("episode-$seasonId-$number".toByteArray()),
            name = "Episode $number",
            originalTitle = null,
            overview = "",
            indexNumber = number,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = played,
            favorite = false,
            liked = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 100_000L,
            playbackPositionTicks = progressTicks,
            premiereDate = null,
            seriesName = "Show",
            seriesId = seriesId,
            seriesLogo = null,
            seriesLogoBlurHash = null,
            seasonId = seasonId,
            communityRating = null,
            people = emptyList(),
            images = AfinityImages(),
            chapters = emptyList(),
            trickplayInfo = null,
            providerIds = null,
            externalUrls = null,
        )

    private fun season(
        index: Int,
        seasonId: UUID,
        episodes: Collection<AfinityEpisode>,
    ): AfinitySeason =
        AfinitySeason(
            id = seasonId,
            name = "Season $index",
            seriesId = seriesId,
            seriesName = "Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = index,
            episodes = episodes,
            episodeCount = episodes.size,
            productionYear = null,
            premiereDate = null,
            people = emptyList(),
            played = false,
            favorite = false,
            liked = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = episodes.count { !it.played },
            images = AfinityImages(),
            providerIds = null,
            externalUrls = null,
        )

    private fun show(seasons: List<AfinitySeason>): AfinityShow =
        AfinityShow(
            id = seriesId,
            name = "Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = seasons,
            played = false,
            favorite = false,
            liked = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = seasons.sumOf { season -> season.episodes.count { !it.played } },
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "Continuing",
            productionYear = null,
            premiereDate = null,
            dateCreated = null,
            dateLastContentAdded = null,
            endDate = null,
            trailer = null,
            tagline = null,
            seasonCount = seasons.size,
            episodeCount = seasons.sumOf { it.episodes.size },
            images = AfinityImages(),
            providerIds = null,
            externalUrls = null,
        )
}
