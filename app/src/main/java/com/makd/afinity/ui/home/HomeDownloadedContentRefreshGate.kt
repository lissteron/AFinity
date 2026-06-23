package com.makd.afinity.ui.home

import java.util.UUID

internal data class HomeDownloadedContentSessionKey(
    val serverId: String?,
    val userId: UUID?,
)

internal data class HomeDownloadedContentRefreshKey(
    val serverId: String?,
    val userId: UUID?,
    val kidModeEnabled: Boolean,
    val parentUnlocked: Boolean,
)

internal class HomeDownloadedContentRefreshGate {
    private var lastLoadedKey: HomeDownloadedContentRefreshKey? = null

    fun shouldRefresh(key: HomeDownloadedContentRefreshKey, force: Boolean): Boolean =
        force || key != lastLoadedKey

    fun markLoaded(key: HomeDownloadedContentRefreshKey) {
        lastLoadedKey = key
    }
}
