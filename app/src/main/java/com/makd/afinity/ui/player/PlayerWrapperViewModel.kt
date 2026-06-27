package com.makd.afinity.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.makd.afinity.R
import com.makd.afinity.data.local.LocalLibraryMediaRepository
import com.makd.afinity.data.local.LocalLibraryVisibilityContext
import com.makd.afinity.data.manager.OfflineModeManager
import com.makd.afinity.data.manager.SessionManager
import com.makd.afinity.data.models.livetv.AfinityChannel
import com.makd.afinity.data.models.livetv.ChannelType
import com.makd.afinity.data.models.media.AfinityImages
import com.makd.afinity.data.models.media.AfinityItem
import com.makd.afinity.data.models.media.toAfinityEpisode
import com.makd.afinity.data.repository.DatabaseRepository
import com.makd.afinity.data.repository.FieldSets
import com.makd.afinity.data.repository.KidModeRepository
import com.makd.afinity.data.repository.PreferencesRepository
import com.makd.afinity.data.models.extensions.toAfinityItem
import com.makd.afinity.data.repository.media.MediaRepository
import com.makd.afinity.data.repository.livetv.LiveTvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import org.jellyfin.sdk.model.api.BaseItemKind

@HiltViewModel
class PlayerWrapperViewModel
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val databaseRepository: DatabaseRepository,
    private val sessionManager: SessionManager,
    private val liveTvRepository: LiveTvRepository,
    private val offlineModeManager: OfflineModeManager,
    private val kidModeRepository: KidModeRepository,
    private val preferencesRepository: PreferencesRepository,
    private val localLibraryMediaRepository: LocalLibraryMediaRepository,
) : ViewModel() {

    private val _item = MutableStateFlow<AfinityItem?>(null)
    val item: StateFlow<AfinityItem?> = _item.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _liveStreamUrl = MutableStateFlow<String?>(null)
    val liveStreamUrl: StateFlow<String?> = _liveStreamUrl.asStateFlow()

    private val _streamError = MutableStateFlow<String?>(null)
    val streamError: StateFlow<String?> = _streamError.asStateFlow()

    fun loadLiveChannel(channelId: UUID, channelName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _streamError.value = null
            Timber.d("PlayerWrapperViewModel: Loading live channel $channelId ($channelName)")

            val placeholderChannel =
                AfinityChannel(
                    id = channelId,
                    name = channelName,
                    images = AfinityImages(),
                    channelNumber = null,
                    channelType = ChannelType.TV,
                    serviceName = null,
                )
            _item.value = placeholderChannel

            try {
                val channelDeferred =
                    viewModelScope.async {
                        try {
                            liveTvRepository.getChannel(channelId)
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to load channel details, using placeholder")
                            null
                        }
                    }

                val streamUrlDeferred =
                    viewModelScope.async { liveTvRepository.getChannelStreamUrl(channelId) }

                val channel = channelDeferred.await()
                if (channel != null) {
                    Timber.d(
                        "PlayerWrapperViewModel: Loaded live channel from API: ${channel.name}"
                    )
                    _item.value = channel
                }

                val streamUrl = streamUrlDeferred.await()
                if (streamUrl != null) {
                    Timber.d("PlayerWrapperViewModel: Got stream URL: $streamUrl")
                    _liveStreamUrl.value = streamUrl
                } else {
                    Timber.e("PlayerWrapperViewModel: Failed to get stream URL")
                    _streamError.value = context.getString(R.string.error_get_stream_url)
                }
            } catch (e: Exception) {
                Timber.e(e, "PlayerWrapperViewModel: Failed to load live channel")
                _streamError.value = context.getString(R.string.error_load_channel_fmt, e.message)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadItem(
        itemId: UUID,
        fallbackSeriesId: UUID? = null,
        fallbackSeasonId: UUID? = null,
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            Timber.d(
                "PlayerWrapperViewModel: Loading item $itemId, fallbackSeriesId=$fallbackSeriesId, fallbackSeasonId=$fallbackSeasonId"
            )
            try {
                var loadedItem: AfinityItem? = null

                val profileUserId = currentProfileUserId()
                val userId =
                    profileUserId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: sessionManager.currentSession.value?.userId

                if (offlineModeManager.isCurrentlyOffline()) {
                    loadedItem = loadLocalCatalogItem(itemId, profileUserId)
                    if (loadedItem != null) {
                        Timber.d(
                            "PlayerWrapperViewModel: Loaded item from local catalog: ${loadedItem.name}"
                        )
                    }
                }

                if (userId != null && loadedItem == null) {
                    Timber.d("PlayerWrapperViewModel: Using userId: $userId")

                    try {
                        loadedItem =
                            databaseRepository.getMovie(itemId, userId)
                                ?: databaseRepository.getEpisode(itemId, userId)

                        if (loadedItem != null) {
                            Timber.d(
                                "PlayerWrapperViewModel: Loaded item from database: ${loadedItem.name}"
                            )
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "PlayerWrapperViewModel: Database lookup failed")
                    }
                } else {
                    Timber.e("PlayerWrapperViewModel: Could not determine userId")
                }

                if (loadedItem == null && !offlineModeManager.isCurrentlyOffline()) {
                    Timber.d("PlayerWrapperViewModel: Trying to load from API")
                    try {
                        loadedItem =
                            loadRemoteItem(
                                itemId = itemId,
                                fallbackSeriesId = fallbackSeriesId,
                                fallbackSeasonId = fallbackSeasonId,
                            )
                        if (loadedItem != null) {
                            Timber.d(
                                "PlayerWrapperViewModel: Loaded item from API: ${loadedItem.name}"
                            )
                        } else {
                            Timber.w("PlayerWrapperViewModel: Item not found via API")
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "PlayerWrapperViewModel: API load failed (likely offline)")
                    }
                }

                if (loadedItem == null) {
                    loadedItem = loadLocalCatalogItem(itemId, profileUserId)
                    if (loadedItem != null) {
                        Timber.d(
                            "PlayerWrapperViewModel: Loaded item from local catalog after cache/API miss: ${loadedItem.name}"
                        )
                    }
                }

                if (loadedItem == null) {
                    Timber.e(
                        "PlayerWrapperViewModel: Failed to load item from local catalog, database, and API"
                    )
                }

                _item.value = loadedItem
            } catch (e: Exception) {
                Timber.e(e, "PlayerWrapperViewModel: Critical failure loading item $itemId")
                _item.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadRemoteItem(
        itemId: UUID,
        fallbackSeriesId: UUID?,
        fallbackSeasonId: UUID?,
    ): AfinityItem? {
        val baseItem = mediaRepository.getItem(itemId, fields = FieldSets.ITEM_DETAIL) ?: return null
        return when (baseItem.type) {
            BaseItemKind.EPISODE ->
                baseItem.toAfinityEpisode(
                    baseUrl = mediaRepository.getBaseUrl(),
                    database = null,
                    fallbackSeriesId = fallbackSeriesId,
                    fallbackSeasonId = fallbackSeasonId,
                )
            else -> baseItem.toAfinityItem(mediaRepository.getBaseUrl())
        }
    }

    private suspend fun loadLocalCatalogItem(
        itemId: UUID,
        profileUserId: String?,
    ): AfinityItem? {
        val capability = kidModeRepository.policy.value
        return localLibraryMediaRepository.resolvePlayableItem(
            itemId = itemId,
            profileUserId = profileUserId,
            visibilityContext =
                LocalLibraryVisibilityContext(
                    currentUserId = profileUserId,
                    kidModeEnabled = capability.isKidModeEnabled,
                    parentUnlocked = capability.isParentUnlocked,
                ),
        )
    }

    private suspend fun currentProfileUserId(): String? =
        sessionManager.currentSession.value?.userId?.toString()
            ?: preferencesRepository.getCurrentUserId()
}
