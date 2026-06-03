package com.makd.afinity.data.repository.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueuePolicyDeciderTest {
    private val decider = DownloadQueuePolicyDecider()

    @Test
    fun noActiveDownloadReschedulesPendingOnly() {
        val decision =
            decider.decideWifiPolicyChange(
                wifiOnly = true,
                hasActiveDownload = false,
                currentNetworkUnmetered = false,
            )

        assertEquals(DownloadQueuePolicyDecision.ReschedulePending, decision)
    }

    @Test
    fun activeDownloadContinuesWhenStricterWifiPolicyIsAlreadySatisfied() {
        val decision =
            decider.decideWifiPolicyChange(
                wifiOnly = true,
                hasActiveDownload = true,
                currentNetworkUnmetered = true,
            )

        assertEquals(DownloadQueuePolicyDecision.ActiveCanContinue, decision)
    }

    @Test
    fun activeDownloadPausesWhenStricterWifiPolicyIsViolated() {
        val decision =
            decider.decideWifiPolicyChange(
                wifiOnly = true,
                hasActiveDownload = true,
                currentNetworkUnmetered = false,
            )

        assertTrue(decision is DownloadQueuePolicyDecision.PauseActive)
    }

    @Test
    fun activeDownloadContinuesWhenWifiPolicyIsRelaxed() {
        val decision =
            decider.decideWifiPolicyChange(
                wifiOnly = false,
                hasActiveDownload = true,
                currentNetworkUnmetered = false,
            )

        assertEquals(DownloadQueuePolicyDecision.ActiveCanContinue, decision)
    }

    @Test
    fun activeDownloadPausesWhenStorageNotLowPolicyIsViolated() {
        val decision =
            decider.decideStorageNotLowPolicyChange(
                storageNotLow = false,
                hasActiveDownload = true,
            )

        assertTrue(decision is DownloadQueuePolicyDecision.PauseActive)
    }

    @Test
    fun activeDownloadContinuesWhenStorageNotLowPolicyIsSatisfied() {
        val decision =
            decider.decideStorageNotLowPolicyChange(
                storageNotLow = true,
                hasActiveDownload = true,
            )

        assertEquals(DownloadQueuePolicyDecision.ActiveCanContinue, decision)
    }

    @Test
    fun noActiveDownloadReschedulesOnStoragePolicyChange() {
        val decision =
            decider.decideStorageNotLowPolicyChange(
                storageNotLow = false,
                hasActiveDownload = false,
            )

        assertEquals(DownloadQueuePolicyDecision.ReschedulePending, decision)
    }

    @Test
    fun activeDownloadPausesWhenStorageLocationChanges() {
        val decision = decider.decideStorageLocationChange(hasActiveDownload = true)

        assertTrue(decision is DownloadQueuePolicyDecision.PauseActive)
    }

    @Test
    fun noActiveDownloadReschedulesWhenStorageLocationChanges() {
        val decision = decider.decideStorageLocationChange(hasActiveDownload = false)

        assertEquals(DownloadQueuePolicyDecision.ReschedulePending, decision)
    }
}
