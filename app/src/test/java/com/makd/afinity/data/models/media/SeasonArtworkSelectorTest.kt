package com.makd.afinity.data.models.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonArtworkSelectorTest {
    @Test
    fun imageArtworkKeyPrefersImageTag() {
        val key =
            imageArtworkKey(
                imageUrl = "http://server/Items/season-1/Images/Primary?tag=same-primary",
                imageBlurHash = "blur-fallback",
            )

        assertEquals("tag:same-primary", key)
    }

    @Test
    fun imageArtworkKeyUsesBlurHashWhenUrlHasNoTag() {
        val key =
            imageArtworkKey(
                imageUrl = "file:///downloads/season-1/primary.jpg",
                imageBlurHash = "blur-primary",
            )

        assertEquals("blur:blur-primary", key)
    }

    @Test
    fun sharedPrimaryArtworkNeedsRepresentativeWhenNoAlternateExists() {
        assertTrue(
            needsRepresentativeSeasonArtwork(
                primaryArtworkKey = "tag:same-primary",
                sharedPrimaryArtworkKeys = setOf("tag:same-primary"),
                hasSeasonAlternateArtwork = false,
                hasEpisodeArtwork = false,
            )
        )
    }

    @Test
    fun seasonAlternateArtworkAvoidsRepresentativeFetch() {
        assertFalse(
            needsRepresentativeSeasonArtwork(
                primaryArtworkKey = "tag:same-primary",
                sharedPrimaryArtworkKeys = setOf("tag:same-primary"),
                hasSeasonAlternateArtwork = true,
                hasEpisodeArtwork = false,
            )
        )
    }

    @Test
    fun sharedPrimaryArtworkUsesRepresentativeEpisodeInsteadOfDuplicatePrimary() {
        val duplicatePrimary =
            SeasonCardArtwork(
                imageUrl = "http://server/Items/season-1/Images/Primary?tag=same-primary",
                blurHash = "season-blur",
            )
        val representativeEpisode =
            SeasonCardArtwork(
                imageUrl = "http://server/Items/episode-1/Images/Primary?tag=episode-primary",
                blurHash = "episode-blur",
            )

        val artwork =
            selectSeasonCardArtwork(
                primaryImageIsShared = true,
                seasonPrimary = duplicatePrimary,
                seasonThumb = null,
                seasonBackdrop = null,
                episodePrimary = representativeEpisode,
                episodeThumb = null,
                episodeBackdrop = null,
            )

        assertEquals(representativeEpisode, artwork)
    }

    @Test
    fun uniquePrimaryArtworkKeepsSeasonPrimary() {
        val seasonPrimary =
            SeasonCardArtwork(
                imageUrl = "http://server/Items/season-1/Images/Primary?tag=season-primary",
                blurHash = "season-blur",
            )
        val representativeEpisode =
            SeasonCardArtwork(
                imageUrl = "http://server/Items/episode-1/Images/Primary?tag=episode-primary",
                blurHash = "episode-blur",
            )

        val artwork =
            selectSeasonCardArtwork(
                primaryImageIsShared = false,
                seasonPrimary = seasonPrimary,
                seasonThumb = null,
                seasonBackdrop = null,
                episodePrimary = representativeEpisode,
                episodeThumb = null,
                episodeBackdrop = null,
            )

        assertEquals(seasonPrimary, artwork)
    }
}
