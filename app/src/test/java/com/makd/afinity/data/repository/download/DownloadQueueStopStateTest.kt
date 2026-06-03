package com.makd.afinity.data.repository.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueStopStateTest {
    @Test
    fun policyRequeueCannotBeDowngradedByNonForcedPause() {
        val state = DownloadQueueStopState()

        assertTrue(
            state.requestPolicyRequeue(
                reason = "Policy changed",
                scheduleAfterStop = DownloadQueueScheduleTrigger.VISIBLE_LIVENESS,
            )
        )
        assertFalse(state.requestPause(reason = "UIDT job stopped", force = false))

        val request = state.current()
        assertEquals(DownloadQueueStopDisposition.REQUEUE, request?.disposition)
        assertEquals(DownloadQueueScheduleTrigger.VISIBLE_LIVENESS, request?.scheduleAfterStop)
    }

    @Test
    fun forcedPauseOverridesPolicyRequeueForUserStop() {
        val state = DownloadQueueStopState()
        state.requestPolicyRequeue(
            reason = "Policy changed",
            scheduleAfterStop = DownloadQueueScheduleTrigger.VISIBLE_LIVENESS,
        )

        assertTrue(state.requestPause(reason = "User stopped UIDT job", force = true))

        val request = state.current()
        assertEquals(DownloadQueueStopDisposition.PAUSE, request?.disposition)
        assertNull(request?.scheduleAfterStop)
    }

    @Test
    fun existingPauseKeepsDispositionButCanCarryPolicyScheduleAfterStop() {
        val state = DownloadQueueStopState()

        assertTrue(state.requestPause(reason = "Notification update failed"))
        assertFalse(
            state.requestPolicyRequeue(
                reason = "Policy changed",
                scheduleAfterStop = DownloadQueueScheduleTrigger.VISIBLE_LIVENESS,
            )
        )

        assertEquals(DownloadQueueStopDisposition.PAUSE, state.current()?.disposition)
        assertEquals(DownloadQueueScheduleTrigger.VISIBLE_LIVENESS, state.current()?.scheduleAfterStop)
    }

    @Test
    fun forcedUserPauseCannotCarryPolicyScheduleAfterStop() {
        val state = DownloadQueueStopState()

        assertTrue(state.requestPause(reason = "User stopped UIDT job", force = true))
        assertFalse(
            state.requestPolicyRequeue(
                reason = "Policy changed",
                scheduleAfterStop = DownloadQueueScheduleTrigger.VISIBLE_LIVENESS,
            )
        )

        assertEquals(DownloadQueueStopDisposition.PAUSE, state.current()?.disposition)
        assertNull(state.current()?.scheduleAfterStop)
    }

    @Test
    fun clearAllowsNextStopRequest() {
        val state = DownloadQueueStopState()
        state.requestPause(reason = "Notification update failed")

        state.clear()

        assertNull(state.current())
        assertTrue(
            state.requestPolicyRequeue(
                reason = "Policy changed",
                scheduleAfterStop = DownloadQueueScheduleTrigger.PASSIVE_BACKGROUND,
            )
        )
        assertEquals(DownloadQueueStopDisposition.REQUEUE, state.current()?.disposition)
    }
}
