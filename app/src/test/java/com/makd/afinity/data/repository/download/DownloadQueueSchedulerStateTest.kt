package com.makd.afinity.data.repository.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueSchedulerStateTest {
    @Test
    fun uidtDeferralIsObservableForLivenessRetry() {
        val state = DownloadQueueSchedulerState()

        state.recordUidtDeferral("App is not visible")

        assertTrue(state.deferredUidtSchedule.value)
        assertEquals("App is not visible", state.schedulerMessage.value)
    }

    @Test
    fun schedulerFailureIsSurfacedWithoutPretendingUidtWasDeferred() {
        val state = DownloadQueueSchedulerState()

        state.recordSchedulerFailure("JobScheduler rejected UIDT download queue job")

        assertFalse(state.deferredUidtSchedule.value)
        assertEquals("JobScheduler rejected UIDT download queue job", state.schedulerMessage.value)
    }

    @Test
    fun clearRemovesDeferredAndFailureState() {
        val state = DownloadQueueSchedulerState()
        state.recordSchedulerFailure("WorkManager download queue scheduling failure")

        state.clear()

        assertFalse(state.deferredUidtSchedule.value)
        assertNull(state.schedulerMessage.value)
    }
}
