package com.makd.afinity.data.repository.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueueBackendStartFailureRecorderTest {
    @Test
    fun recordsSchedulerFailureBeforePausingActiveRows() = runBlocking {
        val events = mutableListOf<String>()
        val recorder =
            DownloadQueueBackendStartFailureRecorder(
                recordFailure = { reason -> events += "record:$reason" },
                pauseActiveRows = { reason ->
                    events += "pause:$reason"
                    2
                },
            )

        val paused = recorder.record("notification blocked")

        assertEquals(2, paused)
        assertEquals(listOf("record:notification blocked", "pause:notification blocked"), events)
    }

    @Test
    fun doesNotHidePauseFailure() = runBlocking {
        val events = mutableListOf<String>()
        val recorder =
            DownloadQueueBackendStartFailureRecorder(
                recordFailure = { reason -> events += "record:$reason" },
                pauseActiveRows = {
                    events += "pause"
                    error("pause failed")
                },
            )

        val thrown =
            runCatching { recorder.record("foreground denied") }
                .exceptionOrNull()
                ?: error("Expected pause failure")

        assertEquals("pause failed", thrown.message)
        assertEquals(listOf("record:foreground denied", "pause"), events)
    }
}
