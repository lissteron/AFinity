package com.makd.afinity.data.repository.download

class DownloadQueuePolicyDecider {
    fun decideWifiPolicyChange(
        wifiOnly: Boolean,
        hasActiveDownload: Boolean,
        currentNetworkUnmetered: Boolean,
    ): DownloadQueuePolicyDecision {
        if (!hasActiveDownload) {
            return DownloadQueuePolicyDecision.ReschedulePending
        }
        return if (wifiOnly && !currentNetworkUnmetered) {
            DownloadQueuePolicyDecision.PauseActive(
                "Wi-Fi-only download policy now requires an unmetered network."
            )
        } else {
            DownloadQueuePolicyDecision.ActiveCanContinue
        }
    }

    fun decideStorageNotLowPolicyChange(
        storageNotLow: Boolean,
        hasActiveDownload: Boolean,
    ): DownloadQueuePolicyDecision {
        if (!hasActiveDownload) {
            return DownloadQueuePolicyDecision.ReschedulePending
        }
        return if (storageNotLow) {
            DownloadQueuePolicyDecision.ActiveCanContinue
        } else {
            DownloadQueuePolicyDecision.PauseActive(
                "Device storage is low; downloads require storage-not-low."
            )
        }
    }

    fun decideStorageLocationChange(hasActiveDownload: Boolean): DownloadQueuePolicyDecision {
        return if (hasActiveDownload) {
            DownloadQueuePolicyDecision.PauseActive(
                "Download storage location changed; active transfer must restart on the selected storage."
            )
        } else {
            DownloadQueuePolicyDecision.ReschedulePending
        }
    }
}

sealed class DownloadQueuePolicyDecision {
    data object ReschedulePending : DownloadQueuePolicyDecision()

    data object ActiveCanContinue : DownloadQueuePolicyDecision()

    data class PauseActive(val reason: String) : DownloadQueuePolicyDecision()
}
