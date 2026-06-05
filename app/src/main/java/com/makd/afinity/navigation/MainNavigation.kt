@file:UnstableApi

package com.makd.afinity.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.makd.afinity.data.manager.OfflineModeManager
import com.makd.afinity.data.models.media.AfinityEpisode
import com.makd.afinity.data.models.media.AfinityItem
import com.makd.afinity.data.models.media.AfinityMovie
import com.makd.afinity.data.models.media.AfinitySeason
import com.makd.afinity.data.models.media.AfinityShow
import com.makd.afinity.data.models.media.AfinityVideo
import com.makd.afinity.data.models.media.preferredPlaybackSource
import com.makd.afinity.data.repository.AudiobookshelfRepository
import com.makd.afinity.data.repository.JellyseerrRepository
import com.makd.afinity.data.repository.watchlist.WatchlistRepository
import com.makd.afinity.data.updater.UpdateManager
import com.makd.afinity.ui.audiobookshelf.genre.AudiobookshelfGenreResultsScreen
import com.makd.afinity.ui.audiobookshelf.item.AudiobookshelfItemScreen
import com.makd.afinity.ui.audiobookshelf.item.series.AudiobookshelfSeriesScreen
import com.makd.afinity.ui.audiobookshelf.libraries.AudiobookshelfLibrariesScreen
import com.makd.afinity.ui.audiobookshelf.player.AudiobookshelfPlayerScreen
import com.makd.afinity.ui.audiobookshelf.player.components.MiniPlayer
import com.makd.afinity.ui.components.AfinitySplashScreen
import com.makd.afinity.ui.components.PinDialog
import com.makd.afinity.ui.favorites.FavoritesScreen
import com.makd.afinity.ui.home.HomeScreen
import com.makd.afinity.ui.item.ItemDetailScreen
import com.makd.afinity.ui.libraries.LibrariesScreen
import com.makd.afinity.ui.library.LibraryContentScreen
import com.makd.afinity.ui.login.LoginScreen
import com.makd.afinity.ui.main.MainViewModel
import com.makd.afinity.ui.person.PersonScreen
import com.makd.afinity.ui.player.PlayerLauncher
import com.makd.afinity.ui.requests.FilterParams
import com.makd.afinity.ui.requests.FilterType
import com.makd.afinity.ui.requests.FilteredMediaScreen
import com.makd.afinity.ui.requests.RequestsScreen
import com.makd.afinity.ui.search.GenreResultsScreen
import com.makd.afinity.ui.search.SearchScreen
import com.makd.afinity.ui.settings.LicensesScreen
import com.makd.afinity.ui.settings.SettingsScreen
import com.makd.afinity.ui.settings.appearance.AppearanceOptionsScreen
import com.makd.afinity.ui.settings.downloads.DownloadSettingsScreen
import com.makd.afinity.ui.settings.player.PlayerOptionsScreen
import com.makd.afinity.ui.settings.servers.AddEditServerScreen
import com.makd.afinity.ui.settings.servers.ServerManagementScreen
import com.makd.afinity.ui.settings.update.GlobalUpdateDialog
import com.makd.afinity.ui.watchlist.WatchlistScreen
import kotlinx.coroutines.launch
import timber.log.Timber

val LocalPlayerOffset = compositionLocalOf { 0.dp }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel = hiltViewModel(),
    viewModel: MainNavigationViewModel = hiltViewModel(),
    updateManager: UpdateManager,
    offlineModeManager: OfflineModeManager,
    widthSizeClass: WindowWidthSizeClass,
) {
    val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val watchlistRepository: WatchlistRepository =
        hiltViewModel<MainNavigationViewModel>().watchlistRepository
    val watchlistCount by watchlistRepository.watchlistCountFlow.collectAsStateWithLifecycle()
    val jellyseerrRepository: JellyseerrRepository =
        hiltViewModel<MainNavigationViewModel>().jellyseerrRepository
    val isJellyseerrAuthenticated by
        jellyseerrRepository.isAuthenticated.collectAsStateWithLifecycle()
    val audiobookshelfRepository: AudiobookshelfRepository =
        hiltViewModel<MainNavigationViewModel>().audiobookshelfRepository
    val isAudiobookshelfAuthenticated by
        audiobookshelfRepository.isAuthenticated.collectAsStateWithLifecycle()
    val hasLiveTvAccess by viewModel.hasLiveTvAccess.collectAsStateWithLifecycle()
    val appLoadingState by viewModel.appLoadingState.collectAsStateWithLifecycle()
    val capabilityPolicy by viewModel.capabilityPolicy.collectAsStateWithLifecycle()
    val isOffline by offlineModeManager.hardOffline.collectAsStateWithLifecycle(initialValue = false)
    val canLoadRemoteContent by
        offlineModeManager.canLoadRemoteContent.collectAsStateWithLifecycle(initialValue = true)
    val canUseAnySearchBackend =
        canLoadRemoteContent || isJellyseerrAuthenticated || isAudiobookshelfAuthenticated
    val audiobookshelfPlaybackState by
        viewModel.audiobookshelfPlaybackManager.playbackState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var showParentPinDialog by remember { mutableStateOf(false) }
    var parentPinError by remember { mutableStateOf<String?>(null) }
    var pendingParentProtectedNavigation by remember { mutableStateOf(false) }

    fun navigateToSettings() {
        if (capabilityPolicy.canOpenSettings) {
            navController.navigate(Destination.createSettingsRoute())
        } else {
            parentPinError = null
            showParentPinDialog = true
        }
    }

    fun isParentProtectedRoute(route: String?): Boolean {
        if (route == null) return false
        return route == Destination.SETTINGS_ROUTE ||
            route == Destination.DOWNLOAD_SETTINGS_ROUTE ||
            route == Destination.PLAYER_OPTIONS_ROUTE ||
            route == Destination.APPEARANCE_OPTIONS_ROUTE ||
            route == Destination.SERVER_MANAGEMENT_ROUTE ||
            route.startsWith("add_edit_server") ||
            route == Destination.SEARCH_ROUTE ||
            route == Destination.REQUESTS.route
    }

    fun isOfflineAllowedRoute(route: String?): Boolean {
        if (route == null) return true
        return route == Destination.HOME.route ||
            route == Destination.SETTINGS_ROUTE ||
            route == Destination.DOWNLOAD_SETTINGS_ROUTE ||
            route.startsWith("item_detail/") ||
            route.startsWith("player/") ||
            route.startsWith("audiobookshelf/item/") ||
            route.startsWith("audiobookshelf/player/")
    }

    fun createItemDetailRoute(item: AfinityItem): String =
        Destination.createItemDetailRoute(
            itemId = item.id.toString(),
            itemType =
                when (item) {
                    is AfinityShow -> "Series"
                    is AfinitySeason -> "Season"
                    else -> null
                },
            seriesId = (item as? AfinitySeason)?.seriesId?.toString(),
        )

    fun navigateToItemDetail(item: AfinityItem) {
        navController.navigate(createItemDetailRoute(item))
    }

    fun launchPlayableItem(item: AfinityItem, fallbackToDetail: Boolean = true) {
        coroutineScope.launch {
            try {
                val playableItem = viewModel.resolvePlayableItem(item)

                if (playableItem == null) {
                    Timber.w("Could not resolve playable item for: ${item.name}")
                    if (fallbackToDetail) navigateToItemDetail(item)
                    return@launch
                }

                val source = playableItem.sources.preferredPlaybackSource()
                if (source == null) {
                    if (!playableItem.canPlay) {
                        Timber.w(
                            "Playable item has no source and cannot play: ${playableItem.name}"
                        )
                        if (fallbackToDetail) navigateToItemDetail(item)
                        return@launch
                    }

                    Timber.w(
                        "Playable item has no preloaded source, launching player to resolve details: ${playableItem.name}"
                    )
                }

                PlayerLauncher.launch(
                    context = navController.context,
                    itemId = playableItem.id,
                    mediaSourceId = source?.id.orEmpty(),
                    audioStreamIndex = null,
                    subtitleStreamIndex = null,
                    startPositionMs = playableItem.playbackPositionTicks / 10_000,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch playable item for: ${item.name}")
                if (fallbackToDetail) navigateToItemDetail(item)
            }
        }
    }

    fun handleItemClick(item: AfinityItem) {
        val shouldDirectPlay =
            capabilityPolicy.isKidModeEnabled &&
                !capabilityPolicy.isParentUnlocked &&
                when (item) {
                    is AfinityEpisode,
                    is AfinityMovie,
                    is AfinityVideo -> true
                    else -> false
                }

        if (shouldDirectPlay) {
            launchPlayableItem(item)
        } else {
            navigateToItemDetail(item)
        }
    }

    val shouldShowNavigation =
        currentDestination?.route?.let { route ->
            !route.startsWith("library_content/") &&
                !route.startsWith("studio_content/") &&
                !route.startsWith("item_detail/") &&
                !route.startsWith("episodes/") &&
                !route.startsWith("player/") &&
                !route.startsWith("person/") &&
                route != "search" &&
                !route.startsWith("genre_results/") &&
                !route.startsWith("filtered_media/") &&
                route != "settings" &&
                route != "download_settings" &&
                route != "player_options" &&
                route != "appearance_options" &&
                route != "licenses" &&
                route != "server_management" &&
                !route.startsWith("add_edit_server") &&
                !route.startsWith("login") &&
                !route.startsWith("audiobookshelf/library/") &&
                !route.startsWith("audiobookshelf/item/") &&
                !route.startsWith("audiobookshelf/series/") &&
                !route.startsWith("audiobookshelf/genre/") &&
                !route.startsWith("audiobookshelf/player/")
        } ?: true

    val useNavRail = widthSizeClass != WindowWidthSizeClass.Compact

    LaunchedEffect(isOffline, currentDestination) {
        if (isOffline && currentDestination != null) {
            val currentRoute = currentDestination.route
            if (!isOfflineAllowedRoute(currentRoute)) {
                Timber.d("Offline mode blocked route $currentRoute, navigating to HOME")
                navController.navigate(Destination.HOME.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    LaunchedEffect(currentDestination?.route) {
        offlineModeManager.requestConnectivityProbe("navigation")
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        coroutineScope.launch {
                            offlineModeManager.requestConnectivityProbe("app foreground")
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        viewModel.lockParent()
                        pendingParentProtectedNavigation = false
                    }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(capabilityPolicy, currentDestination, isOffline, canUseAnySearchBackend) {
        val currentRoute = currentDestination?.route ?: return@LaunchedEffect
        val isSettingsRoute = isParentProtectedRoute(currentRoute) && currentRoute != Destination.SEARCH_ROUTE && currentRoute != Destination.REQUESTS.route
        val shouldBlockRoute =
            (isSettingsRoute && !capabilityPolicy.canOpenSettings) ||
                (currentRoute == Destination.REQUESTS.route && !capabilityPolicy.canManageRequests) ||
                (currentRoute == Destination.SEARCH_ROUTE &&
                    (isOffline || !capabilityPolicy.canUseSearch || !canUseAnySearchBackend))

        if (shouldBlockRoute) {
            Timber.d("Blocked protected route: $currentRoute")
            navController.navigate(Destination.HOME.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        if (isParentProtectedRoute(currentRoute)) {
            pendingParentProtectedNavigation = false
        } else if (
            capabilityPolicy.isKidModeEnabled &&
                capabilityPolicy.isParentUnlocked &&
                !pendingParentProtectedNavigation
        ) {
            viewModel.lockParent()
        }
    }

    if (showParentPinDialog) {
        PinDialog(
            title = "Parent PIN",
            message = "Enter the PIN to open settings.",
            confirmText = "Unlock",
            errorText = parentPinError,
            onConfirm = { pin ->
                coroutineScope.launch {
                    if (viewModel.verifyParentPin(pin)) {
                        parentPinError = null
                        showParentPinDialog = false
                        pendingParentProtectedNavigation = true
                        navController.navigate(Destination.createSettingsRoute())
                    } else {
                        parentPinError = "Incorrect PIN"
                    }
                }
            },
            onDismiss = {
                parentPinError = null
                showParentPinDialog = false
            },
        )
    }

    AnimatedContent(
        targetState = appLoadingState.isLoading,
        transitionSpec = {
            fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
        },
        label = "MainNavigationLoadingState",
    ) { isLoading ->
        if (isLoading) {
            AfinitySplashScreen(
                statusText = appLoadingState.loadingPhase.ifEmpty { "Loading..." },
                progress = appLoadingState.loadingProgress,
                modifier = modifier,
            )
        } else {
            NavigationSuiteScaffold(
                layoutType =
                    when {
                        !shouldShowNavigation -> NavigationSuiteType.None
                        useNavRail -> NavigationSuiteType.NavigationRail
                        else -> NavigationSuiteType.NavigationBar
                    },
                navigationSuiteItems = {
                    Destination.entries.forEach { destination ->
                        if (isOffline && destination != Destination.HOME) {
                            return@forEach
                        }

                        if (destination == Destination.LIBRARIES) {
                            return@forEach
                        }

                        if (destination == Destination.WATCHLIST && watchlistCount == 0) {
                            return@forEach
                        }

                        if (destination == Destination.REQUESTS && !capabilityPolicy.canManageRequests) {
                            return@forEach
                        }

                        if (destination == Destination.REQUESTS && !isJellyseerrAuthenticated) {
                            return@forEach
                        }

                        if (
                            destination == Destination.AUDIOBOOKS && !isAudiobookshelfAuthenticated
                        ) {
                            return@forEach
                        }

                        if (destination == Destination.LIVE_TV && !hasLiveTvAccess) {
                            return@forEach
                        }

                        val selected =
                            currentDestination?.hierarchy?.any { it.route == destination.route } ==
                                true

                        item(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    painter =
                                        painterResource(
                                            id =
                                                if (selected) {
                                                    destination.selectedIconRes
                                                } else {
                                                    destination.unselectedIconRes
                                                }
                                        ),
                                    contentDescription = destination.title,
                                )
                            },
                            label = {
                                if (selected) {
                                    Text(
                                        text = destination.title,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                        )
                    }
                },
                modifier = modifier,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                navigationSuiteColors =
                    androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
                        .colors(
                            navigationBarContainerColor = MaterialTheme.colorScheme.surface,
                            navigationRailContainerColor = MaterialTheme.colorScheme.surface,
                        ),
            ) {
                val isOnAudiobookshelfPlayer =
                    currentDestination?.route?.startsWith("audiobookshelf/player/") == true
                val showMiniPlayer =
                    audiobookshelfPlaybackState.sessionId != null && !isOnAudiobookshelfPlayer
                val globalPlayerOffset by
                    animateDpAsState(
                        targetValue = if (showMiniPlayer) 112.dp else 0.dp,
                        label = "globalPlayerOffset",
                    )
                CompositionLocalProvider(LocalPlayerOffset provides globalPlayerOffset) {
                    SharedTransitionLayout {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NavHost(
                                navController = navController,
                                startDestination = Destination.HOME.route,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                composable(Destination.HOME.route) {
                                    HomeScreen(
                                        onItemClick = { item -> handleItemClick(item) },
                                        onPlayClick = { item ->
                                            launchPlayableItem(item, fallbackToDetail = false)
                                        },
                                        onProfileClick = {
                                            navigateToSettings()
                                        },
                                        onAbsItemClick = { itemId ->
                                            navController.navigate(
                                                Destination.createAudiobookshelfItemRoute(itemId)
                                            )
                                        },
                                        navController = navController,
                                        mainUiState = mainUiState,
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(Destination.LIBRARIES.route) {
                                    LibrariesScreen(
                                        onLibraryClick = { library ->
                                            val route =
                                                Destination.createLibraryContentRoute(
                                                    libraryId = library.id.toString(),
                                                    libraryName = library.name,
                                                )
                                            navController.navigate(route)
                                        },
                                        onProfileClick = {
                                            navigateToSettings()
                                        },
                                        navController = navController,
                                        mainUiState = mainUiState,
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(
                                    route = Destination.LIBRARY_CONTENT_ROUTE,
                                    arguments =
                                        listOf(
                                            navArgument("libraryId") { type = NavType.StringType },
                                            navArgument("libraryName") { type = NavType.StringType },
                                        ),
                                ) {
                                    LibraryContentScreen(
                                        onItemClick = { item -> handleItemClick(item) },
                                        onProfileClick = {
                                            navigateToSettings()
                                        },
                                        navController = navController,
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(
                                    route = Destination.STUDIO_CONTENT_ROUTE,
                                    arguments =
                                        listOf(
                                            navArgument("studioName") { type = NavType.StringType }
                                        ),
                                ) {
                                    LibraryContentScreen(
                                        onItemClick = { item -> handleItemClick(item) },
                                        onProfileClick = {
                                            navigateToSettings()
                                        },
                                        navController = navController,
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(
                                    route = Destination.ITEM_DETAIL_ROUTE,
                                    arguments =
                                        listOf(
                                            navArgument("itemId") { type = NavType.StringType },
                                            navArgument("itemType") {
                                                type = NavType.StringType
                                                nullable = true
                                                defaultValue = null
                                            },
                                            navArgument("seriesId") {
                                                type = NavType.StringType
                                                nullable = true
                                                defaultValue = null
                                            },
                                        ),
                                ) {
                                    ItemDetailScreen(
                                        navController = navController,
                                        onPlayClick = { item, selection ->
                                            PlayerLauncher.launch(
                                                context = navController.context,
                                                itemId = item.id,
                                                mediaSourceId =
                                                    selection?.mediaSourceId
                                                        ?: item.sources.firstOrNull()?.id
                                                        ?: "",
                                                audioStreamIndex = selection?.audioStreamIndex,
                                                subtitleStreamIndex =
                                                    selection?.subtitleStreamIndex,
                                                startPositionMs = selection?.startPositionMs ?: 0L,
                                            )
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(
                                    route = Destination.EPISODE_LIST_ROUTE,
                                    arguments =
                                        listOf(
                                            navArgument("seasonId") { type = NavType.StringType },
                                            navArgument("seasonName") { type = NavType.StringType },
                                        ),
                                ) { backStackEntry ->
                                    ItemDetailScreen(
                                        onPlayClick = { item, selection ->
                                            if (selection != null) {
                                                PlayerLauncher.launch(
                                                    context = navController.context,
                                                    itemId = item.id,
                                                    mediaSourceId = selection.mediaSourceId,
                                                    audioStreamIndex = selection.audioStreamIndex,
                                                    subtitleStreamIndex =
                                                        selection.subtitleStreamIndex,
                                                    startPositionMs = selection.startPositionMs,
                                                )
                                            }
                                        },
                                        navController = navController,
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(
                                    route = Destination.PERSON_ROUTE,
                                    arguments =
                                        listOf(
                                            navArgument("personId") { type = NavType.StringType }
                                        ),
                                ) {
                                    PersonScreen(
                                        navController = navController,
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(Destination.FAVORITES.route) {
                                    FavoritesScreen(
                                        onItemClick = { item -> handleItemClick(item) },
                                        onPlayClick = { item ->
                                            launchPlayableItem(item, fallbackToDetail = false)
                                        },
                                        onPersonClick = { personId ->
                                            val route = Destination.createPersonRoute(personId)
                                            navController.navigate(route)
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        mainUiState = mainUiState,
                                        navController = navController,
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(Destination.WATCHLIST.route) {
                                    WatchlistScreen(
                                        onItemClick = { item -> handleItemClick(item) },
                                        onPlayClick = { item ->
                                            launchPlayableItem(item, fallbackToDetail = false)
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        mainUiState = mainUiState,
                                        navController = navController,
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(Destination.REQUESTS.route) {
                                    RequestsScreen(
                                        onSearchClick = {
                                            navController.navigate(Destination.SEARCH_ROUTE)
                                        },
                                        onProfileClick = {
                                            navigateToSettings()
                                        },
                                        mainUiState = mainUiState,
                                        onNavigateToFilteredMedia = { filterParams ->
                                            val route =
                                                Destination.createFilteredMediaRoute(
                                                    filterType = filterParams.type.name,
                                                    filterId = filterParams.id,
                                                    filterName = filterParams.name,
                                                )
                                            navController.navigate(route)
                                        },
                                        onItemClick = { jellyfinItemId, itemType ->
                                            val route =
                                                Destination.createItemDetailRoute(
                                                    itemId = jellyfinItemId,
                                                    itemType = itemType,
                                                )
                                            navController.navigate(route)
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(Destination.LIVE_TV.route) {
                                    com.makd.afinity.ui.livetv.LiveTvScreen(
                                        navController = navController,
                                        mainUiState = mainUiState,
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(
                                    route = Destination.FILTERED_MEDIA_ROUTE,
                                    arguments =
                                        listOf(
                                            navArgument("filterType") { type = NavType.StringType },
                                            navArgument("filterId") { type = NavType.IntType },
                                            navArgument("filterName") { type = NavType.StringType },
                                        ),
                                ) { backStackEntry ->
                                    val filterTypeString =
                                        backStackEntry.arguments?.getString("filterType")
                                            ?: return@composable
                                    val filterId =
                                        backStackEntry.arguments?.getInt("filterId")
                                            ?: return@composable
                                    val filterName =
                                        backStackEntry.arguments?.getString("filterName")
                                            ?: return@composable

                                    val filterType = FilterType.valueOf(filterTypeString)
                                    val filterParams =
                                        FilterParams(filterType, filterId, filterName)

                                    FilteredMediaScreen(
                                        filterParams = filterParams,
                                        onSearchClick = {
                                            navController.navigate(Destination.SEARCH_ROUTE)
                                        },
                                        onProfileClick = {
                                            navigateToSettings()
                                        },
                                        mainUiState = mainUiState,
                                        onItemClick = { jellyfinItemId, itemType ->
                                            val route =
                                                Destination.createItemDetailRoute(
                                                    itemId = jellyfinItemId,
                                                    itemType = itemType,
                                                )
                                            navController.navigate(route)
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(Destination.SEARCH_ROUTE) {
                                    SearchScreen(
                                        onBackClick = { navController.popBackStack() },
                                        onItemClick = { item -> handleItemClick(item) },
                                        onSeriesClick = { seriesId ->
                                            val route =
                                                Destination.createItemDetailRoute(
                                                    itemId = seriesId,
                                                    itemType = "Series",
                                                )
                                            navController.navigate(route)
                                        },
                                        onGenreClick = { genre ->
                                            val route = Destination.createGenreResultsRoute(genre)
                                            navController.navigate(route)
                                        },
                                        onAudiobookshelfItemClick = { itemId ->
                                            navController.navigate(
                                                Destination.createAudiobookshelfItemRoute(itemId)
                                            )
                                        },
                                        onAudiobookshelfGenreClick = { genre ->
                                            navController.navigate(
                                                Destination.createAudiobookshelfGenreResultsRoute(
                                                    genre
                                                )
                                            )
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(
                                    route = Destination.GENRE_RESULTS_ROUTE,
                                    arguments =
                                        listOf(navArgument("genre") { type = NavType.StringType }),
                                ) {
                                    GenreResultsScreen(
                                        genre = it.arguments?.getString("genre") ?: "",
                                        onBackClick = { navController.popBackStack() },
                                        onItemClick = { item -> handleItemClick(item) },
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(
                                    route = Destination.AUDIOBOOKSHELF_GENRE_RESULTS_ROUTE,
                                    arguments =
                                        listOf(navArgument("genre") { type = NavType.StringType }),
                                ) {
                                    AudiobookshelfGenreResultsScreen(
                                        genre = it.arguments?.getString("genre") ?: "",
                                        onBackClick = { navController.popBackStack() },
                                        onItemClick = { itemId ->
                                            navController.navigate(
                                                Destination.createAudiobookshelfItemRoute(itemId)
                                            )
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(Destination.SETTINGS_ROUTE) {
                                    SettingsScreen(
                                        navController = navController,
                                        onBackClick = { navController.popBackStack() },
                                        onLogoutComplete = {
                                            // Logout handled by MainActivity observing auth state
                                        },
                                        onLicensesClick = {
                                            val route = Destination.createLicensesRoute()
                                            navController.navigate(route)
                                        },
                                        onDownloadClick = {
                                            val route = Destination.createDownloadSettingsRoute()
                                            navController.navigate(route)
                                        },
                                        onPlayerOptionsClick = {
                                            val route = Destination.createPlayerOptionsRoute()
                                            navController.navigate(route)
                                        },
                                        onAppearanceOptionsClick = {
                                            val route = Destination.createAppearanceOptionsRoute()
                                            navController.navigate(route)
                                        },
                                        onServerManagementClick = {
                                            val route = Destination.createServerManagementRoute()
                                            navController.navigate(route)
                                        },
                                    )
                                }

                                composable(Destination.DOWNLOAD_SETTINGS_ROUTE) {
                                    DownloadSettingsScreen(
                                        onBackClick = { navController.popBackStack() },
                                        onNavigateToAbsItem = { itemId ->
                                            navController.navigate(
                                                Destination.createAudiobookshelfItemRoute(itemId)
                                            )
                                        },
                                        offlineModeManager = offlineModeManager,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }

                                composable(Destination.PLAYER_OPTIONS_ROUTE) {
                                    PlayerOptionsScreen(
                                        onBackClick = { navController.popBackStack() },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }

                                composable(Destination.APPEARANCE_OPTIONS_ROUTE) {
                                    AppearanceOptionsScreen(
                                        onBackClick = { navController.popBackStack() }
                                    )
                                }

                                composable(Destination.LICENSES_ROUTE) {
                                    LicensesScreen(onBackClick = { navController.popBackStack() })
                                }

                                composable(Destination.SERVER_MANAGEMENT_ROUTE) {
                                    ServerManagementScreen(
                                        onBackClick = { navController.popBackStack() },
                                        onAddServerClick = {
                                            val route =
                                                Destination.createAddEditServerRoute(
                                                    serverId = null
                                                )
                                            navController.navigate(route)
                                        },
                                        onEditServerClick = { serverId ->
                                            val route =
                                                Destination.createAddEditServerRoute(
                                                    serverId = serverId
                                                )
                                            navController.navigate(route)
                                        },
                                    )
                                }

                                composable(
                                    route = Destination.ADD_EDIT_SERVER_ROUTE,
                                    arguments =
                                        listOf(
                                            navArgument("serverId") {
                                                type = NavType.StringType
                                                nullable = true
                                                defaultValue = null
                                            }
                                        ),
                                ) {
                                    AddEditServerScreen(
                                        onBackClick = { navController.popBackStack() }
                                    )
                                }

                                composable(
                                    route = Destination.LOGIN_ROUTE,
                                    arguments =
                                        listOf(
                                            navArgument("serverUrl") {
                                                type = NavType.StringType
                                                nullable = true
                                                defaultValue = null
                                            }
                                        ),
                                ) {
                                    LoginScreen(
                                        onLoginSuccess = {
                                            navController.navigate(Destination.HOME.route) {
                                                popUpTo(0) { inclusive = true }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(Destination.AUDIOBOOKSHELF_LIBRARIES_ROUTE) {
                                    AudiobookshelfLibrariesScreen(
                                        onNavigateToItem = { itemId ->
                                            navController.navigate(
                                                Destination.createAudiobookshelfItemRoute(itemId)
                                            )
                                        },
                                        navController = navController,
                                        mainUiState = mainUiState,
                                        widthSizeClass = widthSizeClass,
                                    )
                                }

                                composable(
                                    route = Destination.AUDIOBOOKSHELF_ITEM_ROUTE,
                                    arguments =
                                        listOf(navArgument("itemId") { type = NavType.StringType }),
                                ) {
                                    AudiobookshelfItemScreen(
                                        onNavigateToPlayer = {
                                            itemId,
                                            episodeId,
                                            startPosition,
                                            episodeSort ->
                                            navController.navigate(
                                                Destination.createAudiobookshelfPlayerRoute(
                                                    itemId,
                                                    episodeId,
                                                    startPosition,
                                                    episodeSort,
                                                )
                                            )
                                        },
                                        onNavigateToSeries = { seriesId, libraryId, seriesName ->
                                            navController.navigate(
                                                Destination.createAudiobookshelfSeriesRoute(
                                                    seriesId,
                                                    libraryId,
                                                    seriesName,
                                                )
                                            )
                                        },
                                    )
                                }

                                composable(
                                    route = Destination.AUDIOBOOKSHELF_SERIES_ROUTE,
                                    arguments =
                                        listOf(
                                            navArgument("seriesId") { type = NavType.StringType },
                                            navArgument("libraryId") { type = NavType.StringType },
                                            navArgument("seriesName") { type = NavType.StringType },
                                        ),
                                ) {
                                    AudiobookshelfSeriesScreen(
                                        onNavigateToPlayer = { itemId, episodeId, startPosition ->
                                            navController.navigate(
                                                Destination.createAudiobookshelfPlayerRoute(
                                                    itemId = itemId,
                                                    episodeId = episodeId,
                                                    startPosition = startPosition,
                                                )
                                            )
                                        }
                                    )
                                }

                                composable(
                                    route = Destination.AUDIOBOOKSHELF_PLAYER_ROUTE,
                                    arguments =
                                        listOf(
                                            navArgument("itemId") { type = NavType.StringType },
                                            navArgument("episodeId") {
                                                type = NavType.StringType
                                                nullable = true
                                                defaultValue = null
                                            },
                                            navArgument("startPosition") {
                                                type = NavType.StringType
                                                nullable = true
                                                defaultValue = null
                                            },
                                            navArgument("episodeSort") {
                                                type = NavType.StringType
                                                nullable = true
                                                defaultValue = null
                                            },
                                        ),
                                ) {
                                    AudiobookshelfPlayerScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        animatedVisibilityScope = this@composable,
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = showMiniPlayer,
                                enter = slideInVertically { it },
                                exit = slideOutVertically { it },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            ) {
                                MiniPlayer(
                                    modifier = Modifier.navigationBarsPadding(),
                                    title = audiobookshelfPlaybackState.displayTitle,
                                    author = audiobookshelfPlaybackState.displayAuthor,
                                    currentChapter = audiobookshelfPlaybackState.currentChapter,
                                    coverUrl = audiobookshelfPlaybackState.coverUrl,
                                    currentTime = audiobookshelfPlaybackState.currentTime,
                                    duration = audiobookshelfPlaybackState.duration,
                                    isPlaying = audiobookshelfPlaybackState.isPlaying,
                                    isBuffering = audiobookshelfPlaybackState.isBuffering,
                                    animatedVisibilityScope = this@AnimatedVisibility,
                                    onPlayPauseClick = {
                                        if (viewModel.audiobookshelfPlayer.isPlaying()) {
                                            viewModel.audiobookshelfPlayer.pause()
                                        } else {
                                            viewModel.audiobookshelfPlayer.play()
                                        }
                                    },
                                    onCloseClick = {
                                        viewModel.audiobookshelfPlayer.pause()
                                        coroutineScope.launch {
                                            viewModel.audiobookshelfPlayer.closeSession()
                                        }
                                    },
                                    onClick = {
                                        val itemId = audiobookshelfPlaybackState.itemId
                                        val episodeId = audiobookshelfPlaybackState.episodeId
                                        if (itemId != null) {
                                            navController.navigate(
                                                Destination.createAudiobookshelfPlayerRoute(
                                                    itemId,
                                                    episodeId,
                                                )
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    GlobalUpdateDialog(updateManager = updateManager)
}
