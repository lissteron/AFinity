package com.makd.afinity.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.makd.afinity.data.manager.OfflineModeManager
import com.makd.afinity.data.manager.SessionManager
import com.makd.afinity.data.models.media.AfinityItem
import com.makd.afinity.data.models.media.AfinityShow
import com.makd.afinity.data.repository.AppDataRepository
import com.makd.afinity.data.repository.AudiobookshelfRepository
import com.makd.afinity.data.repository.JellyfinRepository
import com.makd.afinity.data.repository.JellyseerrRepository
import com.makd.afinity.data.repository.KidModeRepository
import com.makd.afinity.data.repository.auth.AuthRepository
import com.makd.afinity.data.repository.media.MediaRepository
import com.makd.afinity.data.repository.livetv.LiveTvRepository
import com.makd.afinity.data.repository.watchlist.WatchlistRepository
import com.makd.afinity.player.audiobookshelf.AudiobookshelfPlaybackManager
import com.makd.afinity.player.audiobookshelf.AudiobookshelfPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainNavigationViewModel
@Inject
constructor(
    private val appDataRepository: AppDataRepository,
    private val authRepository: AuthRepository,
    private val jellyfinRepository: JellyfinRepository,
    private val mediaRepository: MediaRepository,
    val watchlistRepository: WatchlistRepository,
    val jellyseerrRepository: JellyseerrRepository,
    val audiobookshelfRepository: AudiobookshelfRepository,
    val audiobookshelfPlayer: AudiobookshelfPlayer,
    val audiobookshelfPlaybackManager: AudiobookshelfPlaybackManager,
    private val liveTvRepository: LiveTvRepository,
    private val offlineModeManager: OfflineModeManager,
    private val sessionManager: SessionManager,
    val kidModeRepository: KidModeRepository,
) : ViewModel() {
    private val _hasLiveTvAccess = MutableStateFlow(true)
    val hasLiveTvAccess = _hasLiveTvAccess.asStateFlow()

    val appLoadingState =
        combine(
                appDataRepository.isInitialDataLoaded,
                appDataRepository.loadingProgress,
                appDataRepository.loadingPhase,
            ) { isLoaded, progress, phase ->
                AppLoadingState(
                    isLoading = !isLoaded,
                    loadingProgress = progress,
                    loadingPhase = phase,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = AppLoadingState(isLoading = true),
            )

    val capabilityPolicy = kidModeRepository.policy

    init {
        observeAuthAndLoadData()
        refreshServerInfo()
        observeDataLoaded()
    }

    private fun observeDataLoaded() {
        viewModelScope.launch {
            appDataRepository.isInitialDataLoaded.collect { isLoaded ->
                if (isLoaded) {
                    checkLiveTvAccess()
                }
            }
        }
    }

    private fun observeAuthAndLoadData() {
        viewModelScope.launch {
            val initialAuthState = authRepository.isAuthenticated.value

            if (initialAuthState) {
                loadAppData()
            }

            var previousAuthState = initialAuthState

            authRepository.isAuthenticated.collect { isAuthenticated ->
                if (isAuthenticated && !previousAuthState) {
                    Timber.d("Fresh login detected")
                    _hasLiveTvAccess.value = false
                    loadAppData()
                } else if (!isAuthenticated) {
                    _hasLiveTvAccess.value = false
                }

                previousAuthState = isAuthenticated
            }
        }
    }

    private fun checkLiveTvAccess() {
        viewModelScope.launch {
            try {
                if (offlineModeManager.isCurrentlyOffline()) {
                    Timber.d("Skipping Live TV access check in offline mode")
                    _hasLiveTvAccess.value = false
                    return@launch
                }

                val hasAccess = liveTvRepository.hasLiveTvAccess()
                Timber.d("Live TV access check result: $hasAccess")
                _hasLiveTvAccess.value = hasAccess
            } catch (e: Exception) {
                Timber.e(e, "Failed to check Live TV access")
                _hasLiveTvAccess.value = true
            }
        }
    }

    private fun refreshServerInfo() {
        viewModelScope.launch {
            try {
                val isOffline = offlineModeManager.isCurrentlyOffline()
                if (isOffline || !sessionManager.isServerReachable.value) {
                    Timber.d("Device is offline or server unreachable, skipping server info refresh")
                    return@launch
                }

                jellyfinRepository.refreshServerInfo()
                Timber.d("Server info refreshed on app start")
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh server info on app start")
            }
        }
    }

    private fun loadAppData() {
        viewModelScope.launch {
            val isOffline = offlineModeManager.isCurrentlyOffline()

            if (isOffline) {
                Timber.d("Device is offline, skipping initial data load")
                appDataRepository.skipInitialDataLoad()
                return@launch
            }

            if (!sessionManager.isServerReachable.value) {
                Timber.d("Server unreachable (address resolution failed), starting in offline mode")
                appDataRepository.skipInitialDataLoad()
                return@launch
            }

            val maxRetries = 3
            var currentAttempt = 0
            var success = false

            while (currentAttempt < maxRetries && !success) {
                try {
                    currentAttempt++
                    Timber.d(
                        "Attempting to load initial data (Attempt $currentAttempt/$maxRetries)"
                    )

                    appDataRepository.loadInitialData()
                    success = true
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load app data on attempt $currentAttempt")

                    if (currentAttempt < maxRetries) {
                        kotlinx.coroutines.delay(1000L * currentAttempt)
                    }
                }
            }
            if (!success) {
                Timber.e("All $maxRetries attempts failed. Falling back to cached data.")
                appDataRepository.skipInitialDataLoad()
            }
        }
    }

    fun retry() {
        loadAppData()
    }

    suspend fun verifyParentPin(pin: String): Boolean = kidModeRepository.verifyParentPin(pin)

    fun lockParent() {
        kidModeRepository.lockParent()
    }

    suspend fun resolvePlayableItem(item: AfinityItem): AfinityItem? {
        return try {
            if (item is AfinityShow) {
                if (offlineModeManager.isCurrentlyOffline()) {
                    val episodes =
                        item.seasons
                            .sortedBy { it.indexNumber }
                            .flatMap { season -> season.episodes.sortedBy { it.indexNumber } }
                    episodes
                        .firstOrNull { it.playbackPositionTicks > 0 && !it.played }
                        ?: episodes.firstOrNull { !it.played }
                        ?: episodes.firstOrNull()
                } else {
                    val episode = mediaRepository.getEpisodeToPlay(item.id)
                    if (episode == null) {
                        Timber.w("No episode found to play for series: ${item.name}")
                    }
                    episode
                }
            } else {
                item
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve playable item for: ${item.name}")
            null
        }
    }
}

data class AppLoadingState(
    val isLoading: Boolean = false,
    val loadingProgress: Float = 0f,
    val loadingPhase: String = "",
    val error: String? = null,
)
