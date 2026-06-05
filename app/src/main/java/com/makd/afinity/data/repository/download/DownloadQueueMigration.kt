package com.makd.afinity.data.repository.download

import android.content.Context
import androidx.work.WorkManager
import com.makd.afinity.data.repository.DatabaseRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class DownloadQueueMigration
@Inject
constructor(
    @ApplicationContext context: Context,
    private val workManager: WorkManager,
    private val databaseRepository: DatabaseRepository,
    private val stateStore: DownloadQueueStateStore,
    private val scheduler: DownloadQueueScheduler,
) {
    private val preferences =
        context.getSharedPreferences("download_queue_migration", Context.MODE_PRIVATE)

    suspend fun run() {
        val startupReconciled =
            stateStore.reconcileStartupActiveClaims(
                reason = "Interrupted download recovered to queued state"
            )
        if (startupReconciled > 0) {
            Timber.w("Requeued $startupReconciled interrupted DOWNLOADING rows during startup")
        }
        val repairedPaused = stateStore.requeuePausedInterruptedDownloads()
        if (repairedPaused > 0) {
            Timber.w("Requeued $repairedPaused legacy interrupted PAUSED rows during startup")
        }
        val repairedUidtStopped = stateStore.requeuePausedUidtStoppedDownloads()
        if (repairedUidtStopped > 0) {
            Timber.w("Requeued $repairedUidtStopped UIDT-stopped PAUSED rows during startup")
        }
        val repairedTransientFailed = stateStore.requeueZeroByteTransientFailedDownloads()
        if (repairedTransientFailed > 0) {
            Timber.w(
                "Requeued $repairedTransientFailed zero-byte transient FAILED rows during startup"
            )
        }

        if (preferences.getBoolean(KEY_LEGACY_CLEANUP_COMPLETE, false)) {
            scheduler.scheduleQueue(DownloadQueueScheduleTrigger.MIGRATION)
            return
        }

        Timber.i("Running media download queue migration")
        awaitCancel("download_active tag") { workManager.cancelAllWorkByTag("download_active") }
        awaitCancel("${DownloadQueueScheduler.WORK_NAME} unique work") {
            workManager.cancelUniqueWork(DownloadQueueScheduler.WORK_NAME)
        }
        awaitCancel("${DownloadQueueScheduler.WORK_TAG} tag") {
            workManager.cancelAllWorkByTag(DownloadQueueScheduler.WORK_TAG)
        }
        databaseRepository.getNonCompletedDownloads().forEach { download ->
            val legacyName = "download_${download.id}"
            awaitCancel("$legacyName unique work") { workManager.cancelUniqueWork(legacyName) }
            awaitCancel("$legacyName tag") { workManager.cancelAllWorkByTag(legacyName) }
        }
        val paused =
            databaseRepository.markStaleDownloadingPaused(
                error = "Legacy media download worker neutralized during queue migration"
            )
        if (paused > 0) {
            Timber.w("Paused $paused legacy DOWNLOADING rows during queue migration")
        }
        preferences.edit().putBoolean(KEY_LEGACY_CLEANUP_COMPLETE, true).apply()
        scheduler.scheduleQueue(DownloadQueueScheduleTrigger.MIGRATION)
    }

    private fun awaitCancel(label: String, cancel: () -> androidx.work.Operation) {
        try {
            cancel().result.get()
        } catch (e: Exception) {
            Timber.w(e, "Failed to wait for legacy WorkManager cancellation: $label")
        }
    }

    private companion object {
        const val KEY_LEGACY_CLEANUP_COMPLETE = "legacy_media_download_cleanup_complete_v1"
    }
}
