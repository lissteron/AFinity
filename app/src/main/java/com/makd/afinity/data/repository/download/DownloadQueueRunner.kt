package com.makd.afinity.data.repository.download

import android.net.Network
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class DownloadQueueRunner
@Inject
constructor(
    private val stateStore: DownloadQueueStateStore,
    private val transferRunner: MediaDownloadTransferRunner,
    private val uidtNetworkSession: UidtNetworkSession,
    private val scheduler: DownloadQueueScheduler,
) {
    private val running = AtomicBoolean(false)
    private val stopState = DownloadQueueStopState()
    @Volatile private var activeClaim: ActiveClaim? = null

    suspend fun run(
        requiredNetwork: Network? = null,
        backendRunId: UUID = UUID.randomUUID(),
        backendKind: String = DownloadQueueBackends.WORK_MANAGER,
        progressObserver: suspend (MediaDownloadTransferRunner.DownloadProgress) -> Unit = {},
    ): QueueRunResult {
        if (!running.compareAndSet(false, true)) {
            Timber.d("Download queue runner already active; duplicate start exits")
            return QueueRunResult(completed = 0, failed = 0, paused = 0, stopped = false)
        }
        stopState.clear()
        var completed = 0
        var failed = 0
        var paused = 0
        try {
            val reconciled =
                stateStore.reconcileOrphanedActiveClaims(
                    backendRunId = backendRunId,
                    reason = "Interrupted download recovered to paused queue state",
                )
            if (reconciled > 0) {
                Timber.w("Reconciled $reconciled orphaned DOWNLOADING media rows to PAUSED")
            }

            while (stopState.current() == null) {
                currentCoroutineContext().ensureActive()
                val claimed =
                    stateStore.claimOldestQueuedDownload(
                        backendRunId = backendRunId,
                        backendKind = backendKind,
                    ) ?: break
                val activeClaimId = claimed.activeClaimId
                val claimedBackendRunId = claimed.activeBackendRunId
                if (activeClaimId == null || claimedBackendRunId != backendRunId) {
                    Timber.w("Claimed media download ${claimed.id} without persisted queue lease")
                    stateStore.pauseActiveDownload(
                        claimed.id,
                        "Download queue lease was not persisted",
                    )
                    continue
                }
                activeClaim = ActiveClaim(claimed.id, activeClaimId, backendRunId)
                Timber.i("Claimed queued media download ${claimed.id} (${claimed.itemName})")
                try {
                    when (
                        transferRunner.run(
                            claimedDownload = claimed,
                            requiredNetwork = requiredNetwork,
                            stopRequest = { stopState.current() },
                            progressObserver = progressObserver,
                        )
                    ) {
                        is MediaDownloadTransferRunner.TransferResult.Completed -> completed += 1
                        is MediaDownloadTransferRunner.TransferResult.Failed -> failed += 1
                        is MediaDownloadTransferRunner.TransferResult.Paused -> paused += 1
                    }
                } finally {
                    activeClaim = null
                }
            }

            return QueueRunResult(
                completed = completed,
                failed = failed,
                paused = paused,
                stopped = stopState.current() != null,
            )
        } finally {
            val scheduleAfterStop = stopState.current()?.scheduleAfterStop
            activeClaim = null
            stopState.clear()
            running.set(false)
            if (scheduleAfterStop != null) {
                withContext(NonCancellable) {
                    scheduler.scheduleQueue(scheduleAfterStop)
                }
            }
        }
    }

    suspend fun stopActive(reason: String) {
        requestPauseActive(reason)
        activeClaim?.let { claim ->
            stateStore.pauseOwned(
                downloadId = claim.downloadId,
                activeClaimId = claim.activeClaimId,
                backendRunId = claim.backendRunId,
                reason = reason,
            )
        }
    }

    fun requestPauseActive(
        reason: String,
        force: Boolean = false,
    ): Boolean = stopState.requestPause(reason = reason, force = force)

    fun requestPolicyRequeue(
        reason: String,
        scheduleAfterStop: DownloadQueueScheduleTrigger,
    ): DownloadQueuePolicyRequeueRequestResult {
        val accepted = stopState.requestPolicyRequeue(reason, scheduleAfterStop)
        if (!accepted) {
            return DownloadQueuePolicyRequeueRequestResult.ExistingStopRequestWins
        }
        return if (running.get()) {
            DownloadQueuePolicyRequeueRequestResult.RunnerWillRequeueAndReschedule
        } else {
            DownloadQueuePolicyRequeueRequestResult.NoRunningRunner
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun onUidtNetworkChanged(network: Network?) {
        val result = uidtNetworkSession.onNetworkChanged(network)
        if (result is UidtNetworkSession.NetworkChangeResult.RequiredNetworkMissing) {
            stopActive("UIDT required network is unavailable")
        }
    }

    data class QueueRunResult(
        val completed: Int,
        val failed: Int,
        val paused: Int,
        val stopped: Boolean,
    )

    private data class ActiveClaim(
        val downloadId: UUID,
        val activeClaimId: UUID,
        val backendRunId: UUID,
    )
}
