package com.makd.afinity.data.models.extensions

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinModelExtensionsImageContractTest {
    @Test
    fun parentShowImagesUseMatchingParentTags() {
        val source =
            File("src/main/java/com/makd/afinity/data/models/extensions/JellyfinModelExtensions.kt")
                .readText()

        assertTrue(source.contains("val showPrimaryTag = parentPrimaryImageTag ?: seriesPrimaryImageTag"))
        assertTrue(source.contains("val showBackdropTag = parentBackdropImageTags?.firstOrNull()"))
        assertTrue(source.contains("val showThumbTag = parentThumbImageTag ?: seriesThumbImageTag"))
        assertTrue(source.contains("val showLogoTag = parentLogoImageTag"))
        assertTrue(source.contains("imageUri(showBackdropItemId, \"Backdrop/0\", showBackdropTag)"))
        assertTrue(source.contains("imageUri(showThumbItemId, \"Thumb\", showThumbTag)"))
        assertTrue(source.contains("imageUri(showLogoItemId, \"Logo\", showLogoTag)"))
    }

    @Test
    fun seriesPrimaryTagIsNotUsedForBackdropThumbOrLogoFallbacks() {
        val source =
            File("src/main/java/com/makd/afinity/data/models/extensions/JellyfinModelExtensions.kt")
                .readText()

        assertFalse(
            source.contains("seriesPrimaryImageTag)?.let") &&
                source.contains("Images/Thumb")
        )
        assertFalse(
            source.contains("seriesPrimaryImageTag?.let") &&
                source.contains("Images/Backdrop/0")
        )
        assertFalse(
            source.contains("fallbackTag = seriesPrimaryImageTag") &&
                source.contains("Images/Logo")
        )
    }
}
