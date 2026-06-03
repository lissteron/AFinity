package com.makd.afinity.data.repository.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.makd.afinity.data.repository.DatabaseRepository
import com.makd.afinity.data.repository.PreferencesRepository
import com.makd.afinity.data.workers.MediaDownloadQueueWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

@Singleton
class DownloadQueueScheduler
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val preferencesRepository: PreferencesRepository,
    private val databaseRepository: DatabaseRepository,
    private val stateStore: DownloadQueueStateStore,
    private val visibilityTracker: AppVisibilityTracker,
    private val notificationFactory: DownloadQueueNotificationFactory,
) {
    companion object {
        const val UIDT_JOB_ID = 42042
        const val WORK_NAME = "media_download_queue"
        const val WORK_TAG = "media_download_queue"
        private const val MIN_MEANINGFUL_CHUNK_BYTES = 256L * 1024L
    }

    private val schedulerState = DownloadQueueSchedulerState()
    val deferredUidtSchedule: StateFlow<Boolean> = schedulerState.deferredUidtSchedule
    val deferredUidtReason: StateFlow<String?> = schedulerState.schedulerMessage
    private val planner = DownloadQueueSchedulePlanner()

    suspend fun scheduleQueue(
        trigger: DownloadQueueScheduleTrigger = DownloadQueueScheduleTrigger.USER_ACTION
    ): ScheduleResult {
        val snapshot = stateStore.snapshot()
        val notificationsAllowed =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                notificationFactory.canPostRequiredNotification()
            } else {
                true
            }

        return when (
            val plan =
                planner.plan(
                    sdkInt = Build.VERSION.SDK_INT,
                    trigger = trigger,
                    isVisible = visibilityTracker.isVisibleNow(),
                    queuedCount = snapshot.queuedCount,
                    notificationsAllowed = notificationsAllowed,
                )
        ) {
            DownloadQueueSchedulePlanner.Plan.ScheduleUidt -> scheduleUidtQueueJob()
            DownloadQueueSchedulePlanner.Plan.ScheduleWorkManager -> scheduleWorkManagerQueueWorker()
            DownloadQueueSchedulePlanner.Plan.NoEligibleRows -> {
                schedulerState.clear()
                ScheduleResult.NoEligibleRows
            }
            is DownloadQueueSchedulePlanner.Plan.DeferUidt -> {
                schedulerState.recordUidtDeferral(plan.reason)
                ScheduleResult.Deferred(plan.reason)
            }
        }
    }

    suspend fun resumeQueue(trigger: DownloadQueueScheduleTrigger): ScheduleResult =
        scheduleQueue(trigger)

    fun recordBackendStartFailure(reason: String) {
        schedulerState.recordSchedulerFailure(reason)
    }

    suspend fun cancelQueue() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(JobScheduler::class.java).cancel(UIDT_JOB_ID)
        }
        try {
            workManager.cancelUniqueWork(WORK_NAME).result.get()
        } catch (e: Exception) {
            Timber.w(e, "Failed to wait for WorkManager download queue cancellation")
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun scheduleUidtQueueJob(): ScheduleResult {
        val jobInfo = buildUidtJobInfo()
        return try {
            val result = context.getSystemService(JobScheduler::class.java).schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                schedulerState.clear()
                ScheduleResult.ScheduledUidt
            } else {
                val reason = "JobScheduler rejected UIDT download queue job"
                Timber.w(reason)
                schedulerState.recordSchedulerFailure(reason)
                ScheduleResult.Failed(reason)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "UIDT scheduling security failure")
            val reason = e.message ?: "UIDT scheduling security failure"
            schedulerState.recordSchedulerFailure(reason)
            ScheduleResult.Failed(reason)
        } catch (e: Exception) {
            Timber.e(e, "UIDT scheduling failure")
            val reason = e.message ?: "UIDT scheduling failure"
            schedulerState.recordSchedulerFailure(reason)
            ScheduleResult.Failed(reason)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    internal suspend fun buildUidtJobInfo(): JobInfo {
        val wifiOnly = preferencesRepository.getDownloadOverWifiOnly()
        val estimatedBytes = estimatePendingDownloadBytes()
        val builder =
            JobInfo.Builder(
                    UIDT_JOB_ID,
                    ComponentName(context, MediaDownloadQueueJobService::class.java),
                )
                .setUserInitiated(true)
                .setRequiredNetworkType(
                    if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
                )
                .setRequiresStorageNotLow(true)
                .setEstimatedNetworkBytes(estimatedBytes, JobInfo.NETWORK_BYTES_UNKNOWN.toLong())

        if (estimatedBytes != JobInfo.NETWORK_BYTES_UNKNOWN.toLong()) {
            builder.setMinimumNetworkChunkBytes(
                estimatedBytes.coerceAtMost(8L * 1024L * 1024L).coerceAtLeast(MIN_MEANINGFUL_CHUNK_BYTES)
            )
        }
        return builder.build()
    }

    private suspend fun estimatePendingDownloadBytes(): Long {
        val pending = databaseRepository.getPendingQueueDownloads()
        return planner.estimateBytes(
            pending.map { download ->
                QueueByteEstimate(
                    bytesDownloaded = download.bytesDownloaded,
                    totalBytes = download.totalBytes,
                )
            }
        )
    }

    private suspend fun scheduleWorkManagerQueueWorker(): ScheduleResult {
        return try {
            val wifiOnly = preferencesRepository.getDownloadOverWifiOnly()
            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
                    .setRequiresStorageNotLow(true)
                    .build()
            val request =
                OneTimeWorkRequestBuilder<MediaDownloadQueueWorker>()
                    .setConstraints(constraints)
                    .addTag(WORK_TAG)
                    .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            schedulerState.clear()
            ScheduleResult.ScheduledWorkManager
        } catch (e: Exception) {
            Timber.e(e, "WorkManager download queue scheduling failure")
            val reason = e.message ?: "WorkManager download queue scheduling failure"
            schedulerState.recordSchedulerFailure(reason)
            ScheduleResult.Failed(reason)
        }
    }

    sealed class ScheduleResult {
        data object ScheduledUidt : ScheduleResult()
        data object ScheduledWorkManager : ScheduleResult()
        data object NoEligibleRows : ScheduleResult()
        data class Deferred(val reason: String) : ScheduleResult()
        data class Failed(val reason: String) : ScheduleResult()
    }
}
