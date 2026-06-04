package com.makd.afinity.data.repository.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedArtworkDedupContractTest {
    @Test
    fun episodeDownloadsDoNotPersistPerEpisodeSeriesArtworkCopies() {
        val source =
            File("src/main/java/com/makd/afinity/data/repository/download/MediaDownloadTransferRunner.kt")
                .readText()

        assertFalse(source.contains("saveImage(images.showPrimary"))
        assertFalse(source.contains("saveImage(images.showBackdrop"))
        assertFalse(source.contains("saveImage(images.showLogo"))
        assertTrue(source.contains("showPrimary = sharedSeriesImages?.primary ?: images.showPrimary"))
        assertTrue(source.contains("deleteRedundantEpisodeSeriesImages(imagesDir)"))
    }
}
