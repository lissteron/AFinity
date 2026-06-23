package com.makd.afinity.data.repository.download

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueueBackendStartFailureHandler
@Inject
constructor(
    private val scheduler: DownloadQueueScheduler,
    private val stateStore: DownloadQueueStateStore,
) {
    private val recorder =
        DownloadQueueBackendStartFailureRecorder(
            recordFailure = scheduler::recordBackendStartFailure,
            requeueActiveRows = { reason -> stateStore.requeueAllActiveForTransientStop(reason) },
        )

    suspend fun record(reason: String): Int = recorder.record(reason)
}

internal class DownloadQueueBackendStartFailureRecorder(
    private val recordFailure: (String) -> Unit,
    private val requeueActiveRows: suspend (String) -> Int,
) {
    suspend fun record(reason: String): Int {
        recordFailure(reason)
        return requeueActiveRows(reason)
    }
}
