package com.makd.afinity.data.repository.download

import android.content.Context
import android.net.Network
import android.os.Build
import androidx.annotation.RequiresApi
import com.makd.afinity.di.DownloadClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import timber.log.Timber

@Singleton
class UidtNetworkSession
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val clientInfo: ClientInfo,
    private val deviceInfo: DeviceInfo,
    @param:DownloadClient private val baseDownloadClient: OkHttpClient,
) {
    private val lock = Any()

    @Volatile private var currentNetwork: Network? = null
    @Volatile private var activeClient: OkHttpClient? = null
    @Volatile private var generation: Long = 0L

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun startJob(requiredNetwork: Network): NetworkLease =
        synchronized(lock) {
            activeClient?.dispatcher?.cancelAll()
            currentNetwork = requiredNetwork
            generation += 1
            NetworkLease(requiredNetwork, generation)
        }

    fun currentLease(): NetworkLease? =
        synchronized(lock) { currentNetwork?.let { NetworkLease(it, generation) } }

    fun clearJob() {
        synchronized(lock) {
            activeClient?.dispatcher?.cancelAll()
            currentNetwork = null
            generation += 1
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun bind(
        serverUrl: String,
        accessToken: String,
        lease: NetworkLease?,
    ): BoundClient? {
        val activeLease = lease ?: return null
        synchronized(lock) {
            if (currentNetwork != activeLease.network || generation != activeLease.generation) {
                return null
            }
        }
        val network = activeLease.network
        val networkClient = buildNetworkBoundOkHttpClient(network)
        val jellyfin =
            createJellyfin {
                this.context = this@UidtNetworkSession.context
                this.clientInfo = this@UidtNetworkSession.clientInfo
                this.deviceInfo = this@UidtNetworkSession.deviceInfo
                val factory = OkHttpFactory(base = networkClient)
                this.apiClientFactory = factory
                this.socketConnectionFactory = factory
            }
        val apiClient = jellyfin.createApi(baseUrl = serverUrl).also { it.update(accessToken = accessToken) }

        val boundGeneration =
            synchronized(lock) {
                if (currentNetwork != network || generation != activeLease.generation) {
                    networkClient.dispatcher.cancelAll()
                    return null
                }
                activeClient = networkClient
                generation
            }
        return BoundClient(apiClient, networkClient, network, boundGeneration)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun onNetworkChanged(network: Network?): NetworkChangeResult {
        val newGeneration =
            synchronized(lock) {
                activeClient?.dispatcher?.cancelAll()
                currentNetwork = network
                generation += 1
                generation
            }
        return if (network == null) {
            Timber.w("UIDT required network became unavailable")
            NetworkChangeResult.RequiredNetworkMissing(newGeneration)
        } else {
            Timber.i("UIDT required network changed; rebinding generation=$newGeneration")
            NetworkChangeResult.Rebound(network, newGeneration)
        }
    }

    fun hasNetworkChangedSince(boundGeneration: Long?): Boolean {
        return boundGeneration != null && generation > boundGeneration
    }

    private fun buildNetworkBoundOkHttpClient(network: Network): OkHttpClient {
        val dns = Dns { hostname -> network.getAllByName(hostname).toList() }
        return baseDownloadClient
            .newBuilder()
            .socketFactory(network.socketFactory)
            .dns(dns)
            .build()
    }

    data class BoundClient(
        val apiClient: ApiClient,
        val okHttpClient: OkHttpClient,
        val network: Network,
        val generation: Long,
    )

    data class NetworkLease(val network: Network, val generation: Long)

    sealed class NetworkChangeResult {
        data class Rebound(val network: Network, val generation: Long) : NetworkChangeResult()

        data class RequiredNetworkMissing(val generation: Long) : NetworkChangeResult()
    }
}
