package com.makd.afinity.data.manager

import com.makd.afinity.data.repository.PreferencesRepository
import com.makd.afinity.util.NetworkConnectivityMonitor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class OfflineModeReason {
    ONLINE,
    MANUAL,
    NO_NETWORK,
    SERVER_UNREACHABLE,
}

internal fun resolveOfflineModeReason(
    manualOfflineMode: Boolean,
    isNetworkAvailable: Boolean,
    isServerReachable: Boolean,
): OfflineModeReason =
    when {
        manualOfflineMode -> OfflineModeReason.MANUAL
        !isNetworkAvailable -> OfflineModeReason.NO_NETWORK
        !isServerReachable -> OfflineModeReason.SERVER_UNREACHABLE
        else -> OfflineModeReason.ONLINE
    }

internal fun OfflineModeReason.isHardOfflineReason(): Boolean =
    this == OfflineModeReason.MANUAL || this == OfflineModeReason.NO_NETWORK

internal fun OfflineModeReason.canAttemptRemoteForReason(): Boolean = !isHardOfflineReason()

internal fun OfflineModeReason.canLoadRemoteContentForReason(): Boolean =
    this == OfflineModeReason.ONLINE

@Singleton
class OfflineModeManager
@Inject
constructor(
    private val preferencesRepository: PreferencesRepository,
    private val networkConnectivityMonitor: NetworkConnectivityMonitor,
    private val sessionManager: SessionManager,
    private val serverReachabilityMonitor: ServerReachabilityMonitor,
) {
    val manualOfflineMode = preferencesRepository.getOfflineModeFlow().distinctUntilChanged()

    val offlineReason =
        combine(
            manualOfflineMode,
            networkConnectivityMonitor.isNetworkAvailable,
            sessionManager.isServerReachable,
        ) { manualOfflineMode, isNetworkAvailable, isServerReachable ->
            val reason =
                resolveOfflineModeReason(
                    manualOfflineMode = manualOfflineMode,
                    isNetworkAvailable = isNetworkAvailable,
                    isServerReachable = isServerReachable,
                )

            Timber.d(
                "Offline mode status: manual=$manualOfflineMode, " +
                    "network=$isNetworkAvailable, " +
                    "serverReachable=$isServerReachable, " +
                    "reason=$reason, " +
                    "serverState=${serverReachabilityMonitor.state.value}"
            )
            reason
        }
            .distinctUntilChanged()

    val isOffline = offlineReason.map { it != OfflineModeReason.ONLINE }.distinctUntilChanged()

    val hardOffline =
        offlineReason
            .map { it.isHardOfflineReason() }
            .distinctUntilChanged()

    val isServerUnavailable =
        offlineReason.map { it == OfflineModeReason.SERVER_UNREACHABLE }.distinctUntilChanged()

    val canAttemptRemote =
        offlineReason
            .map { it.canAttemptRemoteForReason() }
            .distinctUntilChanged()

    val canLoadRemoteContent =
        offlineReason.map { it.canLoadRemoteContentForReason() }.distinctUntilChanged()

    suspend fun isCurrentlyOffline(): Boolean {
        val manualOfflineMode = preferencesRepository.getOfflineMode()
        val isNetworkAvailable = networkConnectivityMonitor.isCurrentlyConnected()
        val isServerReachable = sessionManager.isServerReachable.value

        return manualOfflineMode || !isNetworkAvailable || !isServerReachable
    }

    suspend fun isHardOffline(): Boolean {
        val manualOfflineMode = preferencesRepository.getOfflineMode()
        val isNetworkAvailable = networkConnectivityMonitor.isCurrentlyConnected()

        return manualOfflineMode || !isNetworkAvailable
    }

    suspend fun canAttemptRemoteNow(): Boolean = !isHardOffline()

    suspend fun canLoadRemoteContentNow(): Boolean = !isCurrentlyOffline()

    suspend fun requestConnectivityProbe(reason: String) {
        if (!canAttemptRemoteNow()) return
        serverReachabilityMonitor.probeNow(reason)
    }
}
