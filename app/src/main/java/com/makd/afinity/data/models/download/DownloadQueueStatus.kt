package com.makd.afinity.data.models.download

import java.util.UUID

data class DownloadQueueStatus(
    val activeDownloadId: UUID?,
    val itemTitle: String?,
    val status: DownloadStatus?,
    val progress: Float,
    val queuedCount: Int,
    val serverId: String?,
    val userId: UUID?,
    val schedulerMessage: String? = null,
) {
    val hasVisibleActivity: Boolean
        get() = activeDownloadId != null || queuedCount > 0 || schedulerMessage != null

    companion object {
        val Empty =
            DownloadQueueStatus(
                activeDownloadId = null,
                itemTitle = null,
                status = null,
                progress = 0f,
                queuedCount = 0,
                serverId = null,
                userId = null,
                schedulerMessage = null,
            )
    }
}
