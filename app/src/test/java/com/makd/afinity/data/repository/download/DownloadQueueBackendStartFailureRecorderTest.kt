package com.makd.afinity.data.repository.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueueBackendStartFailureRecorderTest {
    @Test
    fun recordsSchedulerFailureBeforeRequeueingActiveRows() = runBlocking {
        val events = mutableListOf<String>()
        val recorder =
            DownloadQueueBackendStartFailureRecorder(
                recordFailure = { reason -> events += "record:$reason" },
                requeueActiveRows = { reason ->
                    events += "requeue:$reason"
                    2
                },
            )

        val requeued = recorder.record("notification blocked")

        assertEquals(2, requeued)
        assertEquals(listOf("record:notification blocked", "requeue:notification blocked"), events)
    }

    @Test
    fun doesNotHideRequeueFailure() = runBlocking {
        val events = mutableListOf<String>()
        val recorder =
            DownloadQueueBackendStartFailureRecorder(
                recordFailure = { reason -> events += "record:$reason" },
                requeueActiveRows = {
                    events += "requeue"
                    error("requeue failed")
                },
            )

        val thrown =
            runCatching { recorder.record("foreground denied") }
                .exceptionOrNull()
                ?: error("Expected requeue failure")

        assertEquals("requeue failed", thrown.message)
        assertEquals(listOf("record:foreground denied", "requeue"), events)
    }
}
