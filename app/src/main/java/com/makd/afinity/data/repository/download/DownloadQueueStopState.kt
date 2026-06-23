package com.makd.afinity.data.repository.download

import java.util.concurrent.atomic.AtomicReference

class DownloadQueueStopState {
    private val request = AtomicReference<DownloadQueueStopRequest?>(null)

    fun current(): DownloadQueueStopRequest? = request.get()

    fun clear() {
        request.set(null)
    }

    fun requestPause(
        reason: String,
        force: Boolean = false,
    ): Boolean =
        requestStop(
            DownloadQueueStopRequest(
                reason = reason,
                disposition = DownloadQueueStopDisposition.PAUSE,
                scheduleAfterStop = null,
                allowScheduleAfterStop = !force,
                rescheduleCurrentJob = false,
            ),
            force = force,
        )

    fun requestPolicyRequeue(
        reason: String,
        scheduleAfterStop: DownloadQueueScheduleTrigger,
    ): Boolean {
        val accepted =
            requestStop(
                DownloadQueueStopRequest(
                    reason = reason,
                    disposition = DownloadQueueStopDisposition.REQUEUE,
                    scheduleAfterStop = scheduleAfterStop,
                    allowScheduleAfterStop = true,
                    rescheduleCurrentJob = false,
                )
            )
        if (!accepted) ensureScheduleAfterStop(scheduleAfterStop)
        return accepted
    }

    fun requestSystemRequeue(reason: String): Boolean =
        requestStop(
            DownloadQueueStopRequest(
                reason = reason,
                disposition = DownloadQueueStopDisposition.REQUEUE,
                scheduleAfterStop = null,
                allowScheduleAfterStop = false,
                rescheduleCurrentJob = true,
            )
        )

    private fun requestStop(
        next: DownloadQueueStopRequest,
        force: Boolean = false,
    ): Boolean {
        while (true) {
            val current = request.get()
            if (!force && current != null) return false
            if (request.compareAndSet(current, next)) return true
        }
    }

    fun ensureScheduleAfterStop(scheduleAfterStop: DownloadQueueScheduleTrigger): Boolean {
        while (true) {
            val current = request.get() ?: return false
            if (!current.allowScheduleAfterStop) return false
            if (current.scheduleAfterStop == scheduleAfterStop) return true
            val next = current.copy(scheduleAfterStop = scheduleAfterStop)
            if (request.compareAndSet(current, next)) return true
        }
    }
}

data class DownloadQueueStopRequest(
    val reason: String,
    val disposition: DownloadQueueStopDisposition,
    val scheduleAfterStop: DownloadQueueScheduleTrigger?,
    val allowScheduleAfterStop: Boolean,
    val rescheduleCurrentJob: Boolean,
)

enum class DownloadQueueStopDisposition {
    PAUSE,
    REQUEUE,
}

enum class DownloadQueueRequeueRequestResult {
    RunnerWillHandleRequeue,
    NoRunningRunner,
    ExistingStopRequestWins,
}
