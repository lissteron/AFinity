package com.makd.afinity.data.repository.download

import com.makd.afinity.data.repository.DatabaseRepository
import com.makd.afinity.data.repository.SecurePreferencesRepository
import com.makd.afinity.data.repository.ServerUserToken
import com.makd.afinity.di.DownloadClient
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin

@Singleton
class SessionRestoreResolver
@Inject
constructor(
    private val databaseRepository: DatabaseRepository,
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

        val apiClient =
            jellyfin.createApi(baseUrl = tokenInfo.serverUrl).also {
                it.update(baseUrl = tokenInfo.serverUrl, accessToken = token)
            }
        return SessionRestoreResult.Restored(
            RestoredDownloadSession(
                serverId = serverId,
                userId = userId,
                serverUrl = tokenInfo.serverUrl,
                accessToken = token,
                apiClient = apiClient,
                okHttpClient = downloadClient,
            )
        )
    }

    companion object {
        fun findExactToken(
            tokens: List<ServerUserToken>,
            serverId: String,
            userId: UUID,
        ): ServerUserToken? = tokens.firstOrNull { it.serverId == serverId && it.userId == userId }
    }
}
