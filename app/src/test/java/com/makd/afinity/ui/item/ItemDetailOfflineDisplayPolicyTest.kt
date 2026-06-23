package com.makd.afinity.ui.item

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemDetailOfflineDisplayPolicyTest {
    @Test
    fun localCatalogItemsBypassCompletedDownloadFilteringWhenOffline() {
        assertFalse(
            shouldFilterToDownloadedContent(
                isOffline = true,
                isLocalCatalogItem = true,
            )
        )
    }

    @Test
    fun legacyOfflineItemsStillUseCompletedDownloadFiltering() {
        assertTrue(
            shouldFilterToDownloadedContent(
                isOffline = true,
                isLocalCatalogItem = false,
            )
        )
    }

    @Test
    fun onlineItemsAreNotFiltered() {
        assertFalse(
            shouldFilterToDownloadedContent(
                isOffline = false,
                isLocalCatalogItem = false,
            )
        )
    }
}
