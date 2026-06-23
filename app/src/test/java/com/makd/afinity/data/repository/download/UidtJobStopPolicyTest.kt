package com.makd.afinity.data.repository.download

import android.app.job.JobParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UidtJobStopPolicyTest {
    @Test
    fun timeoutIsAppOwnedRequeueWithoutSystemReschedule() {
        val decision = UidtJobStopPolicy.decide(JobParameters.STOP_REASON_TIMEOUT)

        assertEquals(UidtJobStopDisposition.APP_OWNED_REQUEUE, decision.disposition)
        assertEquals(DownloadQueueScheduleTrigger.VISIBLE_LIVENESS, decision.scheduleAfterStop)
        assertFalse(decision.shouldAskJobSchedulerToReschedule)
    }

    @Test
    fun cancelledByAppIsAppOwnedRequeueWithoutSystemReschedule() {
        val decision = UidtJobStopPolicy.decide(JobParameters.STOP_REASON_CANCELLED_BY_APP)

        assertEquals(UidtJobStopDisposition.APP_OWNED_REQUEUE, decision.disposition)
        assertEquals(DownloadQueueScheduleTrigger.VISIBLE_LIVENESS, decision.scheduleAfterStop)
        assertFalse(decision.shouldAskJobSchedulerToReschedule)
    }

    @Test
    fun userStopPausesAndDoesNotReschedule() {
        val decision = UidtJobStopPolicy.decide(JobParameters.STOP_REASON_USER)

        assertEquals(UidtJobStopDisposition.PAUSE_ACTIVE, decision.disposition)
        assertNull(decision.scheduleAfterStop)
        assertFalse(decision.shouldAskJobSchedulerToReschedule)
    }

    @Test
    fun connectivityStopKeepsSystemOwnedReschedule() {
        val decision = UidtJobStopPolicy.decide(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY)

        assertEquals(UidtJobStopDisposition.SYSTEM_OWNED_REQUEUE, decision.disposition)
        assertNull(decision.scheduleAfterStop)
        assertTrue(decision.shouldAskJobSchedulerToReschedule)
    }
}
