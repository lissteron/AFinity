package com.makd.afinity.data.repository.download

import java.util.UUID
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient

data class RestoredDownloadSession(
    val serverId: String,
    val userId: UUID,
    val serverUrl: String,
    val accessToken: String,
    val apiClient: ApiClient,
    val okHttpClient: OkHttpClient,
    val networkGeneration: Long? = null,
)

sealed class SessionRestoreResult {
    data class Restored(val session: RestoredDownloadSession) : SessionRestoreResult()

    data class Failed(val message: String) : SessionRestoreResult()

    data class Paused(val message: String) : SessionRestoreResult()
}
