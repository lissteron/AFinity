package com.makd.afinity.data.repository.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UidtJobRunGateTest {
    @Test
    fun stoppedRunCannotFinish() {
        val gate = UidtJobRunGate()
        val runId = (gate.startIfIdle() as UidtJobRunStart.Started).runId
        assertTrue(gate.hasActiveRun())

        gate.stop()

        var finished = false
        assertFalse(gate.finishIfCurrent(runId) { finished = true })
        assertFalse(finished)
        assertFalse(gate.hasActiveRun())
    }

    @Test
    fun duplicateStartDoesNotInvalidateCurrentRun() {
        val gate = UidtJobRunGate()
        val firstRunId = (gate.startIfIdle() as UidtJobRunStart.Started).runId
        val secondStart = gate.startIfIdle()

        var firstFinished = false
        assertTrue(secondStart is UidtJobRunStart.AlreadyRunning)
        assertTrue(gate.finishIfCurrent(firstRunId) { firstFinished = true })

        assertTrue(firstFinished)
        assertFalse(gate.hasActiveRun())
    }
}
