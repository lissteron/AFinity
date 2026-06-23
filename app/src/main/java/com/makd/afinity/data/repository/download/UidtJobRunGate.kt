package com.makd.afinity.data.repository.download

import java.util.concurrent.atomic.AtomicLong

internal class UidtJobRunGate {
    private val runIds = AtomicLong(0L)
    private val lock = Any()
    @Volatile private var activeRunId: Long = 0L

    fun startIfIdle(): UidtJobRunStart =
        synchronized(lock) {
            if (activeRunId != 0L) {
                return@synchronized UidtJobRunStart.AlreadyRunning(activeRunId)
            }
            val runId = runIds.incrementAndGet()
            activeRunId = runId
            UidtJobRunStart.Started(runId)
        }

    fun stop() {
        synchronized(lock) { activeRunId = 0L }
    }

    fun isCurrent(runId: Long): Boolean = activeRunId == runId

    fun hasActiveRun(): Boolean = activeRunId != 0L

    fun finishIfCurrent(runId: Long, finish: () -> Unit): Boolean {
        synchronized(lock) {
            if (activeRunId != runId) return false
            activeRunId = 0L
        }
        finish()
        return true
    }
}

internal sealed class UidtJobRunStart {
    data class Started(val runId: Long) : UidtJobRunStart()
    data class AlreadyRunning(val activeRunId: Long) : UidtJobRunStart()
}
