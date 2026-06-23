package com.makd.afinity.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.makd.afinity.data.local.LocalLibraryMediaRepository
import com.makd.afinity.data.local.LocalLibraryVisibilityContext
import com.makd.afinity.data.manager.OfflineModeManager
import com.makd.afinity.data.manager.SessionManager
import com.makd.afinity.data.models.media.AfinityItem
import com.makd.afinity.data.models.media.AfinitySeason
import com.makd.afinity.data.models.media.AfinityShow
import com.makd.afinity.data.models.media.offlinePlaybackEpisode
import com.makd.afinity.data.repository.AppDataRepository
import com.makd.afinity.data.repository.AudiobookshelfRepository
import com.makd.afinity.data.repository.JellyfinRepository
import com.makd.afinity.data.repository.JellyseerrRepository
import com.makd.afinity.data.repository.KidModeRepository
import com.makd.afinity.data.repository.PreferencesRepository
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
import kotlinx.coroutines.flow.drop
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
    private val preferencesRepository: PreferencesRepository,
    val kidModeRepository: KidModeRepository,
    private val localLibraryMediaRepository: LocalLibraryMediaRepository,
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
                    isLoading = false,
                    isRemoteBootstrapLoading = !isLoaded,
                    loadingProgress = progress,
                    loadingPhase = phase,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue =
                    AppLoadingState(
                        isLoading = false,
                        isRemoteBootstrapLoading = true,
                    ),
            )

    val capabilityPolicy = kidModeRepository.policy

    init {
        observeAuthAndLoadData()
        refreshServerInfo()
        observeDataLoaded()
        observeRemoteContentAvailability()
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

    private fun observeRemoteContentAvailability() {
        viewModelScope.launch {
            offlineModeManager.canLoadRemoteContent.drop(1).collect { canLoadRemoteContent ->
                if (canLoadRemoteContent) {
                    refreshServerInfo()
                    if (authRepository.isAuthenticated.value && appDataRepository.isInitialDataLoaded.value) {
                        checkLiveTvAccess()
                    }
                } else {
                    _hasLiveTvAccess.value = false
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
                if (offlineModeManager.isHardOffline() || !sessionManager.isServerReachable.value) {
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
                val isOffline = offlineModeManager.isHardOffline()
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
            val isOffline = offlineModeManager.isHardOffline()

            if (isOffline) {
                Timber.d("Device is offline, skipping initial data load")
                appDataRepository.skipInitialDataLoad()
                return@launch
            }

            if (!sessionManager.isServerReachable.value) {
                Timber.d("Server unreachable, deferring initial data load and requesting reconnect")
                appDataRepository.skipInitialDataLoad()
                offlineModeManager.requestConnectivityProbe("initial data deferred")
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
                    resolveLocalPlayableItem(item) ?: item.offlinePlaybackEpisode()
                } else {
                    val episode = mediaRepository.getEpisodeToPlay(item.id)
                    if (episode == null) {
                        Timber.w("No episode found to play for series: ${item.name}")
                    }
                    episode
                }
            } else if (item is AfinitySeason) {
                if (offlineModeManager.isCurrentlyOffline()) {
                    resolveLocalPlayableItem(item) ?: item.offlinePlaybackEpisode()
                } else {
                    val episode = mediaRepository.getEpisodeToPlayForSeason(item.id, item.seriesId)
                    if (episode == null) {
                        Timber.w("No episode found to play for season: ${item.name}")
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

    private suspend fun resolveLocalPlayableItem(item: AfinityItem): AfinityItem? {
        val profileUserId =
            authRepository.currentUser.value?.id?.toString()
                ?: sessionManager.currentSession.value?.userId?.toString()
                ?: preferencesRepository.getCurrentUserId()
        val capability = kidModeRepository.policy.value
        return localLibraryMediaRepository.resolvePlayableItem(
            itemId = item.id,
            itemType =
                when (item) {
                    is AfinityShow -> "Series"
                    is AfinitySeason -> "Season"
                    else -> null
                },
            seriesId = (item as? AfinitySeason)?.seriesId,
            profileUserId = profileUserId,
            visibilityContext =
                LocalLibraryVisibilityContext(
                    currentUserId = profileUserId,
                    kidModeEnabled = capability.isKidModeEnabled,
                    parentUnlocked = capability.isParentUnlocked,
                ),
        )
    }
}

data class AppLoadingState(
    val isLoading: Boolean = false,
    val isRemoteBootstrapLoading: Boolean = false,
    val loadingProgress: Float = 0f,
    val loadingPhase: String = "",
    val error: String? = null,
)
