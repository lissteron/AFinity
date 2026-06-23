package com.makd.afinity.ui.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.makd.afinity.R
import com.makd.afinity.data.local.LocalLibraryMediaRepository
import com.makd.afinity.data.local.LocalLibraryVisibilityContext
import com.makd.afinity.data.manager.OfflineModeManager
import com.makd.afinity.data.manager.PlaybackEvent
import com.makd.afinity.data.manager.PlaybackStateManager
import com.makd.afinity.data.manager.SessionManager
import com.makd.afinity.data.models.common.CollectionType
import com.makd.afinity.data.models.common.SortBy
import com.makd.afinity.data.models.media.AfinityEpisode
import com.makd.afinity.data.models.media.AfinityItem
import com.makd.afinity.data.models.media.AfinityMovie
import com.makd.afinity.data.models.media.AfinitySeason
import com.makd.afinity.data.models.media.AfinityShow
import com.makd.afinity.data.repository.AppDataRepository
import com.makd.afinity.data.repository.KidModeRepository
import com.makd.afinity.data.repository.PreferencesRepository
import com.makd.afinity.data.repository.auth.AuthRepository
import com.makd.afinity.data.repository.media.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

enum class FilterType {
    ALL,
    WATCHED,
    UNWATCHED,
    WATCHLIST,
    FAVORITES,
}

@HiltViewModel
class LibraryContentViewModel
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val appDataRepository: AppDataRepository,
    private val playbackStateManager: PlaybackStateManager,
    private val preferencesRepository: PreferencesRepository,
    private val offlineModeManager: OfflineModeManager,
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    private val kidModeRepository: KidModeRepository,
    private val localLibraryMediaRepository: LocalLibraryMediaRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val libraryId: String? = savedStateHandle["libraryId"]
    private val libraryName: String? = savedStateHandle["libraryName"]
    private val studioName: String? = savedStateHandle["studioName"]

    private val _uiState =
        MutableStateFlow(
            LibraryContentUiState(
                libraryId = libraryId?.let { UUID.fromString(it) },
                libraryName = (libraryName ?: studioName ?: "Content").replace("%2F", "/"),
                isStudioMode = studioName != null,
            )
        )
    val uiState: StateFlow<LibraryContentUiState> = _uiState.asStateFlow()

    private val _itemUpdates = MutableStateFlow<Map<UUID, AfinityItem>>(emptyMap())

    private fun applyUpdatesToPagingFlow(
        baseFlow: Flow<PagingData<AfinityItem>>
    ): Flow<PagingData<AfinityItem>> {
        return baseFlow
            .cachedIn(viewModelScope)
            .combine(_itemUpdates) { pagingData, updates ->
                pagingData
                    .map { item -> updates[item.id] ?: item }
                    .filter { item ->
                        when (currentFilter) {
                            FilterType.ALL -> true
                            FilterType.WATCHED -> item.played
                            FilterType.UNWATCHED -> !item.played
                            FilterType.WATCHLIST -> item.liked
                            FilterType.FAVORITES -> item.favorite
                        }
                    }
            }
            .cachedIn(viewModelScope)
    }

    private val _pagingData = MutableStateFlow<Flow<PagingData<AfinityItem>>>(emptyFlow())
    val pagingData: StateFlow<Flow<PagingData<AfinityItem>>> = _pagingData.asStateFlow()

    private var currentSortBy = SortBy.NAME

    private var currentSortDescending = false

    private val _scrollToIndex = MutableStateFlow(-1)
    val scrollToIndex: StateFlow<Int> = _scrollToIndex.asStateFlow()

    private var libraryType: CollectionType? = null

    private var currentFilter = FilterType.ALL

    init {
        viewModelScope.launch {
            combine(
                    appDataRepository.isInitialDataLoaded,
                    offlineModeManager.canLoadRemoteContent,
                ) { isLoaded, canLoadRemoteContent ->
                    isLoaded to canLoadRemoteContent
                }
                .collect { (isLoaded, canLoadRemoteContent) ->
                    when {
                        canLoadRemoteContent && isLoaded -> loadLibraryContent()
                        !canLoadRemoteContent -> loadLocalLibraryContent()
                        else -> {
                            _uiState.update {
                                it.copy(isLoading = true, error = null, userProfileImageUrl = null)
                            }
                            _pagingData.value = emptyFlow()
                        }
                    }
                }
        }
        viewModelScope.launch {
            appDataRepository.userProfileImageUrl.collect { url ->
                _uiState.update { it.copy(userProfileImageUrl = url) }
            }
        }
        viewModelScope.launch {
            playbackStateManager.playbackEvents.collect { event ->
                if (event is PlaybackEvent.Synced) {
                    val syncedItem = mediaRepository.getItemById(event.itemId) ?: return@collect
                    val targetItem =
                        when (syncedItem) {
                            is AfinityEpisode -> mediaRepository.getItemById(syncedItem.seriesId)
                            is AfinitySeason -> mediaRepository.getItemById(syncedItem.seriesId)
                            else -> syncedItem
                        } ?: return@collect
                    _itemUpdates.value += (targetItem.id to targetItem)
                }
            }
        }
    }

    private suspend fun determineLibraryType(): CollectionType {
        if (studioName != null) {
            Timber.d("Studio mode: using Mixed collection type")
            return CollectionType.Mixed
        }

        return try {
            val libraries = mediaRepository.getLibraries()
            val library = libraries.find { it.id.toString() == libraryId }
            Timber.d("Library '$libraryName' has type: ${library?.type}")
            library?.type ?: CollectionType.Mixed
        } catch (e: Exception) {
            Timber.w(e, "Failed to determine library type, falling back to name detection")
            val name = libraryName ?: ""
            when {
                name.contains("TV", ignoreCase = true) ||
                        name.contains("Shows", ignoreCase = true) ||
                        name.contains("Series", ignoreCase = true) -> CollectionType.TvShows

                name.contains("Movie", ignoreCase = true) -> CollectionType.Movies
                else -> CollectionType.Mixed
            }
        }
    }

    private fun inferLibraryTypeFromName(): CollectionType {
        if (studioName != null) return CollectionType.Mixed
        val name = _uiState.value.libraryName
        return when {
            name.contains("TV", ignoreCase = true) ||
                name.contains("Shows", ignoreCase = true) ||
                name.contains("Series", ignoreCase = true) ->
                CollectionType.TvShows

            name.contains("Movie", ignoreCase = true) -> CollectionType.Movies
            else -> CollectionType.Mixed
        }
    }

    private fun loadItems() {
        viewModelScope.launch {
            if (!offlineModeManager.canLoadRemoteContentNow()) {
                loadLocalLibraryContent()
                return@launch
            }

            val type = libraryType ?: return@launch
            _itemUpdates.value = emptyMap()

            val baseFlow =
                mediaRepository.getItemsPaging(
                    parentId = libraryId?.let { UUID.fromString(it) },
                    libraryType = type,
                    sortBy = currentSortBy,
                    sortDescending = currentSortDescending,
                    filter = currentFilter,
                    nameStartsWith = null,
                    studioName = studioName,
                )
            _pagingData.value = applyUpdatesToPagingFlow(baseFlow)
        }
    }

    private fun loadLibraryContent() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                if (!offlineModeManager.canLoadRemoteContentNow()) {
                    showRemoteUnavailable()
                    return@launch
                }

                val type = determineLibraryType()
                libraryType = type

                currentSortBy = preferencesRepository.getDefaultSortBy()
                currentSortDescending = preferencesRepository.getSortDescending()

                _uiState.value =
                    _uiState.value.copy(
                        libraryType = type,
                        currentSortBy = currentSortBy,
                        currentSortDescending = currentSortDescending,
                        currentFilter = currentFilter,
                        isLoading = false,
                    )

                loadItems()
            } catch (e: Exception) {
                Timber.e(e, "Failed to load library content")
                loadLocalLibraryContent()
            }
        }
    }

    private fun loadLocalLibraryContent(nameStartsWith: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val type = libraryType ?: inferLibraryTypeFromName()
                libraryType = type
                currentSortBy = preferencesRepository.getDefaultSortBy()
                currentSortDescending = preferencesRepository.getSortDescending()

                val profileUserId = currentProfileUserId()
                val capability = kidModeRepository.policy.value
                val localItems =
                    localLibraryMediaRepository
                        .getContentForContainer(
                            containerId = libraryId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                            containerName = _uiState.value.libraryName,
                            profileUserId = profileUserId,
                            visibilityContext =
                                LocalLibraryVisibilityContext(
                                    currentUserId = profileUserId,
                                    kidModeEnabled = capability.isKidModeEnabled,
                                    parentUnlocked = capability.isParentUnlocked,
                                ),
                        )
                        .filterForLibraryType(type)
                        .filterForCurrentFilter()
                        .filterForLetter(nameStartsWith)
                        .sortedForCurrentSort()

                _itemUpdates.value = emptyMap()
                _pagingData.value = flowOf(PagingData.from(localItems))
                _uiState.value =
                    _uiState.value.copy(
                        libraryType = type,
                        currentSortBy = currentSortBy,
                        currentSortDescending = currentSortDescending,
                        currentFilter = currentFilter,
                        isLoading = false,
                    )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load local library content")
                showRemoteUnavailable()
            }
        }
    }

    private fun showRemoteUnavailable() {
        _uiState.update {
            it.copy(
                isLoading = false,
                error = context.getString(R.string.error_folder_unavailable_offline),
            )
        }
        _pagingData.value = flowOf(PagingData.empty<AfinityItem>())
    }

    private suspend fun currentProfileUserId(): String? =
        authRepository.currentUser.value?.id?.toString()
            ?: sessionManager.currentSession.value?.userId?.toString()
            ?: preferencesRepository.getCurrentUserId()

    private fun List<AfinityItem>.filterForLibraryType(type: CollectionType): List<AfinityItem> =
        when (type) {
            CollectionType.Movies -> filterIsInstance<AfinityMovie>()
            CollectionType.TvShows ->
                filter { item ->
                    item is AfinityShow || item is AfinitySeason || item is AfinityEpisode
                }

            else -> this
        }

    private fun List<AfinityItem>.filterForCurrentFilter(): List<AfinityItem> =
        filter { item ->
            when (currentFilter) {
                FilterType.ALL -> true
                FilterType.WATCHED -> item.played
                FilterType.UNWATCHED -> !item.played
                FilterType.WATCHLIST -> item.liked
                FilterType.FAVORITES -> item.favorite
            }
        }

    private fun List<AfinityItem>.filterForLetter(nameStartsWith: String?): List<AfinityItem> {
        val prefix = nameStartsWith?.takeIf { it.isNotBlank() } ?: return this
        return filter { item -> item.name.startsWith(prefix, ignoreCase = true) }
    }

    private fun List<AfinityItem>.sortedForCurrentSort(): List<AfinityItem> {
        val sorted =
            when (currentSortBy) {
                SortBy.RANDOM -> shuffled()
                else -> sortedBy { it.name.lowercase() }
            }
        return if (currentSortDescending && currentSortBy != SortBy.RANDOM) sorted.reversed() else sorted
    }

    fun updateFilter(filterType: FilterType) {
        if (currentFilter != filterType) {
            currentFilter = filterType
            _uiState.value = _uiState.value.copy(currentFilter = currentFilter)
            loadItems()
        }
    }

    fun updateSort(sortBy: SortBy, descending: Boolean) {
        if (currentSortBy != sortBy || currentSortDescending != descending) {
            currentSortBy = sortBy
            currentSortDescending = descending
            viewModelScope.launch {
                preferencesRepository.setDefaultSortBy(sortBy)
                preferencesRepository.setSortDescending(descending)
            }
            _uiState.value =
                _uiState.value.copy(
                    currentSortBy = currentSortBy,
                    currentSortDescending = currentSortDescending,
                )
            loadItems()
        }
    }

    fun onItemClick(item: AfinityItem) {
        Timber.d("Item clicked: ${item.name} (${item.id})")
        // TODO: Navigate to item detail screen
    }

    fun resetScrollIndex() {
        _scrollToIndex.value = -1
    }

    fun scrollToLetter(letter: String) {
        if (_uiState.value.selectedLetter == letter) {
            clearLetterFilter()
            return
        }

        viewModelScope.launch {
            try {
                if (!offlineModeManager.canLoadRemoteContentNow()) {
                    _uiState.value = _uiState.value.copy(selectedLetter = letter)
                    val letterFilter =
                        when (letter) {
                            "#" -> "0"
                            else -> letter
                        }
                    loadLocalLibraryContent(nameStartsWith = letterFilter)
                    return@launch
                }

                val type = libraryType ?: return@launch

                val letterFilter =
                    when (letter) {
                        "#" -> "0"
                        else -> letter
                    }

                _uiState.value = _uiState.value.copy(selectedLetter = letter)
                _itemUpdates.value = emptyMap()

                val baseFlow =
                    mediaRepository.getItemsPaging(
                        parentId = libraryId?.let { UUID.fromString(it) },
                        libraryType = type,
                        sortBy = currentSortBy,
                        sortDescending = currentSortDescending,
                        filter = currentFilter,
                        nameStartsWith = letterFilter,
                        studioName = studioName,
                    )
                _pagingData.value = applyUpdatesToPagingFlow(baseFlow)

                Timber.d("Alphabet scroll: Created new paging source for letter '$letter'")
            } catch (e: Exception) {
                Timber.e(e, "Failed to scroll to letter $letter")
            }
        }
    }

    fun clearLetterFilter() {
        _uiState.value = _uiState.value.copy(selectedLetter = null)
        loadItems()
    }
}

data class LibraryContentUiState(
    val libraryId: UUID?,
    val libraryName: String,
    val libraryType: CollectionType? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val userProfileImageUrl: String? = null,
    val currentSortBy: SortBy = SortBy.NAME,
    val currentSortDescending: Boolean = false,
    val currentFilter: FilterType = FilterType.ALL,
    val isStudioMode: Boolean = false,
    val selectedLetter: String? = null,
)
