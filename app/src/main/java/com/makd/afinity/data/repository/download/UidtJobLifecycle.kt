package com.makd.afinity.data.repository.download

internal class UidtJobLifecycle(
    private val runGate: UidtJobRunGate = UidtJobRunGate()
) {
    @Volatile private var workKind: UidtJobWorkKind = UidtJobWorkKind.NONE

    fun startIfIdle(): UidtJobRunStart = runGate.startIfIdle()

    fun markRunnerWork() {
        workKind = UidtJobWorkKind.RUNNER
    }

    fun markCleanupWork() {
        workKind = UidtJobWorkKind.CLEANUP
    }

    fun hasActiveRun(): Boolean = runGate.hasActiveRun()

    fun stop(): UidtJobLifecycleStopDecision {
        val shouldStopRunner = workKind == UidtJobWorkKind.RUNNER
        runGate.stop()
        return UidtJobLifecycleStopDecision(shouldStopRunner = shouldStopRunner)
    }

    fun finishIfCurrent(
        runId: Long,
        finish: () -> Unit,
    ): Boolean =
        runGate.finishIfCurrent(runId) {
            workKind = UidtJobWorkKind.NONE
            finish()
        }

    fun destroy() {
        runGate.stop()
        workKind = UidtJobWorkKind.NONE
    }

    internal enum class UidtJobWorkKind {
        NONE,
        CLEANUP,
        RUNNER,
    }
}

internal data class UidtJobLifecycleStopDecision(val shouldStopRunner: Boolean)
