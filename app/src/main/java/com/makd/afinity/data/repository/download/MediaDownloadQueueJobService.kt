package com.makd.afinity.data.repository.download

import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class MediaDownloadQueueJobService : JobService() {
    @Inject lateinit var queueRunner: DownloadQueueRunner
    @Inject lateinit var stateStore: DownloadQueueStateStore
    @Inject lateinit var backendStartFailureHandler: DownloadQueueBackendStartFailureHandler
    @Inject lateinit var notificationFactory: DownloadQueueNotificationFactory
    @Inject lateinit var uidtNetworkSession: UidtNetworkSession

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceScope: CoroutineScope? = null
    @Volatile private var runningJob: Job? = null
    private val lifecycle = UidtJobLifecycle()

    override fun onStartJob(params: JobParameters): Boolean {
        val runId =
            when (val start = lifecycle.startIfIdle()) {
                is UidtJobRunStart.Started -> start.runId
                is UidtJobRunStart.AlreadyRunning -> {
                    Timber.w(
                        "Ignoring duplicate UIDT onStartJob while run ${start.activeRunId} is active"
                    )
                    return false
                }
            }
        val notification =
            try {
                notificationFactory.buildQueueNotification()
            } catch (e: Exception) {
                Timber.e(e, "Failed to build UIDT notification")
                return startBackendStartFailureCleanup(
                    runId = runId,
                    params = params,
                    reason = e.message ?: "UIDT notification could not be built",
                )
            }
        try {
            setNotification(
                params,
                DownloadQueueNotificationFactory.NOTIFICATION_ID,
                notification,
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to set required UIDT notification")
            return startBackendStartFailureCleanup(
                runId = runId,
                params = params,
                reason = e.message ?: "UIDT required notification could not be shown",
            )
        }

        val network = params.network
        if (network == null) {
            return startRequeueAllActiveCleanup(
                runId = runId,
                params = params,
                reason = "UIDT required network is unavailable",
            )
        }

        uidtNetworkSession.startJob(network)
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lifecycle.markRunnerWork()
        runningJob =
            serviceScope?.launch {
                try {
                    val backendRunId = UUID.randomUUID()
                    val uidtProgress =
                        UidtJobProgressAccumulator(
                            initialEstimatedDownloadBytes =
                                stateStore.estimatePendingDownloadBytes()
                        )
                    uidtProgress.snapshot().let { snapshot ->
                        if (snapshot.hasKnownEstimate) {
                            updateEstimatedNetworkBytes(
                                params,
                                snapshot.estimatedDownloadBytes,
                                0L,
                            )
                        }
                    }
                    val result =
                        queueRunner.run(
                            requiredNetwork = network,
                            backendRunId = backendRunId,
                            backendKind = DownloadQueueBackends.UIDT,
                        ) { progress ->
                            try {
                                val jobProgress = uidtProgress.record(progress)
                                if (jobProgress.hasKnownEstimate) {
                                    updateEstimatedNetworkBytes(
                                        params,
                                        jobProgress.estimatedDownloadBytes,
                                        0L,
                                    )
                                }
                                updateTransferredNetworkBytes(
                                    params,
                                    jobProgress.transferredDownloadBytes,
                                    0L,
                                )
                                setNotification(
                                    params,
                                    DownloadQueueNotificationFactory.NOTIFICATION_ID,
                                    notificationFactory.buildQueueNotification(progress = progress),
                                    JOB_END_NOTIFICATION_POLICY_REMOVE,
                                )
                            } catch (e: Exception) {
                                Timber.w(e, "UIDT download notification update failed")
                                queueRunner.stopActive(
                                    e.message ?: "Download notification update failed"
                                )
                            }
                        }
                    Timber.i(
                        "UIDT download queue finished: completed=${result.completed}, " +
                            "failed=${result.failed}, paused=${result.paused}, stopped=${result.stopped}"
                    )
                    finishIfCurrent(
                        runId,
                        params,
                        wantsReschedule = result.rescheduleCurrentJob,
                    )
                } catch (e: CancellationException) {
                    val reason = "UIDT job cancelled"
                    Timber.i("UIDT download queue coroutine cancelled; requeueing active download")
                    queueRunner.requestSystemRequeue(reason)
                    queueRunner.stopActive(reason)
                } catch (e: Exception) {
                    Timber.e(e, "UIDT download queue failed")
                    queueRunner.stopActive(e.message ?: "UIDT queue interrupted")
                    finishIfCurrent(runId, params, wantsReschedule = false)
                }
            }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val stopPolicy = UidtJobStopPolicy.decide(params.stopReason)
        val reason = "UIDT job stopped: ${params.stopReason}"
        val stopDecision = lifecycle.stop()
        if (stopDecision.shouldStopRunner) {
            when (stopPolicy.disposition) {
                UidtJobStopDisposition.PAUSE_ACTIVE ->
                    queueRunner.requestPauseActive(
                        reason = reason,
                        force = true,
                    )
                UidtJobStopDisposition.APP_OWNED_REQUEUE ->
                    queueRunner.requestPolicyRequeue(
                        reason = reason,
                        scheduleAfterStop =
                            stopPolicy.scheduleAfterStop
                                ?: DownloadQueueScheduleTrigger.VISIBLE_LIVENESS,
                    )
                UidtJobStopDisposition.SYSTEM_OWNED_REQUEUE -> queueRunner.requestSystemRequeue(reason)
            }
            serviceScope?.launch(start = CoroutineStart.UNDISPATCHED) {
                queueRunner.stopActive(reason)
            }
            runningJob?.cancel()
        }
        uidtNetworkSession.clearJob()
        return stopPolicy.shouldAskJobSchedulerToReschedule
    }

    override fun onNetworkChanged(params: JobParameters) {
        if (!lifecycle.hasActiveRun()) return
        serviceScope?.launch {
            queueRunner.onUidtNetworkChanged(params.network)
        }
    }

    private fun finishIfCurrent(
        runId: Long,
        params: JobParameters,
        wantsReschedule: Boolean,
    ) {
        mainHandler.post {
            lifecycle.finishIfCurrent(runId) {
                uidtNetworkSession.clearJob()
                jobFinished(params, wantsReschedule)
            }
        }
    }

    override fun onDestroy() {
        lifecycle.destroy()
        runningJob?.cancel()
        serviceScope?.cancel()
        uidtNetworkSession.clearJob()
        getSystemService(NotificationManager::class.java)
            .cancel(DownloadQueueNotificationFactory.NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun startBackendStartFailureCleanup(
        runId: Long,
        params: JobParameters,
        reason: String,
    ): Boolean {
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lifecycle.markCleanupWork()
        runningJob =
            serviceScope?.launch(start = CoroutineStart.UNDISPATCHED) {
                val cleanupResult =
                    UidtJobCleanup.run {
                        backendStartFailureHandler.record(reason)
                    }
                if (cleanupResult is UidtJobCleanupResult.Failed) {
                    Timber.e(cleanupResult.error, "Failed to persist UIDT backend start failure")
                }
                finishIfCurrent(runId, params, cleanupResult.wantsReschedule)
            }
        return true
    }

    private fun startRequeueAllActiveCleanup(
        runId: Long,
        params: JobParameters,
        reason: String,
    ): Boolean {
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lifecycle.markCleanupWork()
        runningJob =
            serviceScope?.launch(start = CoroutineStart.UNDISPATCHED) {
                val cleanupResult =
                    UidtJobCleanup.run {
                        stateStore.requeueAllActiveForTransientStop(reason = reason)
                    }
                if (cleanupResult is UidtJobCleanupResult.Failed) {
                    Timber.e(cleanupResult.error, "Failed to requeue active rows for UIDT cleanup")
                }
                finishIfCurrent(runId, params, wantsReschedule = true)
            }
        return true
    }

}
