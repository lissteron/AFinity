package com.makd.afinity.data.models.download

object DownloadQueueStatusPresentation {
    fun labelKind(status: DownloadQueueStatus): DownloadQueueStatusLabelKind {
        val schedulerMessage = status.schedulerMessage
        return when {
            schedulerMessage?.contains("notification", ignoreCase = true) == true ->
                DownloadQueueStatusLabelKind.ENABLE_NOTIFICATIONS
            schedulerMessage != null -> DownloadQueueStatusLabelKind.SCHEDULER_BLOCKED
            status.activeDownloadId != null -> DownloadQueueStatusLabelKind.ACTIVE
            else -> DownloadQueueStatusLabelKind.QUEUED
        }
    }
}

enum class DownloadQueueStatusLabelKind {
    ENABLE_NOTIFICATIONS,
    SCHEDULER_BLOCKED,
    ACTIVE,
    QUEUED,
}
