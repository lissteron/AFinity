package com.makd.afinity.ui.item

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonArtworkSourceTest {
    @Test
    fun seasonCardsUseOnlySeasonSpecificArtwork() {
        val source =
            File("src/main/java/com/makd/afinity/ui/item/components/SeriesDetailContent.kt")
                .readText()

        assertTrue(source.contains("season.images.primaryImageUrl"))
        assertTrue(source.contains("season.images.thumbImageUrl"))
        assertTrue(source.contains("season.images.backdropImageUrl"))
        assertFalse(source.contains("showPrimaryImageUrl"))
        assertFalse(source.contains("showPrimaryBlurHash"))
    }
}
