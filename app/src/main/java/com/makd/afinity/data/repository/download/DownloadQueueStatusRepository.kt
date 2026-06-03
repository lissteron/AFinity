package com.makd.afinity.data.repository.download

import com.makd.afinity.data.models.download.DownloadQueueStatus
import com.makd.afinity.data.repository.DatabaseRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Singleton
class DownloadQueueStatusRepository
@Inject
constructor(
    private val databaseRepository: DatabaseRepository,
    private val scheduler: DownloadQueueScheduler,
) {

    val status: Flow<DownloadQueueStatus> =
        combine(
            databaseRepository.getActiveDownloadFlow(),
            databaseRepository.countQueuedDownloadsFlow(),
            scheduler.deferredUidtReason,
        ) { active, queuedCount, schedulerMessage ->
            if (active == null) {
                DownloadQueueStatus.Empty.copy(
                    queuedCount = queuedCount,
                    schedulerMessage = schedulerMessage,
                )
            } else {
                DownloadQueueStatus(
                    activeDownloadId = active.id,
                    itemTitle = active.itemName,
                    status = active.status,
                    progress = active.progress,
                    queuedCount = queuedCount,
                    serverId = active.serverId,
                    userId = active.userId,
                    schedulerMessage = schedulerMessage,
                )
            }
        }
}
