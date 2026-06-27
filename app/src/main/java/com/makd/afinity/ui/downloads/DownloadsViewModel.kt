package com.makd.afinity.ui.downloads

import android.os.StatFs
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.makd.afinity.data.local.LocalLibraryRootBootstrapper
import com.makd.afinity.data.local.LocalLibraryRootManager
import com.makd.afinity.data.local.LocalLibraryRootRecord
import com.makd.afinity.data.local.LocalLibraryRootStore
import com.makd.afinity.data.local.LocalLibraryMediaRepository
import com.makd.afinity.data.local.LocalLibraryScanSummary
import com.makd.afinity.data.local.LocalLibraryScanService
import com.makd.afinity.data.local.LocalLibraryVisibilityContext
import com.makd.afinity.data.models.audiobookshelf.AbsDownloadInfo
import com.makd.afinity.data.models.download.DownloadInfo
import com.makd.afinity.data.models.download.DownloadQualityMode
import com.makd.afinity.data.models.download.DownloadStorageLocation
import com.makd.afinity.data.models.download.DownloadStatus
import com.makd.afinity.data.repository.KidModeRepository
import com.makd.afinity.data.repository.PreferencesRepository
import com.makd.afinity.data.repository.audiobookshelf.AbsDownloadRepository
import com.makd.afinity.data.repository.download.DownloadRepository
import com.makd.afinity.data.storage.DownloadStorageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel
@Inject
constructor(
    private val downloadRepository: DownloadRepository,
    private val absDownloadRepository: AbsDownloadRepository,
    private val preferencesRepository: PreferencesRepository,
    private val downloadStorageManager: DownloadStorageManager,
    private val kidModeRepository: KidModeRepository,
    private val localLibraryRootStore: LocalLibraryRootStore,
    private val localLibraryRootBootstrapper: LocalLibraryRootBootstrapper,
    private val localLibraryRootManager: LocalLibraryRootManager,
    private val localLibraryMediaRepository: LocalLibraryMediaRepository,
    private val localLibraryScanService: LocalLibraryScanService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()
    val capabilityPolicy = kidModeRepository.policy
    private var artworkRefreshJob: Job? = null

    init {
        observeDownloads()
        observeLocalLibraryRoots()
        observeLocalLibraryCatalog()
        observeLocalLibraryVisibilityProfile()
        loadStorageInfo()
        loadDownloadPreferences()
        loadLocalArtworkRefreshCount()
    }

    private fun loadDownloadPreferences() {
        viewModelScope.launch {
            try {
                val wifiOnly = preferencesRepository.getDownloadOverWifiOnly()
                val isImageCacheEnabled = preferencesRepository.getImageCacheEnabled()
                val imageCacheSizeMb = preferencesRepository.getImageCacheSizeMb()
                val downloadQualityMode =
                    DownloadQualityMode.fromPreference(preferencesRepository.getDownloadQuality())
                val storageLocations = downloadStorageManager.getAvailableLocations()

                _uiState.value =
                    _uiState.value.copy(
                        downloadOverWifiOnly = wifiOnly,
                        downloadQualityMode = downloadQualityMode,
                        isImageCacheEnabled = isImageCacheEnabled,
                        imageCacheSizeMb = imageCacheSizeMb,
                        storageLocations = storageLocations,
                    )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load download preferences")
            }
        }
    }

    fun setDownloadQualityMode(mode: DownloadQualityMode) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                preferencesRepository.setDownloadQuality(mode.preferenceValue)
                _uiState.value = _uiState.value.copy(downloadQualityMode = mode)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update download quality preference")
                _uiState.value =
                    _uiState.value.copy(error = "Failed to update download quality: ${e.message}")
            }
        }
    }

    fun setDownloadOverWifiOnly(wifiOnly: Boolean) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                preferencesRepository.setDownloadOverWifiOnly(wifiOnly)
                _uiState.value = _uiState.value.copy(downloadOverWifiOnly = wifiOnly)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update download WiFi preference")
            }
        }
    }

    fun setImageCacheEnabled(enabled: Boolean) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                preferencesRepository.setImageCacheEnabled(enabled)
                _uiState.value = _uiState.value.copy(isImageCacheEnabled = enabled)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update image cache enabled preference")
            }
        }
    }

    fun setImageCacheSizeMb(sizeMb: Int) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                preferencesRepository.setImageCacheSizeMb(sizeMb)
                _uiState.value = _uiState.value.copy(imageCacheSizeMb = sizeMb)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update image cache size preference")
            }
        }
    }

    fun setDownloadStorageLocation(locationId: String) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                if (uiState.value.activeDownloads.isNotEmpty()) {
                    _uiState.value =
                        _uiState.value.copy(
                            error =
                                "Finish, pause-delete, or cancel active Jellyfin downloads before changing storage location"
                        )
                    return@launch
                }

                downloadStorageManager.setSelectedLocation(locationId)
                localLibraryRootBootstrapper.ensureDefaultRoot(
                    preferSelectedDownloadLocation = true
                )
                Timber.i("Download storage location selected: %s", locationId)
                rescanLocalLibraryRootsInternal()
                val storageLocations = downloadStorageManager.getAvailableLocations()
                _uiState.value = _uiState.value.copy(storageLocations = storageLocations)
                loadStorageInfo()
            } catch (e: Exception) {
                Timber.e(e, "Failed to update download storage location")
                _uiState.value =
                    _uiState.value.copy(
                        error = "Failed to update download storage location: ${e.message}"
                    )
            }
        }
    }

    fun setCustomDownloadStorageLocation(uri: Uri) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                if (uiState.value.activeDownloads.isNotEmpty()) {
                    _uiState.value =
                        _uiState.value.copy(
                            error =
                                "Finish, pause-delete, or cancel active Jellyfin downloads before changing storage location"
                        )
                    return@launch
                }

                downloadStorageManager.setCustomTreeLocation(uri)
                localLibraryRootBootstrapper.ensureDefaultRoot(
                    preferSelectedDownloadLocation = true
                )
                Timber.i("Custom download storage location selected: %s", uri)
                rescanLocalLibraryRootsInternal()
                val storageLocations = downloadStorageManager.getAvailableLocations()
                _uiState.value = _uiState.value.copy(storageLocations = storageLocations)
                loadStorageInfo()
            } catch (e: Exception) {
                Timber.e(e, "Failed to set custom download folder")
                _uiState.value =
                    _uiState.value.copy(error = "Failed to set custom folder: ${e.message}")
            }
        }
    }

    fun addLocalLibraryFolder(uri: Uri) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                localLibraryRootManager.addSafRoot(uri)
                rescanLocalLibraryRootsInternal()
            } catch (e: Exception) {
                Timber.e(e, "Failed to add local library folder")
                _uiState.value =
                    _uiState.value.copy(error = "Failed to add local library folder: ${e.message}")
            }
        }
    }

    fun onFolderPickerUnavailable(target: String, detail: String? = null) {
        val message =
            "System folder picker is unavailable in this profile. Use Device storage or enable Files/DocumentsUI for this child profile."
        if (detail != null) {
            Timber.w("Folder picker unavailable for %s: %s", target, detail)
        } else {
            Timber.w("Folder picker unavailable for %s", target)
        }
        _uiState.value = _uiState.value.copy(error = message)
    }

    fun onFolderPickerLaunchFailed(target: String, error: Throwable) {
        Timber.e(error, "Failed to launch folder picker for %s", target)
        _uiState.value =
            _uiState.value.copy(
                error =
                    "System folder picker could not open in this profile: ${error.message ?: error::class.simpleName}"
            )
    }

    fun onFolderPickerCancelled(target: String) {
        Timber.i("Folder picker dismissed for %s", target)
    }

    fun setLocalLibraryRootEnabled(rootId: UUID, enabled: Boolean) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                localLibraryRootManager.setEnabled(rootId, enabled)
                rescanLocalLibraryRootsInternal()
            } catch (e: Exception) {
                Timber.e(e, "Failed to update local library folder")
                _uiState.value =
                    _uiState.value.copy(error = "Failed to update local library folder: ${e.message}")
            }
        }
    }

    fun setDefaultLocalLibraryRoot(rootId: UUID) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                localLibraryRootManager.setDefaultForDownloads(rootId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to set default local library folder")
                _uiState.value =
                    _uiState.value.copy(error = "Failed to set default local library folder: ${e.message}")
            }
        }
    }

    fun removeLocalLibraryRoot(rootId: UUID) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                localLibraryRootManager.removeRoot(rootId)
                rescanLocalLibraryRootsInternal()
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove local library folder")
                _uiState.value =
                    _uiState.value.copy(error = "Failed to remove local library folder: ${e.message}")
            }
        }
    }

    fun rescanLocalLibraryRoots() {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                Timber.i("Manual local library rescan requested")
                rescanLocalLibraryRootsInternal()
                loadStorageInfo()
            } catch (e: Exception) {
                Timber.e(e, "Failed to rescan local library")
                _uiState.value =
                    _uiState.value.copy(error = "Failed to rescan local library: ${e.message}")
            }
        }
    }

    private suspend fun rescanLocalLibraryRootsInternal() {
        _uiState.value =
            _uiState.value.copy(
                localLibraryScan = _uiState.value.localLibraryScan.copy(isRunning = true)
            )
        try {
            val summaries =
                localLibraryScanService.scanEnabledRootsWithArtworkBackfill(
                    currentLocalVisibilityContext()
                )
            Timber.i(
                "Local library rescan finished from Downloads screen: %s",
                summaries.joinToString(separator = "; ") { summary ->
                    "root=${summary.rootId} files=${summary.discoveredFiles} unavailable=${summary.unavailableItems} errors=${summary.errors.size}"
                },
            )
            publishLocalLibraryScanSummary(summaries)
            updateLocalLibraryCatalogSummary()
            updateLocalArtworkRefreshCount()
        } catch (e: CancellationException) {
            _uiState.value =
                _uiState.value.copy(
                    localLibraryScan =
                        _uiState.value.localLibraryScan.copy(
                            isRunning = false,
                            lastCancelled = true,
                        )
                )
            throw e
        } catch (e: Exception) {
            _uiState.value =
                _uiState.value.copy(
                    localLibraryScan =
                        _uiState.value.localLibraryScan.copy(
                            isRunning = false,
                            lastCancelled = false,
                            lastErrorCount = 1,
                        )
                )
            throw e
        }
    }

    private fun publishLocalLibraryScanSummary(summaries: List<LocalLibraryScanSummary>) {
        _uiState.value =
            _uiState.value.copy(
                localLibraryScan =
                    _uiState.value.localLibraryScan.copy(
                        isRunning = false,
                        lastDiscoveredFiles = summaries.sumOf { it.discoveredFiles },
                        lastUnavailableRoots = summaries.sumOf { it.unavailableItems },
                        lastErrorCount = summaries.sumOf { it.errors.size },
                        lastCancelled = summaries.any { it.cancelled },
                    )
            )
    }

    private fun observeDownloads() {
        viewModelScope.launch {
            try {
                downloadRepository
                    .getActiveDownloadsFlow()
                    .catch { e -> Timber.e(e, "Error observing active downloads") }
                    .collect { activeDownloads ->
                        _uiState.value = _uiState.value.copy(activeDownloads = activeDownloads)
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to observe active downloads")
            }
        }

        viewModelScope.launch {
            try {
                downloadRepository
                    .getCompletedDownloadsFlow()
                    .catch { e -> Timber.e(e, "Error observing completed downloads") }
                    .collect { completedDownloads ->
                        _uiState.value =
                            _uiState.value.copy(completedDownloads = completedDownloads)
                        loadStorageInfo()
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to observe completed downloads")
            }
        }

        viewModelScope.launch {
            try {
                absDownloadRepository
                    .getActiveDownloadsFlow()
                    .catch { e -> Timber.e(e, "Error observing ABS active downloads") }
                    .collect { absActive ->
                        _uiState.value = _uiState.value.copy(absActiveDownloads = absActive)
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to observe ABS active downloads")
            }
        }

        viewModelScope.launch {
            try {
                absDownloadRepository
                    .getCompletedDownloadsFlow()
                    .catch { e -> Timber.e(e, "Error observing ABS completed downloads") }
                    .collect { absCompleted ->
                        _uiState.value = _uiState.value.copy(absCompletedDownloads = absCompleted)
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to observe ABS completed downloads")
            }
        }
    }

    private fun observeLocalLibraryRoots() {
        viewModelScope.launch {
            try {
                localLibraryRootBootstrapper.ensureDefaultRoot()
                localLibraryRootStore
                    .rootsFlow()
                    .catch { e -> Timber.e(e, "Error observing local library roots") }
                    .collect { roots ->
                        _uiState.value =
                            _uiState.value.copy(localLibraryRoots = roots.sortedBy { it.priority })
                        updateLocalArtworkRefreshCount()
                        updateLocalLibraryCatalogSummary()
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to observe local library roots")
            }
        }
    }

    private fun observeLocalLibraryCatalog() {
        viewModelScope.launch {
            try {
                localLibraryMediaRepository
                    .catalogGenerationFlow()
                    .catch { e -> Timber.e(e, "Error observing local library catalog") }
                    .collect { updateLocalLibraryCatalogSummary() }
            } catch (e: Exception) {
                Timber.e(e, "Failed to observe local library catalog")
            }
        }
    }

    private fun observeLocalLibraryVisibilityProfile() {
        viewModelScope.launch {
            try {
                preferencesRepository
                    .getCurrentUserIdFlow()
                    .catch { e -> Timber.e(e, "Error observing local library profile") }
                    .collect { updateLocalLibraryCatalogSummary() }
            } catch (e: Exception) {
                Timber.e(e, "Failed to observe local library profile")
            }
        }
    }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            try {
                val appStorageUsed = downloadRepository.getTotalStorageUsed()
                val allServersStorageUsed = downloadRepository.getTotalStorageUsedAllServers()
                val deviceStats = getDeviceStorageStats()
                val storageLocations = downloadStorageManager.getAvailableLocations()
                val imageCacheStorageUsed = downloadStorageManager.getImageCacheStorageUsed()
                val downloadedImageStorageUsed =
                    downloadStorageManager.getDownloadedImageStorageUsed()
                _uiState.value =
                    _uiState.value.copy(
                        totalStorageUsed = appStorageUsed,
                        totalStorageUsedAllServers = allServersStorageUsed,
                        deviceStorageStats = deviceStats,
                        storageLocations = storageLocations,
                        imageCacheStorageUsed = imageCacheStorageUsed,
                        downloadedImageStorageUsed = downloadedImageStorageUsed,
                    )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load storage info")
            }
        }
    }

    private fun loadLocalArtworkRefreshCount() {
        viewModelScope.launch {
            try {
                updateLocalArtworkRefreshCount()
            } catch (e: Exception) {
                Timber.e(e, "Failed to load local artwork refresh count")
            }
        }
    }

    private suspend fun updateLocalArtworkRefreshCount() {
        val count =
            localLibraryScanService.refreshableLocalLibraryArtworkCount(
                forceRefreshItemArtwork = true
            )
        _uiState.value = _uiState.value.copy(localArtworkRefreshCount = count)
    }

    private suspend fun updateLocalLibraryCatalogSummary() {
        val summary = localLibraryMediaRepository.visibleCatalogSummary(currentLocalVisibilityContext())
        _uiState.value =
            _uiState.value.copy(
                localLibraryScan =
                    _uiState.value.localLibraryScan.copy(
                        indexedFiles = summary.fileCount,
                        indexedStorageUsed = summary.totalSizeBytes,
                    )
            )
    }

    private suspend fun currentLocalVisibilityContext(): LocalLibraryVisibilityContext =
        LocalLibraryVisibilityContext(
            currentUserId = preferencesRepository.getCurrentUserId(),
            kidModeEnabled = kidModeRepository.policy.value.isKidModeEnabled,
            parentUnlocked = kidModeRepository.policy.value.isParentUnlocked,
        )

    fun pauseDownload(downloadId: UUID) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                val result = downloadRepository.pauseDownload(downloadId)
                result.onFailure { error ->
                    Timber.e(error, "Failed to pause download")
                    _uiState.value =
                        _uiState.value.copy(error = "Failed to pause download: ${error.message}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error pausing download")
            }
        }
    }

    fun resumeDownload(downloadId: UUID) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                val result = downloadRepository.resumeDownload(downloadId)
                result.onFailure { error ->
                    Timber.e(error, "Failed to resume download")
                    _uiState.value =
                        _uiState.value.copy(error = "Failed to resume download: ${error.message}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error resuming download")
            }
        }
    }

    fun cancelDownload(downloadId: UUID) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                val result = downloadRepository.cancelDownload(downloadId)
                result
                    .onSuccess {
                        Timber.i("Download cancelled successfully")
                        loadStorageInfo()
                    }
                    .onFailure { error ->
                        Timber.e(error, "Failed to cancel download")
                        _uiState.value =
                            _uiState.value.copy(
                                error = "Failed to cancel download: ${error.message}"
                            )
                    }
            } catch (e: Exception) {
                Timber.e(e, "Error cancelling download")
            }
        }
    }

    fun deleteDownload(downloadId: UUID) {
        if (!kidModeRepository.policy.value.canDeleteDownloads) return
        viewModelScope.launch {
            try {
                val result = downloadRepository.deleteDownload(downloadId)
                result
                    .onSuccess {
                        Timber.i("Download deleted successfully")
                        loadStorageInfo()
                    }
                    .onFailure { error ->
                        Timber.e(error, "Failed to delete download")
                        _uiState.value =
                            _uiState.value.copy(
                                error = "Failed to delete download: ${error.message}"
                            )
                    }
            } catch (e: Exception) {
                Timber.e(e, "Error deleting download")
            }
        }
    }

    fun cancelAbsDownload(downloadId: UUID) {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        viewModelScope.launch {
            try {
                absDownloadRepository.cancelDownload(downloadId)
                    .onFailure { Timber.e(it, "Failed to cancel ABS download") }
            } catch (e: Exception) {
                Timber.e(e, "Error cancelling ABS download")
            }
        }
    }

    fun deleteAbsDownload(downloadId: UUID) {
        if (!kidModeRepository.policy.value.canDeleteDownloads) return
        viewModelScope.launch {
            try {
                absDownloadRepository.deleteDownload(downloadId)
                    .onSuccess { loadStorageInfo() }
                    .onFailure { Timber.e(it, "Failed to delete ABS download") }
            } catch (e: Exception) {
                Timber.e(e, "Error deleting ABS download")
            }
        }
    }

    fun deleteAbsPodcast(libraryItemId: String) {
        if (!kidModeRepository.policy.value.canDeleteDownloads) return
        viewModelScope.launch {
            uiState.value.absCompletedDownloads
                .filter { it.libraryItemId == libraryItemId }
                .forEach { absDownloadRepository.deleteDownload(it.id) }
            loadStorageInfo()
        }
    }

    fun refreshDownloadedArtwork() {
        if (!kidModeRepository.policy.value.canManageDownloads) return
        if (artworkRefreshJob?.isActive == true) return

        artworkRefreshJob =
            viewModelScope.launch {
                val completedVideoCount =
                    uiState.value.completedDownloads.count { download ->
                        download.itemType.uppercase() in setOf("MOVIE", "EPISODE")
                    }
                val localArtworkCount =
                    localLibraryScanService.refreshableLocalLibraryArtworkCount(
                        forceRefreshItemArtwork = true
                    )
                _uiState.value = _uiState.value.copy(localArtworkRefreshCount = localArtworkCount)
                val totalRefreshCount = completedVideoCount + localArtworkCount
                if (totalRefreshCount == 0) {
                    _uiState.value = _uiState.value.copy(error = "No downloaded videos or local artwork to refresh")
                    return@launch
                }
                _uiState.value =
                    _uiState.value.copy(
                        artworkRefresh =
                            ArtworkRefreshUiState(
                                isRunning = true,
                                completed = 0,
                                total = totalRefreshCount,
                            )
                    )
                try {
                    var refreshed = 0
                    var failed = 0
                    var skipped = 0

                    if (completedVideoCount > 0) {
                        downloadRepository
                            .refreshCompletedArtwork { progress ->
                                _uiState.value =
                                    _uiState.value.copy(
                                        artworkRefresh =
                                            ArtworkRefreshUiState(
                                                isRunning = true,
                                                completed = progress.completed,
                                                total = totalRefreshCount,
                                                currentItemName = progress.currentItemName,
                                            )
                                    )
                            }
                            .onSuccess { summary ->
                                refreshed += summary.refreshed
                                failed += summary.failed
                                skipped += summary.skipped
                            }
                            .onFailure { error ->
                                failed += completedVideoCount
                                Timber.w(error, "Failed to refresh completed download artwork")
                            }
                    }

                    if (localArtworkCount > 0) {
                        val localSummary =
                            localLibraryScanService.refreshLocalLibraryArtworkFromOrigins(
                                forceRefreshItemArtwork = true
                            ) { progress ->
                                _uiState.value =
                                    _uiState.value.copy(
                                        artworkRefresh =
                                            ArtworkRefreshUiState(
                                                isRunning = true,
                                                completed = completedVideoCount + progress.completed,
                                                total = totalRefreshCount,
                                                currentItemName = progress.currentItemName,
                                            )
                                    )
                            }
                        refreshed += localSummary.refreshedItems
                        failed += localSummary.failedItems
                        skipped += localSummary.skippedItems
                    }

                    rescanLocalLibraryRootsInternal()
                    loadStorageInfo()
                    _uiState.value =
                        _uiState.value.copy(
                            error = "Artwork refreshed: $refreshed, failed: $failed, skipped: $skipped"
                        )
                } catch (e: CancellationException) {
                    _uiState.value = _uiState.value.copy(error = "Artwork refresh cancelled")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to refresh artwork")
                    _uiState.value =
                        _uiState.value.copy(error = "Failed to refresh artwork: ${e.message}")
                } finally {
                    _uiState.value =
                        _uiState.value.copy(
                            artworkRefresh = _uiState.value.artworkRefresh.copy(isRunning = false)
                        )
                    loadStorageInfo()
                }
            }
    }

    fun cancelArtworkRefresh() {
        artworkRefreshJob?.cancel()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun retryFailedDownload(downloadId: UUID) {
        viewModelScope.launch {
            try {
                val download = downloadRepository.getDownload(downloadId)
                if (download != null && download.status == DownloadStatus.FAILED) {
                    downloadRepository.cancelDownload(downloadId)

                    Timber.d("Failed download cleared, user can restart from item detail")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error retrying failed download")
            }
        }
    }

    fun formatStorageSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format(
                Locale.getDefault(),
                "%.2f MB",
                bytes / (1024.0 * 1024.0)
            )

            else -> String.format(
                Locale.getDefault(),
                "%.2f GB",
                bytes / (1024.0 * 1024.0 * 1024.0)
            )
        }
    }

    data class DeviceStorageStats(
        val totalBytes: Long,
        val freeBytes: Long,
        val usedBytes: Long,
        val usagePercentage: Float,
    )

    private suspend fun getDeviceStorageStats(): DeviceStorageStats {
        val path: File = downloadStorageManager.getSelectedDownloadsRoot()
        val stat = StatFs(path.path)

        val totalBytes = stat.totalBytes
        val availableBytes = stat.availableBytes
        val usedBytes = totalBytes - availableBytes

        return DeviceStorageStats(
            totalBytes = totalBytes,
            freeBytes = availableBytes,
            usedBytes = usedBytes,
            usagePercentage = usedBytes.toFloat() / totalBytes.toFloat(),
        )
    }
}

data class DownloadsUiState(
    val activeDownloads: List<DownloadInfo> = emptyList(),
    val completedDownloads: List<DownloadInfo> = emptyList(),
    val absActiveDownloads: List<AbsDownloadInfo> = emptyList(),
    val absCompletedDownloads: List<AbsDownloadInfo> = emptyList(),
    val totalStorageUsed: Long = 0L,
    val totalStorageUsedAllServers: Long = 0L,
    val downloadOverWifiOnly: Boolean = true,
    val downloadQualityMode: DownloadQualityMode = DownloadQualityMode.HEVC_QUALITY,
    val isImageCacheEnabled: Boolean = true,
    val imageCacheSizeMb: Int = 512,
    val imageCacheStorageUsed: Long = 0L,
    val downloadedImageStorageUsed: Long = 0L,
    val storageLocations: List<DownloadStorageLocation> = emptyList(),
    val localLibraryRoots: List<LocalLibraryRootRecord> = emptyList(),
    val localArtworkRefreshCount: Int = 0,
    val localLibraryScan: LocalLibraryScanUiState = LocalLibraryScanUiState(),
    val deviceStorageStats: DownloadsViewModel.DeviceStorageStats? = null,
    val artworkRefresh: ArtworkRefreshUiState = ArtworkRefreshUiState(),
    val error: String? = null,
)

data class LocalLibraryScanUiState(
    val isRunning: Boolean = false,
    val indexedFiles: Int = 0,
    val indexedStorageUsed: Long = 0L,
    val lastDiscoveredFiles: Int? = null,
    val lastUnavailableRoots: Int = 0,
    val lastErrorCount: Int = 0,
    val lastCancelled: Boolean = false,
)

data class ArtworkRefreshUiState(
    val isRunning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val currentItemName: String? = null,
) {
    val progress: Float
        get() = if (total <= 0) 0f else completed.toFloat() / total.toFloat()
}
