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

    suspend fun estimatePendingDownloadBytes(): Long {
        val pending = databaseRepository.getPendingQueueDownloads()
        return DownloadQueueSchedulePlanner()
            .estimateBytes(
                pending.map { download ->
                    QueueByteEstimate(
                        bytesDownloaded = download.bytesDownloaded,
                        totalBytes = download.totalBytes,
                    )
                }
            )
    }

    suspend fun reconcileOrphanedActiveClaims(
        backendRunId: UUID,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Int =
        databaseRepository.requeueOrphanedActiveDownloads(
            activeBackendRunId = backendRunId,
            error = reason,
            updatedAt = now,
        )

    suspend fun pauseAllActiveForSchedulerFailure(
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Int = databaseRepository.pauseAllActiveDownloads(error = reason, updatedAt = now)

    suspend fun requeueAllActiveForTransientStop(
        reason: String,
        now: Long = System.currentTimeMillis(),
    ): Int = databaseRepository.requeueAllActiveDownloads(error = reason, updatedAt = now)

    suspend fun reconcileStartupActiveClaims(
        reason: String = "Interrupted download recovered to queued state",
        now: Long = System.currentTimeMillis(),
    ): Int = databaseRepository.requeueAllActiveDownloads(error = reason, updatedAt = now)

    suspend fun requeuePausedInterruptedDownloads(
        now: Long = System.currentTimeMillis()
    ): Int =
        databaseRepository.requeuePausedDownloadsByError(
            legacyError = LEGACY_INTERRUPTED_PAUSED_REASON,
            newError = "Interrupted download recovered to queued state",
            updatedAt = now,
        )

    suspend fun requeuePausedUidtStoppedDownloads(
        now: Long = System.currentTimeMillis()
    ): Int =
        databaseRepository.requeuePausedDownloadsByErrorPattern(
            legacyErrorPattern = UIDT_STOPPED_PAUSED_REASON_PATTERN,
            newError = "UIDT system stop recovered to queued state",
            updatedAt = now,
        )

    suspend fun requeuePausedTransientInterruptedDownloads(
        now: Long = System.currentTimeMillis()
    ): Int =
        TRANSIENT_PAUSED_REASON_PATTERNS.sumOf { legacyErrorPattern ->
            databaseRepository.requeuePausedDownloadsByErrorPattern(
                legacyErrorPattern = legacyErrorPattern,
                newError = "Transient download interruption recovered to queued state",
                updatedAt = now,
            )
        }

    suspend fun requeueZeroByteTransientFailedDownloads(
        now: Long = System.currentTimeMillis()
    ): Int =
        TRANSIENT_FAILED_REASON_PATTERNS.sumOf { legacyErrorPattern ->
            databaseRepository.requeueZeroByteFailedDownloadsByErrorPattern(
                legacyErrorPattern = legacyErrorPattern,
                newError = "Transient network failure recovered to queued state",
                updatedAt = now,
            )
        }

    suspend fun requeueRecoverableInterruptedDownloads(
        now: Long = System.currentTimeMillis()
    ): Int =
        requeuePausedInterruptedDownloads(now) +
            requeuePausedUidtStoppedDownloads(now) +
            requeuePausedTransientInterruptedDownloads(now) +
            requeueZeroByteTransientFailedDownloads(now)

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

    private companion object {
        const val LEGACY_INTERRUPTED_PAUSED_REASON =
            "Interrupted download recovered to paused queue state"
        const val UIDT_STOPPED_PAUSED_REASON_PATTERN = "UIDT job stopped:%"
        val TRANSIENT_PAUSED_REASON_PATTERNS =
            DownloadQueueTransientFailureClassifier.sqlLikePatterns
        val TRANSIENT_FAILED_REASON_PATTERNS =
            DownloadQueueTransientFailureClassifier.sqlLikePatterns
    }
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
