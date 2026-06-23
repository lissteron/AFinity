package com.makd.afinity.navigation

import com.makd.afinity.data.models.media.AfinityEpisode
import com.makd.afinity.data.models.media.AfinityImages
import com.makd.afinity.data.models.media.AfinitySeason
import com.makd.afinity.data.models.media.AfinityShow
import com.makd.afinity.data.models.media.AfinitySource
import com.makd.afinity.data.models.media.AfinitySourceType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeItemNavigationPolicyTest {
    private val seriesId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val seasonId = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val episodeId = UUID.fromString("30000000-0000-0000-0000-000000000001")

    @Test
    fun localDownloadedShowOpensAsContainerInsteadOfRemoteDetail() {
        val target = homeItemNavigationTarget(show(status = "Local", sourceType = AfinitySourceType.LOCAL))

        assertEquals(HomeItemNavigationTarget.Container(seriesId.toString(), "Local Show"), target)
    }

    @Test
    fun remoteShowStillOpensDetail() {
        val target =
            homeItemNavigationTarget(show(status = "Continuing", sourceType = AfinitySourceType.REMOTE))

        assertTrue(target is HomeItemNavigationTarget.Detail)
    }

    @Test
    fun localSeasonOpensAsContainerForEpisodeList() {
        val target = homeItemNavigationTarget(season(sourceType = AfinitySourceType.LOCAL))

        assertEquals(HomeItemNavigationTarget.Container(seasonId.toString(), "Season 1"), target)
    }

    private fun show(status: String, sourceType: AfinitySourceType): AfinityShow =
        AfinityShow(
            id = seriesId,
            name = "Local Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = listOf(season(sourceType)),
            played = false,
            favorite = false,
            liked = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = 1,
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = status,
            productionYear = null,
            premiereDate = null,
            dateCreated = null,
            dateLastContentAdded = null,
            endDate = null,
            trailer = null,
            tagline = null,
            seasonCount = 1,
            episodeCount = 1,
            images = AfinityImages(),
            providerIds = null,
            externalUrls = null,
        )

    private fun season(sourceType: AfinitySourceType): AfinitySeason =
        AfinitySeason(
            id = seasonId,
            name = "Season 1",
            seriesId = seriesId,
            seriesName = "Local Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = 1,
            episodes = listOf(episode(sourceType)),
            episodeCount = 1,
            productionYear = null,
            premiereDate = null,
            people = emptyList(),
            played = false,
            favorite = false,
            liked = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = 1,
            images = AfinityImages(),
            providerIds = null,
            externalUrls = null,
        )

    private fun episode(sourceType: AfinitySourceType): AfinityEpisode =
        AfinityEpisode(
            id = episodeId,
            name = "Episode",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources =
                listOf(
                    AfinitySource(
                        id = "source",
                        name = "source",
                        type = sourceType,
                        path = "/tmp/video.mkv",
                        size = 1L,
                        mediaStreams = emptyList(),
                    )
                ),
            played = false,
            favorite = false,
            liked = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 1L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesName = "Local Show",
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
}
