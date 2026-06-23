package com.makd.afinity.data.repository.download

import android.app.job.JobInfo
import com.makd.afinity.data.repository.ServerUserToken
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueSchedulePlannerTest {
    private val planner = DownloadQueueSchedulePlanner()

    @Test
    fun api31UsesWorkManagerAndNeverSelectsUidt() {
        val plan =
            planner.plan(
                sdkInt = 31,
                trigger = DownloadQueueScheduleTrigger.PASSIVE_BACKGROUND,
                isVisible = false,
                queuedCount = 1,
                notificationsAllowed = false,
            )

        assertEquals(DownloadQueueSchedulePlanner.Plan.ScheduleWorkManager, plan)
    }

    @Test
    fun api33UsesWorkManagerAndNeverSelectsUidt() {
        val plan =
            planner.plan(
                sdkInt = 33,
                trigger = DownloadQueueScheduleTrigger.USER_ACTION,
                isVisible = true,
                queuedCount = 1,
                notificationsAllowed = true,
            )

        assertEquals(DownloadQueueSchedulePlanner.Plan.ScheduleWorkManager, plan)
    }

    @Test
    fun emptyQueueDoesNotScheduleBackend() {
        val plan =
            planner.plan(
                sdkInt = 34,
                trigger = DownloadQueueScheduleTrigger.USER_ACTION,
                isVisible = true,
                queuedCount = 0,
                notificationsAllowed = true,
            )

        assertEquals(DownloadQueueSchedulePlanner.Plan.NoEligibleRows, plan)
    }

    @Test
    fun activeDownloadNeverSchedulesSecondBackend() {
        val plan =
            planner.plan(
                sdkInt = 34,
                trigger = DownloadQueueScheduleTrigger.VISIBLE_LIVENESS,
                isVisible = true,
                queuedCount = 39,
                activeDownloadCount = 1,
                notificationsAllowed = true,
            )

        assertEquals(DownloadQueueSchedulePlanner.Plan.BackendAlreadyRunning, plan)
    }

    @Test
    fun api34PassiveBackgroundDefersUidtWhenNotVisible() {
        val plan =
            planner.plan(
                sdkInt = 34,
                trigger = DownloadQueueScheduleTrigger.PASSIVE_BACKGROUND,
                isVisible = false,
                queuedCount = 1,
                notificationsAllowed = true,
            )

        assertTrue(plan is DownloadQueueSchedulePlanner.Plan.DeferUidt)
    }

    @Test
    fun api34UserActionDefersUidtWhenNotVisible() {
        val plan =
            planner.plan(
                sdkInt = 34,
                trigger = DownloadQueueScheduleTrigger.USER_ACTION,
                isVisible = false,
                queuedCount = 1,
                notificationsAllowed = true,
            )

        assertTrue(plan is DownloadQueueSchedulePlanner.Plan.DeferUidt)
    }

    @Test
    fun api34PassiveBackgroundDefersUidtEvenWhenVisible() {
        val plan =
            planner.plan(
                sdkInt = 34,
                trigger = DownloadQueueScheduleTrigger.PASSIVE_BACKGROUND,
                isVisible = true,
                queuedCount = 1,
                notificationsAllowed = true,
            )

        assertTrue(plan is DownloadQueueSchedulePlanner.Plan.DeferUidt)
    }

    @Test
    fun api34LegacyWorkerDefersUidtEvenWhenVisible() {
        val plan =
            planner.plan(
                sdkInt = 34,
                trigger = DownloadQueueScheduleTrigger.LEGACY_WORKER,
                isVisible = true,
                queuedCount = 1,
                notificationsAllowed = true,
            )

        assertTrue(plan is DownloadQueueSchedulePlanner.Plan.DeferUidt)
    }

    @Test
    fun api34MigrationDefersUidtEvenWhenVisible() {
        val plan =
            planner.plan(
                sdkInt = 34,
                trigger = DownloadQueueScheduleTrigger.MIGRATION,
                isVisible = true,
                queuedCount = 1,
                notificationsAllowed = true,
            )

        assertTrue(plan is DownloadQueueSchedulePlanner.Plan.DeferUidt)
    }

    @Test
    fun api34VisibleLivenessCanRetryDeferredUidtSchedule() {
        val plan =
            planner.plan(
                sdkInt = 34,
                trigger = DownloadQueueScheduleTrigger.VISIBLE_LIVENESS,
                isVisible = true,
                queuedCount = 1,
                notificationsAllowed = true,
            )

        assertEquals(DownloadQueueSchedulePlanner.Plan.ScheduleUidt, plan)
    }

    @Test
    fun api34VisibleUserActionCanScheduleUidt() {
        val plan =
            planner.plan(
                sdkInt = 34,
                trigger = DownloadQueueScheduleTrigger.USER_ACTION,
                isVisible = true,
                queuedCount = 1,
                notificationsAllowed = true,
            )

        assertEquals(DownloadQueueSchedulePlanner.Plan.ScheduleUidt, plan)
    }

    @Test
    fun api34BlockedNotificationDefersUidt() {
        val plan =
            planner.plan(
                sdkInt = 34,
                trigger = DownloadQueueScheduleTrigger.USER_ACTION,
                isVisible = true,
                queuedCount = 1,
                notificationsAllowed = false,
            )

        assertTrue(plan is DownloadQueueSchedulePlanner.Plan.DeferUidt)
    }

    @Test
    fun byteEstimateUsesKnownRemainingOrUnknown() {
        assertEquals(
            300L,
            planner.estimateBytes(
                listOf(
                    QueueByteEstimate(bytesDownloaded = 50L, totalBytes = 200L),
                    QueueByteEstimate(bytesDownloaded = 0L, totalBytes = 150L),
                )
            ),
        )
        assertEquals(
            JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
            planner.estimateBytes(listOf(QueueByteEstimate(bytesDownloaded = 0L, totalBytes = 0L))),
        )
    }

    @Test
    fun exactRowOwnedTokenMatchesTwoUsersOnSameServer() {
        val serverId = "server-a"
        val userA = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val userB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val tokens =
            listOf(
                ServerUserToken(serverId, userA, "token-a", "A", "https://a.example"),
                ServerUserToken(serverId, userB, "token-b", "B", "https://b.example"),
            )

        val token = SessionRestoreResolver.findExactToken(tokens, serverId, userB)

        assertEquals("token-b", token?.accessToken)
        assertEquals(userB, token?.userId)
    }
}
