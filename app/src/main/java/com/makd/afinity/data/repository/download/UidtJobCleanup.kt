package com.makd.afinity.data.repository.download

import kotlinx.coroutines.CancellationException

internal object UidtJobCleanup {
    suspend fun run(cleanup: suspend () -> Unit): UidtJobCleanupResult =
        try {
            cleanup()
            UidtJobCleanupResult.Completed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UidtJobCleanupResult.Failed(e)
        }
}

internal sealed class UidtJobCleanupResult {
    abstract val wantsReschedule: Boolean

    data object Completed : UidtJobCleanupResult() {
        override val wantsReschedule: Boolean = false
    }

    data class Failed(val error: Exception) : UidtJobCleanupResult() {
        override val wantsReschedule: Boolean = true
    }
}
