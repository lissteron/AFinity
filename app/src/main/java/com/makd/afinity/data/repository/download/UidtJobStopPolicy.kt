package com.makd.afinity.data.repository.download

import android.app.job.JobParameters

internal object UidtJobStopPolicy {
    fun decide(stopReason: Int): UidtJobStopPolicyDecision =
        when (stopReason) {
            JobParameters.STOP_REASON_USER ->
                UidtJobStopPolicyDecision(
                    disposition = UidtJobStopDisposition.PAUSE_ACTIVE,
                    shouldAskJobSchedulerToReschedule = false,
                )
            JobParameters.STOP_REASON_CANCELLED_BY_APP,
            JobParameters.STOP_REASON_TIMEOUT,
            JobParameters.STOP_REASON_TIMEOUT_ABANDONED ->
                UidtJobStopPolicyDecision(
                    disposition = UidtJobStopDisposition.APP_OWNED_REQUEUE,
                    shouldAskJobSchedulerToReschedule = false,
                    scheduleAfterStop = DownloadQueueScheduleTrigger.VISIBLE_LIVENESS,
                )
            else ->
                UidtJobStopPolicyDecision(
                    disposition = UidtJobStopDisposition.SYSTEM_OWNED_REQUEUE,
                    shouldAskJobSchedulerToReschedule = true,
                )
        }
}

internal data class UidtJobStopPolicyDecision(
    val disposition: UidtJobStopDisposition,
    val shouldAskJobSchedulerToReschedule: Boolean,
    val scheduleAfterStop: DownloadQueueScheduleTrigger? = null,
)

internal enum class UidtJobStopDisposition {
    PAUSE_ACTIVE,
    APP_OWNED_REQUEUE,
    SYSTEM_OWNED_REQUEUE,
}
