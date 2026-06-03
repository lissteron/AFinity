package com.makd.afinity.data.repository.download

import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.models.download.DownloadStatus
import com.makd.afinity.data.repository.DatabaseRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueueStateStore
@Inject
constructor(private val databaseRepository: DatabaseRepository) {

    suspend fun snapshot(): DownloadQueueSnapshot {
        val activeDownloads = databaseRepository.getActiveDownloadingDownloads()
        val queuedCount = databaseRepository.countQueuedDownloads()
        return DownloadQueueSnapshot(
            queuedCount = queuedCount,
            activeDownloadCount = activeDownloads.size,
            hasActiveBackendLease = activeDownloads.any { it.activeBackendRunId != null },
            activeDownloads = activeDownloads,
        )
    }

    suspend fun reconcileOrphanedActiveClaims(
        backendRunId: UUID,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Int =
        databaseRepository.pauseOrphanedActiveDownloads(
            activeBackendRunId = backendRunId,
            error = reason,
            updatedAt = now,
        )

    suspend fun pauseAllActiveForSchedulerFailure(
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Int = databaseRepository.pauseAllActiveDownloads(error = reason, updatedAt = now)

    suspend fun reconcileStartupActiveClaims(
        reason: String = "Interrupted download recovered to paused queue state",
        now: Long = System.currentTimeMillis(),
    ): Int = databaseRepository.pauseAllActiveDownloads(error = reason, updatedAt = now)

    suspend fun claimOldestQueuedDownload(
        backendRunId: UUID,
        backendKind: String,
        activeClaimId: UUID = UUID.randomUUID(),
        now: Long = System.currentTimeMillis(),
    ): DownloadDto? =
        databaseRepository.claimOldestQueuedDownload(
            activeClaimId = activeClaimId,
            activeBackendRunId = backendRunId,
            activeBackendKind = backendKind,
            updatedAt = now,
        )

    suspend fun updateOwnedProgress(
        downloadId: UUID,
        activeClaimId: UUID,
        backendRunId: UUID,
        progress: Float,
        bytesDownloaded: Long,
        totalBytes: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        databaseRepository.updateActiveDownloadProgress(
            downloadId = downloadId,
            activeClaimId = activeClaimId,
            activeBackendRunId = backendRunId,
            progress = progress,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            updatedAt = now,
        )

    suspend fun completeOwned(
        downloadId: UUID,
        activeClaimId: UUID,
        backendRunId: UUID,
        bytes: Long,
        filePath: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        databaseRepository.finalizeActiveDownload(
            downloadId = downloadId,
            activeClaimId = activeClaimId,
            activeBackendRunId = backendRunId,
            status = DownloadStatus.COMPLETED,
            progress = 1f,
            bytesDownloaded = bytes,
            totalBytes = bytes,
            filePath = filePath,
            error = null,
            updatedAt = now,
        )

    suspend fun failOwned(
        downloadId: UUID,
        activeClaimId: UUID,
        backendRunId: UUID,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        databaseRepository.finalizeActiveDownload(
            downloadId = downloadId,
            activeClaimId = activeClaimId,
            activeBackendRunId = backendRunId,
            status = DownloadStatus.FAILED,
            progress = 0f,
            bytesDownloaded = 0L,
            totalBytes = 0L,
            filePath = null,
            error = reason,
            updatedAt = now,
        )

    suspend fun pauseOwned(
        downloadId: UUID,
        activeClaimId: UUID,
        backendRunId: UUID,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        databaseRepository.pauseClaimedActiveDownload(
            downloadId = downloadId,
            activeClaimId = activeClaimId,
            activeBackendRunId = backendRunId,
            error = reason,
            updatedAt = now,
        )

    suspend fun requeueOwned(
        downloadId: UUID,
        activeClaimId: UUID,
        backendRunId: UUID,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        databaseRepository.requeueActiveDownload(
            downloadId = downloadId,
            activeClaimId = activeClaimId,
            activeBackendRunId = backendRunId,
            error = reason,
            updatedAt = now,
        )

    suspend fun pauseActiveDownload(
        downloadId: UUID,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = databaseRepository.pauseActiveDownload(downloadId, reason, now)
}

data class DownloadQueueSnapshot(
    val queuedCount: Int,
    val activeDownloadCount: Int,
    val hasActiveBackendLease: Boolean,
    val activeDownloads: List<DownloadDto>,
) {
    val hasRunnableQueuedRows: Boolean
        get() = queuedCount > 0
}
