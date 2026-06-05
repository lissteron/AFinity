package com.makd.afinity.data.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModeReasonTest {
    @Test
    fun manualOfflineWinsOverNetworkAndServerState() {
        val reason =
            resolveOfflineModeReason(
                manualOfflineMode = true,
                isNetworkAvailable = true,
                isServerReachable = true,
            )

        assertEquals(OfflineModeReason.MANUAL, reason)
        assertTrue(reason.isHardOfflineReason())
        assertFalse(reason.canAttemptRemoteForReason())
        assertFalse(reason.canLoadRemoteContentForReason())
    }

    @Test
    fun missingNetworkIsHardOffline() {
        val reason =
            resolveOfflineModeReason(
                manualOfflineMode = false,
                isNetworkAvailable = false,
                isServerReachable = true,
            )

        assertEquals(OfflineModeReason.NO_NETWORK, reason)
        assertTrue(reason.isHardOfflineReason())
        assertFalse(reason.canAttemptRemoteForReason())
        assertFalse(reason.canLoadRemoteContentForReason())
    }

    @Test
    fun unreachableServerIsNotHardOfflineButBlocksJellyfinContent() {
        val reason =
            resolveOfflineModeReason(
                manualOfflineMode = false,
                isNetworkAvailable = true,
                isServerReachable = false,
            )

        assertEquals(OfflineModeReason.SERVER_UNREACHABLE, reason)
        assertFalse(reason.isHardOfflineReason())
        assertTrue(reason.canAttemptRemoteForReason())
        assertFalse(reason.canLoadRemoteContentForReason())
    }

    @Test
    fun onlineCanLoadRemoteContent() {
        val reason =
            resolveOfflineModeReason(
                manualOfflineMode = false,
                isNetworkAvailable = true,
                isServerReachable = true,
            )

        assertEquals(OfflineModeReason.ONLINE, reason)
        assertFalse(reason.isHardOfflineReason())
        assertTrue(reason.canAttemptRemoteForReason())
        assertTrue(reason.canLoadRemoteContentForReason())
    }
}
