package com.makd.afinity.data.repository.download

import android.app.job.JobInfo

class UidtJobProgressAccumulator(initialEstimatedDownloadBytes: Long) {
    private var transferredDownloadBytes = 0L
    private var estimatedDownloadBytes = initialEstimatedDownloadBytes

    fun record(progress: MediaDownloadTransferRunner.DownloadProgress): Snapshot {
        transferredDownloadBytes += progress.networkBytesDelta.coerceAtLeast(0L)

        val observedLowerBound =
            if (progress.totalBytes > 0L) {
                transferredDownloadBytes +
                    (progress.totalBytes - progress.downloadedBytes).coerceAtLeast(0L)
            } else {
                transferredDownloadBytes
            }
        if (
            observedLowerBound > 0L &&
                (estimatedDownloadBytes == JobInfo.NETWORK_BYTES_UNKNOWN.toLong() ||
                    observedLowerBound > estimatedDownloadBytes)
        ) {
            estimatedDownloadBytes = observedLowerBound
        }

        return snapshot()
    }

    fun snapshot(): Snapshot =
        Snapshot(
            transferredDownloadBytes = transferredDownloadBytes,
            estimatedDownloadBytes = estimatedDownloadBytes,
        )

    data class Snapshot(
        val transferredDownloadBytes: Long,
        val estimatedDownloadBytes: Long,
    ) {
        val hasKnownEstimate: Boolean
            get() = estimatedDownloadBytes != JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
    }
}
