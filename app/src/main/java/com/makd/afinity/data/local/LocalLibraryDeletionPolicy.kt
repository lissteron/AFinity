package com.makd.afinity.data.local

import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.models.download.DownloadStatus
import javax.inject.Inject
import javax.inject.Singleton

enum class LocalLibraryRemovalAction {
    CANCEL_QUEUED_DOWNLOAD,
    CANCEL_ACTIVE_DOWNLOAD,
    REMOVE_DOWNLOAD_HISTORY,
    REMOVE_FROM_LOCAL_LIBRARY,
    REMOVE_ROOT,
    DELETE_PHYSICAL_MEDIA,
    CLEANUP_LEGACY_FILES,
}

data class LocalLibraryDeletionDecision(
    val action: LocalLibraryRemovalAction,
    val cancelActiveTransfer: Boolean,
    val deleteQueueRow: Boolean,
    val deleteOwnedIncompletePart: Boolean,
    val deletePhysicalMedia: Boolean,
    val deleteLocalLibraryIndex: Boolean,
)

@Singleton
class LocalLibraryDeletionPolicy @Inject constructor() {
    fun cancelDownload(download: DownloadDto): LocalLibraryDeletionDecision {
        val active = download.status == DownloadStatus.DOWNLOADING
        return LocalLibraryDeletionDecision(
            action =
                if (active) {
                    LocalLibraryRemovalAction.CANCEL_ACTIVE_DOWNLOAD
                } else {
                    LocalLibraryRemovalAction.CANCEL_QUEUED_DOWNLOAD
                },
            cancelActiveTransfer = active,
            deleteQueueRow = true,
            deleteOwnedIncompletePart = active,
            deletePhysicalMedia = false,
            deleteLocalLibraryIndex = false,
        )
    }

    fun removeDownloadHistory(download: DownloadDto): LocalLibraryDeletionDecision =
        LocalLibraryDeletionDecision(
            action = LocalLibraryRemovalAction.REMOVE_DOWNLOAD_HISTORY,
            cancelActiveTransfer = download.status == DownloadStatus.DOWNLOADING,
            deleteQueueRow = true,
            deleteOwnedIncompletePart = false,
            deletePhysicalMedia = false,
            deleteLocalLibraryIndex = false,
        )

    fun removeFromLocalLibrary(): LocalLibraryDeletionDecision =
        LocalLibraryDeletionDecision(
            action = LocalLibraryRemovalAction.REMOVE_FROM_LOCAL_LIBRARY,
            cancelActiveTransfer = false,
            deleteQueueRow = false,
            deleteOwnedIncompletePart = false,
            deletePhysicalMedia = false,
            deleteLocalLibraryIndex = true,
        )

    fun removeRootFromSettings(): LocalLibraryDeletionDecision =
        LocalLibraryDeletionDecision(
            action = LocalLibraryRemovalAction.REMOVE_ROOT,
            cancelActiveTransfer = false,
            deleteQueueRow = false,
            deleteOwnedIncompletePart = false,
            deletePhysicalMedia = false,
            deleteLocalLibraryIndex = true,
        )

    fun deletePhysicalMedia(confirmed: Boolean): LocalLibraryDeletionDecision {
        require(confirmed) { "Physical media deletion requires explicit user confirmation" }
        return LocalLibraryDeletionDecision(
            action = LocalLibraryRemovalAction.DELETE_PHYSICAL_MEDIA,
            cancelActiveTransfer = false,
            deleteQueueRow = false,
            deleteOwnedIncompletePart = false,
            deletePhysicalMedia = true,
            deleteLocalLibraryIndex = true,
        )
    }

    fun cleanupLegacyFilesAfterMigration(confirmed: Boolean): LocalLibraryDeletionDecision {
        require(confirmed) { "Legacy file cleanup requires explicit user confirmation" }
        return LocalLibraryDeletionDecision(
            action = LocalLibraryRemovalAction.CLEANUP_LEGACY_FILES,
            cancelActiveTransfer = false,
            deleteQueueRow = false,
            deleteOwnedIncompletePart = false,
            deletePhysicalMedia = true,
            deleteLocalLibraryIndex = false,
        )
    }
}
