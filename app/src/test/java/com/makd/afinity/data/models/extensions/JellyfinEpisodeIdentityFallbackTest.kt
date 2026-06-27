package com.makd.afinity.data.models.extensions

import com.makd.afinity.data.models.media.resolveAfinityEpisodeIdentity
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlayAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinEpisodeIdentityFallbackTest {
    private val episodeId = UUID.fromString("e9055b2d-fed1-5df3-584a-c1e92ce4d2f0")
    private val seriesId = UUID.fromString("023194fb-82a9-7903-9040-88fe62589acd")
    private val seasonId = UUID.fromString("47ba02af-1580-1932-292f-0e4f04cdcfcc")

    @Test
    fun looseEpisodeUsesSeasonIdFromCallerContext() {
        val identity =
            looseEpisodeDto()
                .resolveAfinityEpisodeIdentity(
                    fallbackSeriesId = seriesId,
                    fallbackSeasonId = seasonId,
                )

        assertNotNull(identity)
        assertEquals(seriesId, identity!!.seriesId)
        assertEquals(seasonId, identity.seasonId)
    }

    @Test
    fun looseEpisodeWithoutContextStaysUnmapped() {
        val episode = looseEpisodeDto().resolveAfinityEpisodeIdentity()

        assertNull(episode)
    }

    private fun looseEpisodeDto(): BaseItemDto =
        BaseItemDto(
            id = episodeId,
            name = "Приключения Буратино",
            type = BaseItemKind.EPISODE,
            seriesId = seriesId,
            seasonId = null,
            seriesName = "Фильмы",
            seasonName = "Season Unknown",
            playAccess = PlayAccess.FULL,
        )
}
