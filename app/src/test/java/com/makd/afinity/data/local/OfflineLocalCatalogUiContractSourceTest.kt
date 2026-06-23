package com.makd.afinity.data.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLocalCatalogUiContractSourceTest {
    @Test
    fun homeReadsLocalCatalogWithoutForegroundScan() {
        val home = readSource("src/main/java/com/makd/afinity/ui/home/HomeViewModel.kt")

        assertTrue(home.contains("MutableSharedFlow<Unit>"))
        assertTrue(home.contains("AtomicBoolean(false)"))
        assertTrue(home.contains("pendingDownloadedContentForce.getAndSet(false)"))
        assertTrue(home.contains("pendingDownloadedContentForce.set(true)"))
        assertTrue(home.contains("replay = 1"))
        assertTrue(home.contains("BufferOverflow.DROP_OLDEST"))
        assertTrue(home.contains("HomeDownloadedContentRefreshGate()"))
        assertTrue(home.contains("HomeDownloadedOnlyRefreshTransition()"))
        assertTrue(home.contains("HomeDownloadedContentSessionKey("))
        assertTrue(home.contains("HomeDownloadedContentRefreshKey("))
        assertTrue(home.contains("serverId = serverId"))
        assertTrue(home.contains("databaseRepository.getAllMovies(userId, serverId)"))
        assertTrue(home.contains("databaseRepository.getAllShows(userId, serverId)"))
        assertTrue(home.contains("kidModeEnabled = capability.isKidModeEnabled"))
        assertTrue(home.contains("parentUnlocked = capability.isParentUnlocked"))
        assertTrue(home.contains(".catalogGenerationFlow()"))
        assertFalse(home.contains(".drop(1)"))
        assertTrue(home.contains("Skipping duplicate downloaded Home content refresh"))
        assertTrue(home.contains("requestDownloadedContentRefresh()"))
        assertTrue(home.contains("requestDownloadedContentRefresh(force = true)"))
        assertTrue(home.contains("refreshForceFor(isOffline)"))
        assertTrue(home.contains("refreshForceFor(isServerUnavailable)"))
        assertTrue(home.contains("catch (e: CancellationException)"))
        val downloadedContentBlock =
            home.substring(
                home.indexOf("private suspend fun loadDownloadedContent"),
                home.indexOf("private suspend fun currentHomeProfileUserId"),
            )
        assertFalse(downloadedContentBlock.contains("runCatching"))
        assertFalse(home.contains("viewModelScope.launch { loadDownloadedContent() }"))
        assertTrue(home.contains("clearRemoteHomeStatePreservingLocalCatalog()"))
        assertFalse(home.contains("_uiState.value = HomeUiState()"))
        assertTrue(home.contains("localLibraryMediaRepository.getHomeCatalog("))
        assertTrue(home.contains("Published local Home catalog with"))
        assertTrue(home.contains("downloadedMovies = localLibraryMovies"))
        assertTrue(home.contains("downloadedShows = localLibraryShows"))
        assertTrue(home.contains("offlineContinueWatching = localLibraryCatalog.continueWatching"))
        assertTrue(home.contains(".getDownloadsByStatusFlowScoped("))
        assertTrue(home.contains("listOf(DownloadStatus.COMPLETED)"))
        assertTrue(home.contains("legacyDownloadedMoviesFromCache"))
        assertTrue(home.contains("legacyDownloadedShowsFromCache"))
        assertFalse(home.contains("downloadRepository.getCompletedDownloadsFlow().first()"))
        assertTrue(home.contains("absDownloadRepository.getCompletedDownloadsFlow().first()"))
        assertTrue(
            home.indexOf("Published local Home catalog with") <
                home.indexOf(".getDownloadsByStatusFlowScoped(")
        )
        assertFalse(home.contains("scanEnabledRoots("))
    }

    @Test
    fun applicationBackfillsLocalArtworkIndexOffTheHomeCriticalPath() {
        val app = readSource("src/main/java/com/makd/afinity/AfinityApplication.kt")

        assertTrue(app.contains("localLibraryMediaRepository.hasIndexedMediaMissingArtwork()"))
        assertTrue(app.contains("localLibraryMediaRepository.hasIndexedMedia()"))
        assertTrue(app.contains("val needsIndexRebuild = !hasIndexedMedia"))
        assertTrue(app.contains("if (!needsIndexRebuild && !hasMissingArtwork) return@launch"))
        assertFalse(app.contains("localLibraryScanService.hasCompletedVideoDownloads()"))
        assertFalse(app.contains("if (!hasCompletedVideoDownloads) return@launch"))
        assertTrue(app.contains("localLibraryScanService.scanEnabledRootsWithArtworkBackfill("))
        assertTrue(app.contains("Repairing local library index in background"))
        assertTrue(app.contains("applicationScope.launch(Dispatchers.IO)"))
    }

    @Test
    fun startupShellDoesNotBlockLocalHomeOnRemoteBootstrap() {
        val navigation = readSource("src/main/java/com/makd/afinity/navigation/MainNavigationViewModel.kt")
        val homeScreen = readSource("src/main/java/com/makd/afinity/ui/home/HomeScreen.kt")
        val policy = readSource("src/main/java/com/makd/afinity/ui/home/HomeStartupContentPolicy.kt")

        assertTrue(navigation.contains("isRemoteBootstrapLoading = !isLoaded"))
        assertTrue(navigation.contains("isLoading = false"))
        assertFalse(navigation.contains("isLoading = !isLoaded"))
        assertTrue(homeScreen.contains("val startupContent = uiState.startupContentFlags()"))
        assertTrue(homeScreen.contains("val canShowRemoteContent = startupContent.canShowRemoteContent"))
        assertTrue(homeScreen.contains("val showDownloadedSections = startupContent.showDownloadedSections"))
        assertTrue(policy.contains("showDownloadedSections = !canShowRemoteContent || hasLocalCatalogContent"))
        assertTrue(policy.contains("useOfflineContinueWatching = !canShowRemoteContent"))
    }

    @Test
    fun detailAndScreenKeepLocalCatalogItemsOutOfCompletedDownloadFilter() {
        val viewModel = readSource("src/main/java/com/makd/afinity/ui/item/ItemDetailViewModel.kt")
        val screen = readSource("src/main/java/com/makd/afinity/ui/item/ItemDetailScreen.kt")

        assertTrue(viewModel.contains("loadLocalCatalogItem()?.also"))
        assertTrue(viewModel.contains("isLocalCatalogItem = loadedFromLocalCatalog"))
        assertTrue(viewModel.contains("resolveLocalCatalogEpisode(episode)"))
        assertTrue(viewModel.contains("val isDownloadedOnlyUi: StateFlow<Boolean>"))
        assertTrue(screen.contains("val isLocalCatalogItem = uiState.isLocalCatalogItem"))
        assertTrue(screen.contains("shouldFilterToDownloadedContent(isDownloadedOnlyUi, isLocalCatalogItem)"))
    }

    @Test
    fun localCatalogRepositoryOwnsRoomThreadingBoundary() {
        val repository = readSource("src/main/java/com/makd/afinity/data/local/LocalLibraryMediaRepository.kt")
        val fileSystem = readSource("src/main/java/com/makd/afinity/data/local/LocalLibraryFileSystem.kt")

        assertTrue(repository.contains("import kotlinx.coroutines.Dispatchers"))
        assertTrue(repository.contains("import kotlinx.coroutines.withContext"))
        assertTrue(repository.contains("): LocalLibraryMediaSnapshot = withContext(Dispatchers.IO)"))
        assertFalse(repository.contains("private val fileSystem: LocalLibraryFileSystem"))
        assertFalse(repository.contains("fileSystem.playerUri("))
        assertTrue(repository.contains("path = localCatalogPath()"))
        assertTrue(repository.contains("suspend fun hasIndexedMediaMissingArtwork()"))
        assertTrue(repository.contains("images = artwork.toAfinityImages()"))
        assertTrue(repository.contains("val seasonImages = seasonEntries.toSeasonImages()"))
        assertTrue(repository.contains("val showImages = seriesEntries.map { it.episode }.toShowImages()"))
        assertTrue(repository.contains("private fun LocalMediaArtwork.toAfinityImages()"))
        assertTrue(repository.contains("primary = primaryUri?.toAndroidAssetUri()"))
        assertFalse(repository.contains("primary = seasonPrimaryUri?.let(Uri::parse)"))
        assertTrue(repository.contains("primary = firstArtworkUri { it.seasonPrimaryUri }"))
        assertTrue(repository.contains("primary = images.firstUri { it.showPrimary }"))
        assertFalse(repository.contains("it.showPrimary ?: it.primary"))
        assertFalse(repository.contains("it.seasonPrimaryUri }\n                    ?: images.firstUri"))
        assertTrue(repository.contains("hasScopedEpisodeItemDisplayArtwork()"))
        assertTrue(repository.contains("matchesEpisodeItemArtworkPath(relativePath)"))
        assertTrue(repository.contains("hasLegacyJavaFileUri()"))
        assertTrue(repository.contains("Uri.fromFile(File(path))"))
        assertTrue(fileSystem.contains("?.toPath()?.toUri()?.toASCIIString()"))
        assertFalse(fileSystem.contains("?.toURI()?.toString()"))
        assertTrue(
            repository.contains(
                "private fun LocalMediaFileRecord.localCatalogPath(): String = \"local://${'$'}mediaFileId\""
            )
        )
    }

    @Test
    fun downloadedArtworkRefreshNormalizesSidecarImagesBeforeLocalScan() {
        val downloadsViewModel = readSource("src/main/java/com/makd/afinity/ui/downloads/DownloadsViewModel.kt")
        val transferRunner =
            readSource("src/main/java/com/makd/afinity/data/repository/download/MediaDownloadTransferRunner.kt")
        val scanService = readSource("src/main/java/com/makd/afinity/data/local/LocalLibraryScanService.kt")
        val backfillService =
            readSource("src/main/java/com/makd/afinity/data/local/LocalLibraryArtworkBackfillService.kt")
        val originRefresher =
            readSource("src/main/java/com/makd/afinity/data/local/LocalLibraryOriginArtworkRefresher.kt")

        assertTrue(downloadsViewModel.contains("refreshableLocalLibraryArtworkCount()"))
        assertTrue(downloadsViewModel.contains("refreshLocalLibraryArtworkFromOrigins"))
        assertTrue(downloadsViewModel.contains("localArtworkRefreshCount"))
        assertTrue(downloadsViewModel.contains("updateLocalArtworkRefreshCount()"))
        assertTrue(downloadsViewModel.contains("completedVideoCount + localArtworkCount"))
        assertFalse(downloadsViewModel.contains("No downloaded videos to refresh"))
        val settingsScreen =
            readSource("src/main/java/com/makd/afinity/ui/settings/downloads/DownloadSettingsScreen.kt")
        assertTrue(settingsScreen.contains("localArtworkRefreshCount = uiState.localArtworkRefreshCount"))
        assertTrue(settingsScreen.contains("val totalRefreshableVideos = completedVideoCount + localArtworkRefreshCount"))
        assertTrue(settingsScreen.contains("enabled = canModify && totalRefreshableVideos > 0"))
        assertFalse(settingsScreen.contains("enabled = canModify && completedVideoCount > 0"))
        assertTrue(
            transferRunner.indexOf("localLibraryScanService.backfillDownloadedArtwork(listOf(completedDownload))") <
                transferRunner.indexOf("if (artworkBackfill.writtenFiles > 0)")
        )
        assertTrue(transferRunner.contains("if (artworkBackfill.writtenFiles > 0)"))
        assertTrue(scanService.contains("LocalLibraryArtworkBackfillService"))
        assertTrue(scanService.contains("LocalLibraryOriginArtworkRefresher"))
        assertTrue(scanService.contains("scanEnabledRootsWithArtworkBackfill("))
        assertTrue(scanService.contains("val localScan = scanEnabledRoots(visibilityContext)"))
        assertTrue(
            scanService.indexOf("val localScan = scanEnabledRoots(visibilityContext)") <
                scanService.indexOf("val originRefresh = refreshLocalLibraryArtworkFromOrigins()")
        )
        assertTrue(backfillService.contains("fileSystem.writeBytes("))
        assertTrue(backfillService.contains("seasonImageDirectories(sourceRoots)"))
        assertTrue(backfillService.contains("showImageDirectories(sourceRoots)"))
        assertTrue(backfillService.contains("LocalLibraryArtworkPaths.itemImagesDirectory("))
        assertTrue(originRefresher.contains("LocalLibraryArtworkOrigin("))
        assertTrue(originRefresher.contains("identity.jellyfinItemId"))
        assertTrue(originRefresher.contains("needsOriginArtworkRefresh("))
        assertTrue(originRefresher.contains("LocalLibraryArtworkPaths.itemImagesDirectory("))
        assertTrue(originRefresher.contains("updateSidecarParentIdentity("))
    }

    @Test
    fun mediaItemCardsUseOnlyEpisodeArtworkForLocalCatalogVideos() {
        val card = readSource("src/main/java/com/makd/afinity/ui/components/MediaItemCard.kt")
        val videoSurfaces =
            listOf(
                card,
                readSource("src/main/java/com/makd/afinity/ui/components/ContinueWatchingCard.kt"),
                readSource("src/main/java/com/makd/afinity/ui/components/EpisodeListCard.kt"),
                readSource("src/main/java/com/makd/afinity/ui/home/components/HomeSections.kt"),
                readSource("src/main/java/com/makd/afinity/ui/item/components/EpisodeDetailOverlay.kt"),
            )

        assertTrue(card.contains("private fun AfinityItem.cardImageUrl()"))
        assertTrue(card.contains("if (this is AfinityEpisode)"))
        val episodeBranch =
            card.substring(
                card.indexOf("if (this is AfinityEpisode)"),
                card.indexOf("} else {", card.indexOf("if (this is AfinityEpisode)")),
            )
        assertTrue(episodeBranch.contains("images.primaryImageUrl"))
        assertTrue(episodeBranch.contains("images.thumbImageUrl"))
        assertTrue(episodeBranch.contains("images.backdropImageUrl"))
        assertFalse(episodeBranch.contains("images.showThumbImageUrl"))
        assertFalse(episodeBranch.contains("images.showBackdropImageUrl"))
        assertFalse(episodeBranch.contains("images.showPrimaryImageUrl"))
        videoSurfaces.forEach { source ->
            assertFalse(source.contains("showThumbImageUrl"))
            assertFalse(source.contains("showBackdropImageUrl"))
            assertFalse(source.contains("showPrimaryImageUrl"))
            assertFalse(source.contains("showThumbBlurHash"))
            assertFalse(source.contains("showBackdropBlurHash"))
            assertFalse(source.contains("showPrimaryBlurHash"))
        }
    }

    @Test
    fun detailDoesNotLaunchRemoteFetchesForOfflineFallbackItems() {
        val viewModel = readSource("src/main/java/com/makd/afinity/ui/item/ItemDetailViewModel.kt")
        val loadItemBlock =
            viewModel.substring(
                viewModel.indexOf("fun loadItem()"),
                viewModel.indexOf("private fun launchParallelFetches()"),
            )

        assertFalse(
            loadItemBlock.contains(
                "if (canLoadRemoteContent) {\n                    launchParallelFetches()\n                }"
            )
        )
        assertTrue(
            loadItemBlock.contains(
                "if (canLoadRemoteContent && !loadedFromOfflineCache) {\n                    launchParallelFetches()"
            )
        )
        assertTrue(viewModel.contains("if (!offlineModeManager.canLoadRemoteContentNow()) return@launch"))
    }

    @Test
    fun offlineLocalCatalogVisibilityFallsBackToSavedProfileUser() {
        val detail = readSource("src/main/java/com/makd/afinity/ui/item/ItemDetailViewModel.kt")
        val navigation = readSource("src/main/java/com/makd/afinity/navigation/MainNavigationViewModel.kt")
        val home = readSource("src/main/java/com/makd/afinity/ui/home/HomeViewModel.kt")

        assertTrue(detail.contains("private suspend fun localLibraryVisibility()"))
        assertTrue(detail.contains("?: preferencesRepository.getCurrentUserId()"))
        assertTrue(navigation.contains("PreferencesRepository"))
        assertTrue(navigation.contains("?: preferencesRepository.getCurrentUserId()"))
        assertTrue(home.contains("?: preferencesRepository.getCurrentUserId()?.let"))
    }

    @Test
    fun routeAndPlayerWrapperRestoreThroughLocalCatalog() {
        val navigation = readSource("src/main/java/com/makd/afinity/navigation/MainNavigationViewModel.kt")
        val playerWrapper = readSource("src/main/java/com/makd/afinity/ui/player/PlayerWrapperViewModel.kt")
        val player = readSource("src/main/java/com/makd/afinity/ui/player/PlayerViewModel.kt")

        assertTrue(navigation.contains("resolveLocalPlayableItem(item)"))
        assertTrue(navigation.contains("localLibraryMediaRepository.resolvePlayableItem("))
        assertTrue(navigation.contains("PreferencesRepository"))
        assertTrue(navigation.contains("preferencesRepository.getCurrentUserId()"))
        assertTrue(playerWrapper.contains("loadLocalCatalogItem(itemId, profileUserId)"))
        assertTrue(playerWrapper.contains("localLibraryMediaRepository.resolvePlayableItem("))
        assertTrue(playerWrapper.contains("PreferencesRepository"))
        assertTrue(playerWrapper.contains("preferencesRepository.getCurrentUserId()"))
        assertFalse(playerWrapper.contains("getAllUsers().firstOrNull()"))
        assertTrue(player.contains("sessionManager.currentSession.value?.userId?.toString()"))
        assertTrue(player.contains("currentUserId = currentProfileUserId()"))
    }

    @Test
    fun localCatalogPlaybackFlowKeepsLocalSourceThroughPlayerLaunch() {
        val source = readSource("src/main/java/com/makd/afinity/data/models/media/AfinitySource.kt")
        val detailScreen = readSource("src/main/java/com/makd/afinity/ui/item/ItemDetailScreen.kt")
        val playerWrapper = readSource("src/main/java/com/makd/afinity/ui/player/PlayerWrapperViewModel.kt")
        val player = readSource("src/main/java/com/makd/afinity/ui/player/PlayerViewModel.kt")

        assertTrue(source.contains("firstOrNull { it.type == AfinitySourceType.LOCAL } ?: firstOrNull()"))
        assertTrue(detailScreen.contains("val localSource = item.sources.firstOrNull { it.type == AfinitySourceType.LOCAL }"))
        assertTrue(detailScreen.contains("selectedSource?.type == AfinitySourceType.LOCAL"))
        assertTrue(detailScreen.contains("mediaSourceId = localSource?.id.orEmpty()"))
        assertTrue(playerWrapper.contains("loadedItem = loadLocalCatalogItem(itemId, profileUserId)"))
        assertTrue(playerWrapper.contains("Loaded item from local catalog after cache/API miss"))
        assertTrue(player.contains("private suspend fun resolveLocalPlaybackUrl(mediaSource: AfinitySource): String?"))
        assertTrue(player.contains("localPlaybackSourceRepository.resolve("))
        assertTrue(player.contains("LocalPlaybackResolutionRequest("))
        assertTrue(player.contains("if (mediaSource?.type != AfinitySourceType.LOCAL) return"))
    }

    @Test
    fun jellyfinFolderClickNavigatesToFolderContentRoute() {
        val navigation = readSource("src/main/java/com/makd/afinity/navigation/MainNavigation.kt")
        val navigationPolicy =
            readSource("src/main/java/com/makd/afinity/navigation/HomeItemNavigationPolicy.kt")
        val libraryContent =
            readSource("src/main/java/com/makd/afinity/ui/library/LibraryContentViewModel.kt")
        val localCatalog =
            readSource("src/main/java/com/makd/afinity/data/local/LocalLibraryMediaRepository.kt")

        assertTrue(navigation.contains("import com.makd.afinity.data.models.media.AfinityFolder"))
        assertTrue(navigation.contains("fun navigateToFolderContent(folder: AfinityFolder)"))
        assertTrue(navigation.contains("Destination.createLibraryContentRoute("))
        assertTrue(navigation.contains("libraryId = folder.id.toString()"))
        assertTrue(navigation.contains("libraryName = folder.name"))
        assertTrue(navigation.contains("homeItemNavigationTarget(item)"))
        assertTrue(navigation.contains("HomeItemNavigationTarget.Container"))
        assertTrue(navigationPolicy.contains("item is AfinityFolder"))
        assertTrue(navigationPolicy.contains("item is AfinityShow && item.isLocalCatalogContainer()"))
        assertTrue(navigationPolicy.contains("item is AfinitySeason && item.isLocalCatalogContainer()"))
        assertTrue(navigationPolicy.contains("status == \"Local\""))
        assertTrue(navigationPolicy.contains("source.type == AfinitySourceType.LOCAL"))
        assertTrue(navigation.contains("route.startsWith(\"library_content/\")"))
        assertTrue(libraryContent.contains("private val localLibraryMediaRepository: LocalLibraryMediaRepository"))
        assertTrue(libraryContent.contains("private fun loadLocalLibraryContent("))
        assertTrue(libraryContent.contains("localLibraryMediaRepository"))
        assertTrue(libraryContent.contains(".getContentForContainer("))
        assertTrue(libraryContent.contains("flowOf(PagingData.from(localItems))"))
        assertTrue(localCatalog.contains("suspend fun getContentForContainer("))
        assertTrue(localCatalog.contains("show.name.matchesLocalContainerName(containerName)"))
    }

    @Test
    fun sessionStartPublishesCurrentProfilePreferenceForColdStartOfflinePlayback() {
        val sessionManager = readSource("src/main/java/com/makd/afinity/data/manager/SessionManager.kt")
        val authRepository = readSource("src/main/java/com/makd/afinity/data/repository/auth/JellyfinAuthRepository.kt")

        assertTrue(sessionManager.contains("PreferencesRepository"))
        assertTrue(sessionManager.contains("preferencesRepository.setCurrentServerId(serverId)"))
        assertTrue(sessionManager.contains("preferencesRepository.setCurrentUserId(userId.toString())"))
        assertTrue(sessionManager.contains("preferencesRepository.setCurrentUserId(null)"))
        assertTrue(sessionManager.contains("clearActiveSessionState()"))
        assertTrue(sessionManager.contains("No session to logout from"))
        assertTrue(authRepository.contains("sessionManager.clearActiveSessionState()"))
        assertTrue(
            authRepository.contains(
                "if (!securePreferencesRepository.hasValidAuthData()) {\n                    sessionManager.clearActiveSessionState()"
            )
        )
    }

    @Test
    fun databaseMigrationBackfillsLocalEpisodeParentIdentity() {
        val migrations = readSource("src/main/java/com/makd/afinity/data/database/DatabaseMigrations.kt")

        assertTrue(migrations.contains("ALTER TABLE local_media_identities ADD COLUMN jellyfinSeriesId TEXT"))
        assertTrue(migrations.contains("ALTER TABLE local_media_identities ADD COLUMN jellyfinSeasonId TEXT"))
        assertTrue(migrations.contains("UPDATE local_media_identities"))
        assertTrue(migrations.contains("FROM episodes"))
        assertTrue(migrations.contains("episodes.seriesId"))
        assertTrue(migrations.contains("episodes.seasonId"))
        assertTrue(migrations.contains("FROM downloads"))
        assertTrue(migrations.contains("downloads.seriesId"))
    }

    private fun readSource(relativePath: String): String = File(relativePath).readText()
}
