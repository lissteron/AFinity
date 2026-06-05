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
    fun sharedPrimaryArtworkPrefersRepresentative() {
        assertTrue(
            prefersRepresentativeSeasonArtwork(
                seasonName = "Season 1",
                primaryArtworkKey = "tag:same-primary",
                sharedPrimaryArtworkKeys = setOf("tag:same-primary"),
            )
        )
    }

    @Test
    fun namedSeasonPrefersRepresentativeEvenWithUniquePrimaryTag() {
        assertTrue(
            prefersRepresentativeSeasonArtwork(
                seasonName = "Трое из Простоквашино",
                primaryArtworkKey = "tag:unique-primary",
                sharedPrimaryArtworkKeys = emptySet(),
            )
        )
    }

    @Test
    fun genericSeasonKeepsSeasonPrimaryWhenPrimaryTagIsUnique() {
        assertFalse(
            prefersRepresentativeSeasonArtwork(
                seasonName = "Сезон 1",
                primaryArtworkKey = "tag:unique-primary",
                sharedPrimaryArtworkKeys = emptySet(),
            )
        )
    }

    @Test
    fun preferredRepresentativeArtworkNeedsFetchWhenNoAlternateExists() {
        assertTrue(
            needsRepresentativeSeasonArtwork(
                prefersRepresentativeArtwork = true,
                hasSeasonAlternateArtwork = false,
                hasEpisodeArtwork = false,
            )
        )
    }

    @Test
    fun seasonAlternateArtworkAvoidsRepresentativeFetch() {
        assertFalse(
            needsRepresentativeSeasonArtwork(
                prefersRepresentativeArtwork = true,
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
                preferRepresentativeArtwork = true,
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
                preferRepresentativeArtwork = false,
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
