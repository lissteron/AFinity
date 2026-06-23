package com.makd.afinity.ui.home

internal data class HomeStartupContentFlags(
    val canShowRemoteContent: Boolean,
    val hasLocalCatalogContent: Boolean,
    val showDownloadedSections: Boolean,
    val useOfflineContinueWatching: Boolean,
)

internal fun HomeUiState.startupContentFlags(): HomeStartupContentFlags {
    val canShowRemoteContent = !isOffline && !isServerUnavailable
    val hasLocalCatalogContent =
        offlineContinueWatching.isNotEmpty() ||
            downloadedMovies.isNotEmpty() ||
            downloadedShows.isNotEmpty() ||
            downloadedAudiobooks.isNotEmpty() ||
            downloadedPodcastEpisodes.isNotEmpty()

    return HomeStartupContentFlags(
        canShowRemoteContent = canShowRemoteContent,
        hasLocalCatalogContent = hasLocalCatalogContent,
        showDownloadedSections = !canShowRemoteContent || hasLocalCatalogContent,
        useOfflineContinueWatching = !canShowRemoteContent,
    )
}
