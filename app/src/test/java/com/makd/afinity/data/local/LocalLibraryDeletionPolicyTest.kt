package com.makd.afinity.data.local

import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.models.download.DownloadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import java.util.UUID

class LocalLibraryDeletionPolicyTest {
    private val policy = LocalLibraryDeletionPolicy()

    @Test
    fun cancelQueuedDownloadDoesNotDeletePhysicalMedia() {
        val decision = policy.cancelDownload(download(DownloadStatus.QUEUED))

        assertTrue(decision.deleteQueueRow)
        assertFalse(decision.cancelActiveTransfer)
        assertFalse(decision.deletePhysicalMedia)
        assertFalse(decision.deleteLocalLibraryIndex)
    }

    @Test
    fun removeDownloadHistoryDoesNotDeleteCompletedMedia() {
        val decision = policy.removeDownloadHistory(download(DownloadStatus.COMPLETED))

        assertTrue(decision.deleteQueueRow)
        assertFalse(decision.cancelActiveTransfer)
        assertFalse(decision.deleteOwnedIncompletePart)
        assertFalse(decision.deletePhysicalMedia)
        assertFalse(decision.deleteLocalLibraryIndex)
    }

    @Test
    fun removeFromLocalLibraryDetachesIndexWithoutDeletingPhysicalMedia() {
        val decision = policy.removeFromLocalLibrary()

        assertTrue(decision.deleteLocalLibraryIndex)
        assertFalse(decision.deleteQueueRow)
        assertFalse(decision.deletePhysicalMedia)
    }

    @Test
    fun removeRootFromSettingsDoesNotDeletePhysicalMedia() {
        val decision = policy.removeRootFromSettings()

        assertTrue(decision.deleteLocalLibraryIndex)
        assertFalse(decision.deletePhysicalMedia)
    }

    @Test
    fun physicalMediaDeleteRequiresExplicitConfirmation() {
        try {
            policy.deletePhysicalMedia(confirmed = false)
            fail("Expected deletion without confirmation to fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertTrue(policy.deletePhysicalMedia(confirmed = true).deletePhysicalMedia)
    }

    private fun download(status: DownloadStatus): DownloadDto =
        DownloadDto(
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            itemId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            itemName = "Item",
            itemType = "Movie",
            sourceId = "source",
            sourceName = "Source",
            status = status,
            progress = 0f,
            bytesDownloaded = 0L,
            totalBytes = 0L,
            filePath = null,
            error = null,
            createdAt = 1L,
            updatedAt = 1L,
            serverId = "server",
            userId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            imageUrl = null,
            seriesImageUrl = null,
            seriesName = null,
            seasonNumber = null,
            episodeNumber = null,
            releaseYear = null,
            runtimeTicks = null,
        )
}
