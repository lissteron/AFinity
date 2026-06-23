package com.makd.afinity.ui.home

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDownloadedContentRefreshGateTest {
    @Test
    fun duplicateStartupSignalsForSameProfileAndPolicyAreSkippedAfterFirstLoad() {
        val gate = HomeDownloadedContentRefreshGate()
        val key = refreshKey()

        assertTrue(gate.shouldRefresh(key, force = false))
        gate.markLoaded(key)

        assertFalse(gate.shouldRefresh(key, force = false))
    }

    @Test
    fun forceRefreshBypassesDuplicateKey() {
        val gate = HomeDownloadedContentRefreshGate()
        val key = refreshKey()

        gate.markLoaded(key)

        assertTrue(gate.shouldRefresh(key, force = true))
    }

    @Test
    fun kidModeAndParentUnlockChangesRefreshLocalCatalogVisibility() {
        val gate = HomeDownloadedContentRefreshGate()
        val unlocked = refreshKey(kidModeEnabled = true, parentUnlocked = true)
        val locked = unlocked.copy(parentUnlocked = false)

        gate.markLoaded(unlocked)

        assertTrue(gate.shouldRefresh(locked, force = false))
    }

    @Test
    fun sameUserOnDifferentServerRefreshesLocalCatalogScope() {
        val gate = HomeDownloadedContentRefreshGate()
        val firstServer = refreshKey(serverId = "server-a")
        val secondServer = firstServer.copy(serverId = "server-b")

        gate.markLoaded(firstServer)

        assertTrue(gate.shouldRefresh(secondServer, force = false))
    }

    private fun refreshKey(
        serverId: String = "server-a",
        kidModeEnabled: Boolean = false,
        parentUnlocked: Boolean = false,
    ): HomeDownloadedContentRefreshKey =
        HomeDownloadedContentRefreshKey(
            serverId = serverId,
            userId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            kidModeEnabled = kidModeEnabled,
            parentUnlocked = parentUnlocked,
        )
}
