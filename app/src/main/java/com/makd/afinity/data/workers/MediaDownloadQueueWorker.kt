package com.makd.afinity.data.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.makd.afinity.data.repository.download.DownloadQueueBackendStartFailureHandler
import com.makd.afinity.data.repository.download.DownloadQueueBackends
import com.makd.afinity.data.repository.download.DownloadQueueNotificationFactory
import com.makd.afinity.data.repository.download.DownloadQueueRunner
import com.makd.afinity.data.repository.download.DownloadQueueScheduleTrigger
import com.makd.afinity.data.repository.download.DownloadQueueScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class MediaDownloadQueueWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val queueRunner: DownloadQueueRunner,
    private val notificationFactory: DownloadQueueNotificationFactory,
    private val scheduler: DownloadQueueScheduler,
    private val backendStartFailureHandler: DownloadQueueBackendStartFailureHandler,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Timber.w("Neutralized API 34+ WorkManager download queue fallback")
                scheduler.scheduleQueue(DownloadQueueScheduleTrigger.LEGACY_WORKER)
                return@withContext Result.success()
            }

            try {
                setForegroundOrPause(notificationFactory.buildQueueNotification())
            } catch (e: ForegroundUnavailableException) {
                Timber.w(e, "Download queue foreground start failed")
                backendStartFailureHandler.record(
                    e.message ?: "Foreground service start was not allowed"
                )
                return@withContext Result.failure(workDataOf("error" to e.message))
            }

            val backendRunId = UUID.randomUUID()
            var lastNotificationUpdate = 0L
            val runResult =
                queueRunner.run(
                    backendRunId = backendRunId,
                    backendKind = DownloadQueueBackends.WORK_MANAGER,
                ) { progress ->
                    val now = System.currentTimeMillis()
                    if (now - lastNotificationUpdate > 1_000L || progress.progress >= 1f) {
                        lastNotificationUpdate = now
                        try {
                            setForegroundOrPause(
                                notificationFactory.buildQueueNotification(progress = progress)
                            )
                        } catch (e: ForegroundUnavailableException) {
                            queueRunner.stopActive(
                                e.message ?: "Foreground notification update failed"
                            )
                        }
                    }
                }

            Timber.i(
                "Download queue worker finished: completed=${runResult.completed}, " +
                    "failed=${runResult.failed}, paused=${runResult.paused}, stopped=${runResult.stopped}"
            )
            Result.success()
        }

    private suspend fun setForegroundOrPause(notification: android.app.Notification) {
        try {
            setForeground(
                ForegroundInfo(
                    DownloadQueueNotificationFactory.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to maintain download queue foreground notification")
            throw ForegroundUnavailableException(e.foregroundServicePauseMessage(), e)
        }
    }

    private class ForegroundUnavailableException(message: String, cause: Throwable) :
        Exception(message, cause)

    private fun Throwable.foregroundServicePauseMessage(): String =
        message?.takeIf { it.isNotBlank() }
            ?: "Download paused: foreground notification could not be maintained"
}
