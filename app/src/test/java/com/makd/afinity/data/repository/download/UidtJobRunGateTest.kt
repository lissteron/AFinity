package com.makd.afinity.data.repository.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UidtJobRunGateTest {
    @Test
    fun stoppedRunCannotFinish() {
        val gate = UidtJobRunGate()
        val runId = gate.start()
        assertTrue(gate.hasActiveRun())

        gate.stop()

        var finished = false
        assertFalse(gate.finishIfCurrent(runId) { finished = true })
        assertFalse(finished)
        assertFalse(gate.hasActiveRun())
    }

    @Test
    fun restartedRunInvalidatesPreviousRun() {
        val gate = UidtJobRunGate()
        val firstRunId = gate.start()
        val secondRunId = gate.start()

        var firstFinished = false
        var secondFinished = false
        assertFalse(gate.finishIfCurrent(firstRunId) { firstFinished = true })
        assertTrue(gate.finishIfCurrent(secondRunId) { secondFinished = true })

        assertFalse(firstFinished)
        assertTrue(secondFinished)
        assertFalse(gate.hasActiveRun())
    }
}
