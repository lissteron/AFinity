package com.makd.afinity.data.repository.download

import com.makd.afinity.data.repository.DatabaseRepository
import com.makd.afinity.data.repository.SecurePreferencesRepository
import com.makd.afinity.data.repository.ServerUserToken
import com.makd.afinity.data.repository.server.AddressResolutionResult
import com.makd.afinity.data.repository.server.ServerAddressResolver
import com.makd.afinity.di.DownloadClient
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.operations.UserApi
import timber.log.Timber

@Singleton
class SessionRestoreResolver
@Inject
constructor(
    private val databaseRepository: DatabaseRepository,
    private val serverAddressResolver: ServerAddressResolver,
    private val securePreferencesRepository: SecurePreferencesRepository,
    private val jellyfin: Jellyfin,
    @param:DownloadClient private val downloadClient: OkHttpClient,
    private val uidtNetworkSession: UidtNetworkSession,
) {

    suspend fun restore(
        serverId: String,
        userId: UUID,
        networkLease: UidtNetworkSession.NetworkLease? = null,
    ): SessionRestoreResult {
        databaseRepository.getServer(serverId)
            ?: return SessionRestoreResult.Failed("Server $serverId is no longer available")

        val token =
            securePreferencesRepository.getServerUserToken(serverId, userId)
                ?: return SessionRestoreResult.Failed(
                    "No saved token for row owner server=$serverId user=$userId"
                )

        val tokenInfo =
            findExactToken(securePreferencesRepository.getAllServerUserTokens(), serverId, userId)
                ?: return SessionRestoreResult.Failed(
                    "No saved server URL for row owner server=$serverId user=$userId"
                )

        if (networkLease != null) {
            val bound =
                uidtNetworkSession.bind(
                    serverUrl = tokenInfo.serverUrl,
                    accessToken = token,
                    lease = networkLease,
                )
                    ?: return SessionRestoreResult.Paused("UIDT required network changed")
            return SessionRestoreResult.Restored(
                RestoredDownloadSession(
                    serverId = serverId,
                    userId = userId,
                    serverUrl = tokenInfo.serverUrl,
                    accessToken = token,
                    apiClient = bound.apiClient,
                    okHttpClient = bound.okHttpClient,
                    networkGeneration = bound.generation,
                )
            )
        }

        val restoredServerUrl = resolveRestoredServerUrl(serverId, tokenInfo.serverUrl, token)
        val apiClient =
            jellyfin.createApi(baseUrl = restoredServerUrl).also {
                it.update(baseUrl = restoredServerUrl, accessToken = token)
            }
        return SessionRestoreResult.Restored(
            RestoredDownloadSession(
                serverId = serverId,
                userId = userId,
                serverUrl = restoredServerUrl,
                accessToken = token,
                apiClient = apiClient,
                okHttpClient = downloadClient,
            )
        )
    }

    private suspend fun resolveRestoredServerUrl(
        serverId: String,
        savedServerUrl: String,
        accessToken: String,
    ): String {
        val result =
            runCatching {
                    serverAddressResolver.resolveAddress(serverId) { address ->
                        canAuthenticateAt(address, accessToken)
                    }
                }
                .onFailure { error ->
                    Timber.w(error, "Failed to resolve server address for restored background session")
                }
                .getOrNull()
        return when (result) {
            is AddressResolutionResult.Success -> result.address
            is AddressResolutionResult.AllFailed -> {
                Timber.w(
                    "All server addresses failed for restored background session; falling back to saved URL: %s",
                    savedServerUrl,
                )
                savedServerUrl
            }
            null -> savedServerUrl
        }
    }

    private suspend fun canAuthenticateAt(address: String, accessToken: String): Boolean =
        try {
            val client =
                jellyfin.createApi(baseUrl = address).also {
                    it.update(baseUrl = address, accessToken = accessToken)
                }
            withTimeoutOrNull(3000L) { UserApi(client).getCurrentUser() }?.content != null
        } catch (_: Exception) {
            false
        }

    companion object {
        fun findExactToken(
            tokens: List<ServerUserToken>,
            serverId: String,
            userId: UUID,
        ): ServerUserToken? = tokens.firstOrNull { it.serverId == serverId && it.userId == userId }
    }
}
