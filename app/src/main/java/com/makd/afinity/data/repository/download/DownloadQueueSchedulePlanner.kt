package com.makd.afinity.data.repository.download

import android.app.job.JobInfo
import android.os.Build

class DownloadQueueSchedulePlanner {
    fun plan(
        sdkInt: Int,
        trigger: DownloadQueueScheduleTrigger,
        isVisible: Boolean,
        queuedCount: Int,
        activeDownloadCount: Int = 0,
        notificationsAllowed: Boolean,
    ): Plan {
        if (activeDownloadCount > 0) return Plan.BackendAlreadyRunning
        if (queuedCount <= 0) return Plan.NoEligibleRows
        if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return Plan.ScheduleWorkManager
        if (!isVisible) {
            return Plan.DeferUidt("App is not visible enough to schedule UIDT")
        }
        if (!trigger.userInitiatedVisible) {
            return Plan.DeferUidt("Download queue trigger is not a visible user entry point")
        }
        if (!notificationsAllowed) {
            return Plan.DeferUidt("Download notification permission or channel is blocked")
        }
        return Plan.ScheduleUidt
    }

    fun estimateBytes(downloads: List<QueueByteEstimate>): Long {
        val known =
            downloads.sumOf { estimate ->
                val remaining = estimate.totalBytes - estimate.bytesDownloaded
                when {
                    remaining > 0L -> remaining
                    estimate.totalBytes > 0L -> estimate.totalBytes
                    else -> 0L
                }
            }
        return if (known > 0L) known else JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
    }

    sealed class Plan {
        data object ScheduleUidt : Plan()
        data object ScheduleWorkManager : Plan()
        data object BackendAlreadyRunning : Plan()
        data object NoEligibleRows : Plan()
        data class DeferUidt(val reason: String) : Plan()
    }
}

data class QueueByteEstimate(val bytesDownloaded: Long, val totalBytes: Long)
