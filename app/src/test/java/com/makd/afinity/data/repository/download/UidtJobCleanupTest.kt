package com.makd.afinity.data.repository.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UidtJobCleanupTest {
    @Test
    fun completedCleanupDoesNotRequestReschedule() = runBlocking {
        var calls = 0

        val result =
            UidtJobCleanup.run {
                calls += 1
            }

        assertSame(UidtJobCleanupResult.Completed, result)
        assertEquals(1, calls)
        assertTrue(!result.wantsReschedule)
    }

    @Test
    fun failedCleanupRequestsReschedule() = runBlocking {
        val failure = IllegalStateException("database unavailable")

        val result =
            UidtJobCleanup.run {
                throw failure
            }

        assertTrue(result is UidtJobCleanupResult.Failed)
        assertSame(failure, (result as UidtJobCleanupResult.Failed).error)
        assertTrue(result.wantsReschedule)
    }

    @Test
    fun cancellationIsNotConvertedToCleanupFailure() = runBlocking {
        val cancellation = CancellationException("service destroyed")

        val thrown =
            runCatching {
                    UidtJobCleanup.run {
                        throw cancellation
                    }
                }
                .exceptionOrNull()

        assertSame(cancellation, thrown)
    }
}
