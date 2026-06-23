package com.makd.afinity.ui.home

import com.makd.afinity.data.models.audiobookshelf.AbsDownloadInfo
import com.makd.afinity.data.models.audiobookshelf.AbsDownloadStatus
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStartupContentPolicyTest {
    @Test
    fun onlineRemoteContentDoesNotHideAvailableDownloadedSections() {
        val flags =
            HomeUiState(downloadedAudiobooks = listOf(downloadedBook()))
                .startupContentFlags()

        assertTrue(flags.canShowRemoteContent)
        assertTrue(flags.hasLocalCatalogContent)
        assertTrue(flags.showDownloadedSections)
        assertFalse(flags.useOfflineContinueWatching)
    }

    @Test
    fun offlineModeUsesLocalContinueWatchingAndDownloadedSections() {
        val flags = HomeUiState(isOffline = true).startupContentFlags()

        assertFalse(flags.canShowRemoteContent)
        assertFalse(flags.hasLocalCatalogContent)
        assertTrue(flags.showDownloadedSections)
        assertTrue(flags.useOfflineContinueWatching)
    }

    @Test
    fun emptyOnlineStateDoesNotInventDownloadedSections() {
        val flags = HomeUiState().startupContentFlags()

        assertTrue(flags.canShowRemoteContent)
        assertFalse(flags.hasLocalCatalogContent)
        assertFalse(flags.showDownloadedSections)
        assertFalse(flags.useOfflineContinueWatching)
    }

    private fun downloadedBook(): AbsDownloadInfo =
        AbsDownloadInfo(
            id = UUID.randomUUID(),
            libraryItemId = "book-1",
            episodeId = null,
            title = "Book",
            authorName = "Author",
            mediaType = "book",
            coverUrl = null,
            duration = 1.0,
            status = AbsDownloadStatus.COMPLETED,
            progress = 1f,
            bytesDownloaded = 1L,
            totalBytes = 1L,
            tracksTotal = 1,
            tracksDownloaded = 1,
            error = null,
            createdAt = 1L,
            updatedAt = 1L,
            localDirPath = "/tmp/book",
        )
}
