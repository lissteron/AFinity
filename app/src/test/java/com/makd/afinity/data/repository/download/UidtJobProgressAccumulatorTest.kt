package com.makd.afinity.data.repository.download

import android.app.job.JobInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UidtJobProgressAccumulatorTest {
    @Test
    fun transferredBytesAreCumulativeAcrossFilesAndDoNotCountResumeOffset() {
        val accumulator = UidtJobProgressAccumulator(initialEstimatedDownloadBytes = 300L)
        val firstId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondId = UUID.fromString("00000000-0000-0000-0000-000000000002")

        assertEquals(
            10L,
            accumulator
                .record(
                    progress(
                        downloadId = firstId,
                        downloadedBytes = 110L,
                        totalBytes = 200L,
                        networkBytesDelta = 10L,
                    )
                )
                .transferredDownloadBytes,
        )
        assertEquals(
            100L,
            accumulator
                .record(
                    progress(
                        downloadId = firstId,
                        downloadedBytes = 200L,
                        totalBytes = 200L,
                        networkBytesDelta = 90L,
                    )
                )
                .transferredDownloadBytes,
        )
        val secondSnapshot =
            accumulator.record(
                progress(
                    downloadId = secondId,
                    downloadedBytes = 30L,
                    totalBytes = 100L,
                    networkBytesDelta = 30L,
                )
            )

        assertEquals(130L, secondSnapshot.transferredDownloadBytes)
        assertEquals(300L, secondSnapshot.estimatedDownloadBytes)
    }

    @Test
    fun unknownEstimateIsRaisedToObservedLowerBound() {
        val accumulator =
            UidtJobProgressAccumulator(
                initialEstimatedDownloadBytes = JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
            )

        val snapshot =
            accumulator.record(
                progress(
                    downloadedBytes = 40L,
                    totalBytes = 100L,
                    networkBytesDelta = 20L,
                )
            )

        assertTrue(snapshot.hasKnownEstimate)
        assertEquals(80L, snapshot.estimatedDownloadBytes)
        assertEquals(20L, snapshot.transferredDownloadBytes)
    }

    private fun progress(
        downloadId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        downloadedBytes: Long,
        totalBytes: Long,
        networkBytesDelta: Long,
    ): MediaDownloadTransferRunner.DownloadProgress =
        MediaDownloadTransferRunner.DownloadProgress(
            downloadId = downloadId,
            itemName = "item",
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            progress = if (totalBytes > 0L) downloadedBytes.toFloat() / totalBytes else 0f,
            networkBytesDelta = networkBytesDelta,
        )
}
