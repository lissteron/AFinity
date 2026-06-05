package com.makd.afinity.data.manager

import com.makd.afinity.data.repository.server.AddressResolutionResult
import com.makd.afinity.data.repository.server.ServerAddressResolver
import com.makd.afinity.data.repository.server.ServerRepository
import com.makd.afinity.util.NetworkConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class ServerReachabilityState {
    UNKNOWN,
    CHECKING,
    REACHABLE,
    UNREACHABLE,
}

@Singleton
class ServerReachabilityMonitor
@Inject
constructor(
    private val networkConnectivityMonitor: NetworkConnectivityMonitor,
    private val sessionManager: SessionManager,
    private val serverAddressResolver: ServerAddressResolver,
    private val serverRepository: ServerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ServerReachabilityState.UNKNOWN)
    val state: StateFlow<ServerReachabilityState> = _state.asStateFlow()

    private val immediateProbeRequests =
        MutableSharedFlow<String>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private var monitorJob: Job? = null
    private var consecutiveFailures = 0

    init {
        scope.launch {
            combine(
                    networkConnectivityMonitor.isNetworkAvailable,
                    sessionManager.currentSession,
                ) { networkAvailable, session ->
                    networkAvailable to session
                }
                .collect { (networkAvailable, session) ->
                    restartMonitor(networkAvailable, session)
                }
        }
    }

    private suspend fun restartMonitor(networkAvailable: Boolean, session: Session?) {
        monitorJob?.cancelAndJoin()
        monitorJob = null

        if (session == null) {
            consecutiveFailures = 0
            _state.value = ServerReachabilityState.UNKNOWN
            return
        }

        if (!networkAvailable) {
            consecutiveFailures = 0
            _state.value = ServerReachabilityState.UNKNOWN
            return
        }

        monitorJob =
            scope.launch {
                var nextDelayMs = 0L
                while (currentCoroutineContext().isActive) {
                    if (nextDelayMs > 0L) {
                        val reason =
                            withTimeoutOrNull(nextDelayMs) { immediateProbeRequests.first() }
                        if (reason != null) {
                            Timber.d("Server reachability: foreground probe requested ($reason)")
                        }
                    }

                    val reachable = probe(session)
                    nextDelayMs =
                        if (reachable) {
                            REACHABLE_CHECK_INTERVAL_MS
                        } else {
                            retryDelayMs(consecutiveFailures)
                        }
                }
            }
    }

    fun probeNow(reason: String) {
        if (!networkConnectivityMonitor.isCurrentlyConnected()) return
        if (sessionManager.currentSession.value == null) return

        Timber.d("Server reachability: immediate probe requested ($reason)")
        immediateProbeRequests.tryEmit(reason)
    }

    private suspend fun probe(session: Session): Boolean {
        _state.value = ServerReachabilityState.CHECKING

        return when (val result = serverAddressResolver.resolveAddress(session.serverId)) {
            is AddressResolutionResult.Success -> {
                consecutiveFailures = 0
                sessionManager.setServerReachable(true)
                _state.value = ServerReachabilityState.REACHABLE

                if (result.address != sessionManager.currentSession.value?.serverUrl) {
                    Timber.d("Server reachability: switching active address to ${result.address}")
                    serverRepository.setBaseUrl(result.address)
                    sessionManager.updateSessionUrl(result.address)
                }

                true
            }

            is AddressResolutionResult.AllFailed -> {
                consecutiveFailures += 1

                if (consecutiveFailures >= FAILURE_THRESHOLD) {
                    sessionManager.setServerReachable(false)
                    _state.value = ServerReachabilityState.UNREACHABLE
                } else {
                    _state.value = ServerReachabilityState.UNKNOWN
                }

                Timber.w(
                    "Server reachability: probe failed " +
                        "($consecutiveFailures/$FAILURE_THRESHOLD), " +
                        "attempted=${result.attemptedAddresses}"
                )

                false
            }
        }
    }

    private fun retryDelayMs(failureCount: Int): Long =
        when {
            failureCount <= 1 -> 5_000L
            failureCount == 2 -> 15_000L
            failureCount == 3 -> 30_000L
            else -> 60_000L
        }

    companion object {
        private const val FAILURE_THRESHOLD = 2
        private const val REACHABLE_CHECK_INTERVAL_MS = 60_000L
    }
}
