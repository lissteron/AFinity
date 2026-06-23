package com.makd.afinity.data.repository.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UidtJobLifecycleTest {
    @Test
    fun stopDuringCleanupDoesNotStopRunnerAndPreventsFinish() {
        val lifecycle = UidtJobLifecycle()
        val runId = (lifecycle.startIfIdle() as UidtJobRunStart.Started).runId
        lifecycle.markCleanupWork()

        val stopDecision = lifecycle.stop()

        assertFalse(stopDecision.shouldStopRunner)
        var finished = false
        assertFalse(lifecycle.finishIfCurrent(runId) { finished = true })
        assertFalse(finished)
        assertFalse(lifecycle.hasActiveRun())
    }

    @Test
    fun stopDuringRunnerStopsRunnerAndPreventsFinish() {
        val lifecycle = UidtJobLifecycle()
        val runId = (lifecycle.startIfIdle() as UidtJobRunStart.Started).runId
        lifecycle.markRunnerWork()

        val stopDecision = lifecycle.stop()

        assertTrue(stopDecision.shouldStopRunner)
        var finished = false
        assertFalse(lifecycle.finishIfCurrent(runId) { finished = true })
        assertFalse(finished)
        assertFalse(lifecycle.hasActiveRun())
    }

    @Test
    fun currentRunCanFinishOnceAndClearsRunnerStopDecision() {
        val lifecycle = UidtJobLifecycle()
        val runId = (lifecycle.startIfIdle() as UidtJobRunStart.Started).runId
        lifecycle.markRunnerWork()

        var finished = false
        assertTrue(lifecycle.finishIfCurrent(runId) { finished = true })

        assertTrue(finished)
        assertFalse(lifecycle.hasActiveRun())
        assertFalse(lifecycle.stop().shouldStopRunner)
    }

    @Test
    fun duplicateStartDoesNotBecomeCurrentJobOwner() {
        val lifecycle = UidtJobLifecycle()
        val runId = (lifecycle.startIfIdle() as UidtJobRunStart.Started).runId
        lifecycle.markRunnerWork()

        val duplicateStart = lifecycle.startIfIdle()

        assertTrue(duplicateStart is UidtJobRunStart.AlreadyRunning)
        var finished = false
        assertTrue(lifecycle.finishIfCurrent(runId) { finished = true })
        assertTrue(finished)
        assertFalse(lifecycle.hasActiveRun())
    }
}
