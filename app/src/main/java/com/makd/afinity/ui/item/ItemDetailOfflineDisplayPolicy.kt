package com.makd.afinity.ui.item

internal fun shouldFilterToDownloadedContent(
    isOffline: Boolean,
    isLocalCatalogItem: Boolean,
): Boolean = isOffline && !isLocalCatalogItem
