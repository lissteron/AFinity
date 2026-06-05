package com.makd.afinity.data.manager

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModeUxSourceTest {
    @Test
    fun hardOfflineDoesNotIncludeServerUnavailableAndCanRequestForegroundProbe() {
        val offlineManager = readSource("src/main/java/com/makd/afinity/data/manager/OfflineModeManager.kt")
        val reachabilityMonitor =
            readSource("src/main/java/com/makd/afinity/data/manager/ServerReachabilityMonitor.kt")

        val hardOfflineBlock =
            offlineManager.substring(
                offlineManager.indexOf("internal fun OfflineModeReason.isHardOfflineReason"),
                offlineManager.indexOf("val isServerUnavailable"),
            )

        assertTrue(hardOfflineBlock.contains("OfflineModeReason.MANUAL"))
        assertTrue(hardOfflineBlock.contains("OfflineModeReason.NO_NETWORK"))
        assertTrue(!hardOfflineBlock.contains("SERVER_UNREACHABLE"))
        assertTrue(offlineManager.contains("resolveOfflineModeReason("))
        assertTrue(offlineManager.contains("val canLoadRemoteContent"))
        assertTrue(offlineManager.contains("suspend fun canLoadRemoteContentNow()"))
        assertTrue(offlineManager.contains("suspend fun requestConnectivityProbe(reason: String)"))
        assertTrue(offlineManager.contains("serverReachabilityMonitor.probeNow(reason)"))
        assertTrue(reachabilityMonitor.contains("MutableSharedFlow<String>"))
        assertTrue(reachabilityMonitor.contains("withTimeoutOrNull(nextDelayMs)"))
        assertTrue(reachabilityMonitor.contains("fun probeNow(reason: String)"))
    }

    @Test
    fun downloadedOnlyUiUsesHardOfflineAndServerUnavailableKeepsDegradedContentVisible() {
        val navigation = readSource("src/main/java/com/makd/afinity/navigation/MainNavigation.kt")
        val homeViewModel = readSource("src/main/java/com/makd/afinity/ui/home/HomeViewModel.kt")
        val homeScreen = readSource("src/main/java/com/makd/afinity/ui/home/HomeScreen.kt")
        val detailViewModel = readSource("src/main/java/com/makd/afinity/ui/item/ItemDetailViewModel.kt")
        val homeReloadWorker = readSource("src/main/java/com/makd/afinity/data/workers/HomeDataReloadWorker.kt")
        val sessionManager = readSource("src/main/java/com/makd/afinity/data/manager/SessionManager.kt")
        val logoutBlock =
            sessionManager.substring(
                sessionManager.indexOf("suspend fun logout()"),
                sessionManager.indexOf("private fun getOrCreateApiClient"),
            )

        assertTrue(navigation.contains("offlineModeManager.hardOffline"))
        assertTrue(navigation.contains("requestConnectivityProbe(\"app foreground\")"))
        assertTrue(homeViewModel.contains("offlineModeManager.hardOffline.collect"))
        assertTrue(homeViewModel.contains("offlineModeManager.isServerUnavailable.collect"))
        assertTrue(homeViewModel.contains("offlineModeManager.canLoadRemoteContentNow()"))
        assertTrue(homeReloadWorker.contains("!offlineModeManager.canLoadRemoteContentNow()"))
        assertTrue(homeScreen.contains("val showDownloadedContent = uiState.isOffline || uiState.isServerUnavailable"))
        assertTrue(detailViewModel.contains("offlineModeManager.hardOffline"))
        assertTrue(detailViewModel.contains("val canLoadRemoteContent = offlineModeManager.canLoadRemoteContentNow()"))
        assertTrue(detailViewModel.contains("loadDownloadedItemFromDatabase()"))
        assertTrue(detailViewModel.contains("canLoadRemoteContent && !loadedFromOfflineCache"))
        assertTrue(detailViewModel.contains("downloadRepository"))
        assertTrue(detailViewModel.contains(".getCompletedDownloadsFlow()"))
        assertTrue(logoutBlock.contains("_isServerReachable.value = true"))
    }

    @Test
    fun settingsSwitchRepresentsManualOfflineAndConnectionChipHasServerUnavailableState() {
        val settingsViewModel = readSource("src/main/java/com/makd/afinity/ui/settings/SettingsViewModel.kt")
        val settingsScreen = readSource("src/main/java/com/makd/afinity/ui/settings/SettingsScreen.kt")
        val topAppBar = readSource("src/main/java/com/makd/afinity/ui/components/AfinityTopAppBar.kt")

        assertTrue(settingsViewModel.contains("val manualOfflineMode"))
        assertTrue(settingsViewModel.contains("offlineModeManager.manualOfflineMode"))
        assertTrue(settingsScreen.contains("checked = manualOfflineMode"))
        assertTrue(settingsScreen.contains("hardOfflineMode -> ConnectionType.OFFLINE"))
        assertTrue(topAppBar.contains("SERVER_UNAVAILABLE"))
        assertTrue(topAppBar.contains("offlineReason == OfflineModeReason.SERVER_UNREACHABLE"))
    }

    @Test
    fun remoteOnlyNavigationAndSearchRecoverWhenRemoteContentIsOnline() {
        val navigationViewModel =
            readSource("src/main/java/com/makd/afinity/navigation/MainNavigationViewModel.kt")
        val navigation = readSource("src/main/java/com/makd/afinity/navigation/MainNavigation.kt")
        val topAppBar = readSource("src/main/java/com/makd/afinity/ui/components/AfinityTopAppBar.kt")
        val searchViewModel = readSource("src/main/java/com/makd/afinity/ui/search/SearchViewModel.kt")
        val searchScreen = readSource("src/main/java/com/makd/afinity/ui/search/SearchScreen.kt")
        val selectLibraryBlock =
            searchViewModel.substring(
                searchViewModel.indexOf("fun selectLibrary("),
                searchViewModel.indexOf("fun performSearch()"),
            )
        val selectJellyseerrModeBlock =
            searchViewModel.substring(
                searchViewModel.indexOf("fun selectJellyseerrSearchMode()"),
                searchViewModel.indexOf("private fun loadCurrentUser()"),
            )
        val selectAudiobookshelfModeBlock =
            searchViewModel.substring(
                searchViewModel.indexOf("fun selectAudiobookshelfSearchMode()"),
                searchViewModel.indexOf("fun performAudiobookshelfSearch()"),
            )

        assertTrue(navigationViewModel.contains("observeRemoteContentAvailability()"))
        assertTrue(navigationViewModel.contains("offlineModeManager.canLoadRemoteContent.drop(1).collect"))
        assertTrue(navigationViewModel.contains("checkLiveTvAccess()"))
        assertTrue(navigation.contains("val canLoadRemoteContent by"))
        assertTrue(navigation.contains("val canUseAnySearchBackend"))
        assertTrue(navigation.contains("canLoadRemoteContent || isJellyseerrAuthenticated || isAudiobookshelfAuthenticated"))
        assertTrue(navigation.contains("isOffline || !capabilityPolicy.canUseSearch || !canUseAnySearchBackend"))
        assertTrue(topAppBar.contains("offlineModeManager.hardOffline"))
        assertTrue(topAppBar.contains("canLoadRemoteContent || isJellyseerrAuthenticated || isAudiobookshelfAuthenticated"))
        assertTrue(searchViewModel.contains("val canLoadJellyfinContent"))
        assertTrue(searchViewModel.contains("offlineModeManager.canLoadRemoteContent"))
        assertTrue(searchViewModel.contains("offlineModeManager.canLoadRemoteContentNow()"))
        assertTrue(searchViewModel.contains("offlineModeManager.requestConnectivityProbe(\"search jellyfin\")"))
        assertTrue(searchViewModel.contains("fun selectAllSearchMode()"))
        assertTrue(searchViewModel.contains("fun performAllAvailableSearches()"))
        assertTrue(searchViewModel.contains("performAllAvailableSearches()"))
        assertTrue(searchViewModel.contains("!audiobookshelfRepository.isAuthenticated.value"))
        assertTrue(searchViewModel.contains("!jellyseerrRepository.isAuthenticated.value"))
        assertTrue(searchViewModel.contains("clearAudiobookshelfSearchState()"))
        assertTrue(searchViewModel.contains("clearJellyseerrSearchState()"))
        assertTrue(searchViewModel.contains("audiobookshelfGenres = emptyList()"))
        assertTrue(searchViewModel.contains("_currentUser.value = null"))
        assertTrue(selectLibraryBlock.contains("if (library == null)"))
        assertTrue(selectLibraryBlock.contains("selectAllSearchMode()"))
        assertTrue(selectLibraryBlock.contains("cancelAllSearchJobs()"))
        assertTrue(selectLibraryBlock.contains("isAudiobookshelfSearching = false"))
        assertTrue(selectLibraryBlock.contains("isJellyseerrSearching = false"))
        assertTrue(selectJellyseerrModeBlock.contains("isSearching = false"))
        assertTrue(selectJellyseerrModeBlock.contains("isAudiobookshelfSearching = false"))
        assertTrue(selectAudiobookshelfModeBlock.contains("isSearching = false"))
        assertTrue(selectAudiobookshelfModeBlock.contains("isJellyseerrSearching = false"))
        assertTrue(searchScreen.contains("canLoadJellyfinContent"))
        assertTrue(searchScreen.contains("viewModel.performAllAvailableSearches()"))
        assertTrue(searchScreen.contains("onAllSearchSelected = viewModel::selectAllSearchMode"))
        assertTrue(!searchScreen.contains("onLibrarySelected(null)"))
        assertTrue(!searchScreen.contains("onJellyfinSearchSelected"))
        assertTrue(searchScreen.contains("val hasAnyAllResults"))
        assertTrue(searchScreen.contains("uiState.isJellyseerrSearching"))
    }

    @Test
    fun audiobookshelfNetworkWorkUsesHardOfflineNotJellyfinReachability() {
        val absDownloadRepository =
            readSource("src/main/java/com/makd/afinity/data/repository/audiobookshelf/AbsDownloadRepositoryImpl.kt")
        val absMediaDownloadWorker =
            readSource("src/main/java/com/makd/afinity/data/workers/AbsMediaDownloadWorker.kt")
        val absProgressSyncWorker =
            readSource("src/main/java/com/makd/afinity/data/workers/AbsProgressSyncWorker.kt")

        assertTrue(absDownloadRepository.contains("offlineModeManager.isHardOffline()"))
        assertTrue(!absDownloadRepository.contains("offlineModeManager.isCurrentlyOffline()"))
        assertTrue(absMediaDownloadWorker.contains("offlineModeManager.isHardOffline()"))
        assertTrue(!absMediaDownloadWorker.contains("offlineModeManager.isCurrentlyOffline()"))
        assertTrue(absProgressSyncWorker.contains("offlineModeManager.isHardOffline()"))
        assertTrue(!absProgressSyncWorker.contains("offlineModeManager.isCurrentlyOffline()"))
    }

    private fun readSource(path: String): String = File(path).readText()
}
