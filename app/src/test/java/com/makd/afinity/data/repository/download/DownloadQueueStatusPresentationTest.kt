package com.makd.afinity.data.repository.download

import com.makd.afinity.data.models.download.DownloadQueueStatus
import com.makd.afinity.data.models.download.DownloadQueueStatusLabelKind
import com.makd.afinity.data.models.download.DownloadQueueStatusPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueStatusPresentationTest {
    @Test
    fun notificationSchedulerMessageUsesNotificationGuidance() {
        val status =
            DownloadQueueStatus.Empty.copy(
                queuedCount = 1,
                schedulerMessage = "Required notification permission is blocked",
            )

        assertEquals(
            DownloadQueueStatusLabelKind.ENABLE_NOTIFICATIONS,
            DownloadQueueStatusPresentation.labelKind(status),
        )
    }

    @Test
    fun genericSchedulerMessageUsesBlockedGuidance() {
        val status =
            DownloadQueueStatus.Empty.copy(
                queuedCount = 1,
                schedulerMessage = "JobScheduler rejected UIDT download queue job",
            )

        assertTrue(status.hasVisibleActivity)
        assertEquals(
            DownloadQueueStatusLabelKind.SCHEDULER_BLOCKED,
            DownloadQueueStatusPresentation.labelKind(status),
        )
    }

    @Test
    fun activeDownloadLabelWinsWhenSchedulerIsHealthy() {
        val status =
            DownloadQueueStatus.Empty.copy(
                activeDownloadId = java.util.UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
                ),
                queuedCount = 3,
                progress = 0.25f,
            )

        assertEquals(
            DownloadQueueStatusLabelKind.ACTIVE,
            DownloadQueueStatusPresentation.labelKind(status),
        )
    }
}
