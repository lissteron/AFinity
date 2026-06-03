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

@Singleton
class OfflineModeManager
@Inject
constructor(
    private val preferencesRepository: PreferencesRepository,
    private val networkConnectivityMonitor: NetworkConnectivityMonitor,
    private val sessionManager: SessionManager,
    private val serverReachabilityMonitor: ServerReachabilityMonitor,
) {
    val offlineReason =
        combine(
            preferencesRepository.getOfflineModeFlow(),
            networkConnectivityMonitor.isNetworkAvailable,
            sessionManager.isServerReachable,
        ) { manualOfflineMode, isNetworkAvailable, isServerReachable ->
            val reason =
                when {
                    manualOfflineMode -> OfflineModeReason.MANUAL
                    !isNetworkAvailable -> OfflineModeReason.NO_NETWORK
                    !isServerReachable -> OfflineModeReason.SERVER_UNREACHABLE
                    else -> OfflineModeReason.ONLINE
                }

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

    suspend fun isCurrentlyOffline(): Boolean {
        val manualOfflineMode = preferencesRepository.getOfflineMode()
        val isNetworkAvailable = networkConnectivityMonitor.isCurrentlyConnected()
        val isServerReachable = sessionManager.isServerReachable.value

        return manualOfflineMode || !isNetworkAvailable || !isServerReachable
    }
}
