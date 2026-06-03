package com.makd.afinity.data.repository.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.makd.afinity.data.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class DownloadQueuePolicyCoordinator
@Inject
constructor(
    @ApplicationContext context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val stateStore: DownloadQueueStateStore,
    private val scheduler: DownloadQueueScheduler,
    private val visibilityTracker: AppVisibilityTracker,
    private val queueRunner: DownloadQueueRunner,
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val policyDecider = DownloadQueuePolicyDecider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Suppress("DEPRECATION")
    private val storagePolicyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_DEVICE_STORAGE_LOW ->
                        scope.launch { handleStorageNotLowPolicyChanged(storageNotLow = false) }
                    Intent.ACTION_DEVICE_STORAGE_OK ->
                        scope.launch { handleStorageNotLowPolicyChanged(storageNotLow = true) }
                }
            }
        }
    @Volatile private var started = false
    @Volatile private var storageReceiverRegistered = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            preferencesRepository
                .getDownloadWifiOnlyFlow()
                .distinctUntilChanged()
                .drop(1)
                .collect { wifiOnly ->
                    handleWifiPolicyChanged(wifiOnly)
                }
        }
        scope.launch {
            preferencesRepository
                .getDownloadStorageLocationIdFlow()
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    handleStorageLocationChanged()
                }
        }
        registerStoragePolicyReceiver()
    }

    private suspend fun handleWifiPolicyChanged(wifiOnly: Boolean) {
        Timber.d("Download queue Wi-Fi policy changed: wifiOnly=$wifiOnly")
        val snapshot = stateStore.snapshot()
        val decision =
            policyDecider.decideWifiPolicyChange(
                wifiOnly = wifiOnly,
                hasActiveDownload = snapshot.activeDownloadCount > 0,
                currentNetworkUnmetered = isCurrentNetworkUnmetered(),
            )

        when (decision) {
            DownloadQueuePolicyDecision.ActiveCanContinue -> {
                Timber.d("Active download satisfies new Wi-Fi policy; keeping current claim")
                return
            }
            is DownloadQueuePolicyDecision.PauseActive -> {
                stopActiveForPolicyRequeue(snapshot, decision.reason)
                return
            }
            DownloadQueuePolicyDecision.ReschedulePending -> scheduler.cancelQueue()
        }

        scheduleAfterPolicyChange()
    }

    private suspend fun handleStorageNotLowPolicyChanged(storageNotLow: Boolean) {
        Timber.d("Download queue storage-not-low policy changed: storageNotLow=$storageNotLow")
        val snapshot = stateStore.snapshot()
        val decision =
            policyDecider.decideStorageNotLowPolicyChange(
                storageNotLow = storageNotLow,
                hasActiveDownload = snapshot.activeDownloadCount > 0,
            )
        applyPolicyDecision(snapshot, decision)
    }

    private suspend fun handleStorageLocationChanged() {
        Timber.d("Download storage location changed")
        val snapshot = stateStore.snapshot()
        val decision = policyDecider.decideStorageLocationChange(snapshot.activeDownloadCount > 0)
        applyPolicyDecision(snapshot, decision)
    }

    private suspend fun applyPolicyDecision(
        snapshot: DownloadQueueSnapshot,
        decision: DownloadQueuePolicyDecision,
    ) {
        when (decision) {
            DownloadQueuePolicyDecision.ActiveCanContinue -> {
                Timber.d("Active download satisfies changed policy; keeping current claim")
                return
            }
            is DownloadQueuePolicyDecision.PauseActive -> {
                stopActiveForPolicyRequeue(snapshot, decision.reason)
                return
            }
            DownloadQueuePolicyDecision.ReschedulePending -> scheduler.cancelQueue()
        }
        scheduleAfterPolicyChange()
    }

    private suspend fun stopActiveForPolicyRequeue(
        snapshot: DownloadQueueSnapshot,
        reason: String,
    ) {
        val trigger = scheduleTriggerForCurrentVisibility()
        val requestResult = queueRunner.requestPolicyRequeue(reason, trigger)
        scheduler.cancelQueue()
        when (requestResult) {
            DownloadQueuePolicyRequeueRequestResult.RunnerWillRequeueAndReschedule,
            DownloadQueuePolicyRequeueRequestResult.ExistingStopRequestWins -> return
            DownloadQueuePolicyRequeueRequestResult.NoRunningRunner -> Unit
        }

        val now = System.currentTimeMillis()
        snapshot.activeDownloads.forEach { download ->
            val activeClaimId = download.activeClaimId
            val activeBackendRunId = download.activeBackendRunId
            if (activeClaimId == null || activeBackendRunId == null) {
                stateStore.pauseActiveDownload(download.id, reason, now)
            } else {
                stateStore.requeueOwned(
                    downloadId = download.id,
                    activeClaimId = activeClaimId,
                    backendRunId = activeBackendRunId,
                    reason = reason,
                    now = now,
                )
            }
        }
        scheduler.scheduleQueue(trigger)
    }

    private suspend fun scheduleAfterPolicyChange() {
        scheduler.scheduleQueue(scheduleTriggerForCurrentVisibility())
    }

    private fun scheduleTriggerForCurrentVisibility(): DownloadQueueScheduleTrigger {
        return if (visibilityTracker.isVisibleNow()) {
            DownloadQueueScheduleTrigger.VISIBLE_LIVENESS
        } else {
            DownloadQueueScheduleTrigger.PASSIVE_BACKGROUND
        }
    }

    private fun isCurrentNetworkUnmetered(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    @Suppress("DEPRECATION")
    private fun registerStoragePolicyReceiver() {
        if (storageReceiverRegistered) return
        storageReceiverRegistered = true
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
                addAction(Intent.ACTION_DEVICE_STORAGE_OK)
            }
        ContextCompat.registerReceiver(
            appContext,
            storagePolicyReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
