package com.makd.afinity.data.repository.download

import java.util.concurrent.atomic.AtomicLong

class UidtJobRunGate {
    private val runIds = AtomicLong(0L)
    private val lock = Any()
    @Volatile private var activeRunId: Long = 0L

    fun start(): Long =
        synchronized(lock) {
            runIds.incrementAndGet().also { activeRunId = it }
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
