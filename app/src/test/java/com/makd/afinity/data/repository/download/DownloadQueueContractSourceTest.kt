package com.makd.afinity.data.repository.download

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueContractSourceTest {
    @Test
    fun daoClaimIsDatabaseOwnedAndTransactional() {
        val source = readSource("src/main/java/com/makd/afinity/data/database/dao/ServerDatabaseDao.kt")

        assertTrue(source.contains("@Transaction\n    open suspend fun claimOldestQueuedDownload"))
        assertTrue(source.contains("val queued = getOldestQueuedDownload() ?: return null"))
        assertTrue(source.contains("NOT EXISTS (SELECT 1 FROM downloads WHERE status = 'DOWNLOADING')"))
        assertTrue(source.contains("activeBackendRunId = :activeBackendRunId"))
        assertTrue(source.contains("activeBackendKind = :activeBackendKind"))
        assertTrue(source.contains("claimHeartbeatAt = :updatedAt"))
    }

    @Test
    fun activeLeaseIsPersistedAndRequiredForRunnerOwnedWrites() {
        val entity = readSource("src/main/java/com/makd/afinity/data/database/entities/DownloadDto.kt")
        val dao = readSource("src/main/java/com/makd/afinity/data/database/dao/ServerDatabaseDao.kt")
        val migrations = readSource("src/main/java/com/makd/afinity/data/database/DatabaseMigrations.kt")

        assertTrue(entity.contains("val activeClaimId: UUID? = null"))
        assertTrue(entity.contains("val activeBackendRunId: UUID? = null"))
        assertTrue(entity.contains("val activeBackendKind: String? = null"))
        assertTrue(entity.contains("val claimStartedAt: Long? = null"))
        assertTrue(entity.contains("val claimHeartbeatAt: Long? = null"))
        assertTrue(migrations.contains("ALTER TABLE downloads ADD COLUMN activeClaimId TEXT"))
        assertTrue(migrations.contains("ALTER TABLE downloads ADD COLUMN activeBackendRunId TEXT"))
        assertTrue(migrations.contains("ALTER TABLE downloads ADD COLUMN activeBackendKind TEXT"))
        assertTrue(migrations.contains("ALTER TABLE downloads ADD COLUMN claimStartedAt INTEGER DEFAULT NULL"))
        assertTrue(migrations.contains("ALTER TABLE downloads ADD COLUMN claimHeartbeatAt INTEGER DEFAULT NULL"))
        assertTrue(dao.contains("activeClaimId = :activeClaimId"))
        assertTrue(dao.contains("activeBackendRunId = :activeBackendRunId"))
        assertTrue(
            dao.contains(
                "AND activeClaimId = :activeClaimId\n            AND activeBackendRunId = :activeBackendRunId\n            AND status = 'DOWNLOADING'"
            )
        )
    }

    @Test
    fun uidtNetworkClientIsBoundToRequiredNetwork() {
        val source =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/UidtNetworkSession.kt"
            )

        assertTrue(source.contains(".socketFactory(network.socketFactory)"))
        assertTrue(source.contains("network.getAllByName(hostname).toList()"))
        assertTrue(source.contains("activeClient?.dispatcher?.cancelAll()"))
    }

    @Test
    fun uidtJobServiceSetsNotificationBeforeStartingRunnerAndUsesFinishGate() {
        val source =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/MediaDownloadQueueJobService.kt"
            )
        val setNotificationIndex = source.indexOf("setNotification(")
        val runnerIndex = source.indexOf("queueRunner.run(")

        assertTrue(setNotificationIndex >= 0)
        assertTrue(runnerIndex > setNotificationIndex)
        assertTrue(source.contains("lifecycle.finishIfCurrent(runId)"))
        assertTrue(source.contains("jobFinished(params, wantsReschedule)"))
        assertTrue(source.contains("launch(start = CoroutineStart.UNDISPATCHED)"))
        assertTrue(source.contains("return startBackendStartFailureCleanup("))
        assertTrue(source.contains("backendStartFailureHandler.record(reason)"))
        assertTrue(!source.contains("private fun recordBackendStartFailure"))
    }

    @Test
    fun legacyMediaAndSidecarWorkersAreNeutralized() {
        val workerPaths =
            listOf(
                "src/main/java/com/makd/afinity/data/workers/MediaDownloadWorker.kt",
                "src/main/java/com/makd/afinity/data/workers/ImageDownloadWorker.kt",
                "src/main/java/com/makd/afinity/data/workers/SubtitleDownloadWorker.kt",
                "src/main/java/com/makd/afinity/data/workers/TrickplayDownloadWorker.kt",
            )

        workerPaths.forEach { path ->
            val source = readSource(path)
            assertTrue(source.contains("Neutralized legacy"))
            assertTrue(!source.contains("setForeground("))
            assertTrue(!source.contains("newCall("))
        }
    }

    @Test
    fun mediaRepositoryDoesNotCreateOldPerDownloadWorkerChains() {
        val source =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/JellyfinDownloadRepository.kt"
            )

        assertTrue(!source.contains("OneTimeWorkRequestBuilder<MediaDownloadWorker>"))
        assertTrue(!source.contains("OneTimeWorkRequestBuilder<ImageDownloadWorker>"))
        assertTrue(!source.contains("OneTimeWorkRequestBuilder<SubtitleDownloadWorker>"))
        assertTrue(!source.contains("OneTimeWorkRequestBuilder<TrickplayDownloadWorker>"))
    }

    @Test
    fun policyViolationRequeueIsRunnerOwnedBeforeFallbackDbRequeue() {
        val source =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/DownloadQueuePolicyCoordinator.kt"
            )

        val runnerRequestIndex = source.indexOf("queueRunner.requestPolicyRequeue")
        val fallbackRequeueIndex = source.indexOf("stateStore.requeueOwned")

        assertTrue(runnerRequestIndex >= 0)
        assertTrue(fallbackRequeueIndex > runnerRequestIndex)
        assertTrue(
            source.contains("DownloadQueuePolicyRequeueRequestResult.RunnerWillRequeueAndReschedule")
        )
        assertTrue(source.contains("DownloadQueuePolicyRequeueRequestResult.ExistingStopRequestWins"))
    }

    @Test
    fun transferRunnerAppliesRequeueStopDispositionToActiveClaim() {
        val source =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/MediaDownloadTransferRunner.kt"
            )

        assertTrue(source.contains("DownloadQueueStopDisposition.REQUEUE"))
        assertTrue(
            source.contains(
                "stateStore.requeueOwned(downloadId, activeClaimId, activeBackendRunId, request.reason)"
            )
        )
    }

    @Test
    fun fixedWorkManagerFallbackCannotTransferOnApi34() {
        val source =
            readSource("src/main/java/com/makd/afinity/data/workers/MediaDownloadQueueWorker.kt")

        val api34GuardIndex = source.indexOf("Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE")
        val scheduleIndex = source.indexOf("scheduler.scheduleQueue(DownloadQueueScheduleTrigger.LEGACY_WORKER)")
        val runnerIndex = source.indexOf("queueRunner.run")

        assertTrue(api34GuardIndex >= 0)
        assertTrue(scheduleIndex > api34GuardIndex)
        assertTrue(runnerIndex > scheduleIndex)
    }

    @Test
    fun migrationCancelsFixedFallbackQueueWork() {
        val source =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/DownloadQueueMigration.kt"
            )

        assertTrue(source.contains("workManager.cancelUniqueWork(DownloadQueueScheduler.WORK_NAME)"))
        assertTrue(source.contains("workManager.cancelAllWorkByTag(DownloadQueueScheduler.WORK_TAG)"))
        assertTrue(source.contains("stateStore.reconcileStartupActiveClaims"))
        assertTrue(source.contains("KEY_LEGACY_CLEANUP_COMPLETE"))
        assertTrue(source.contains("preferences.getBoolean(KEY_LEGACY_CLEANUP_COMPLETE, false)"))
    }

    @Test
    fun runtimeReconciliationUsesPersistedBackendLeaseNotAgeCutoff() {
        val runner =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/DownloadQueueRunner.kt"
            )
        val worker = readSource("src/main/java/com/makd/afinity/data/workers/MediaDownloadQueueWorker.kt")
        val jobService =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/MediaDownloadQueueJobService.kt"
            )
        val stateStore =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/DownloadQueueStateStore.kt"
            )
        val scheduler =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/DownloadQueueScheduler.kt"
            )
        val dao = readSource("src/main/java/com/makd/afinity/data/database/dao/ServerDatabaseDao.kt")

        assertTrue(!runner.contains("DownloadQueueReconciliationPolicy"))
        assertTrue(!worker.contains("DownloadQueueReconciliationPolicy"))
        assertTrue(!jobService.contains("DownloadQueueReconciliationPolicy"))
        assertTrue(runner.contains("stateStore.reconcileOrphanedActiveClaims"))
        assertTrue(worker.contains("backendStartFailureHandler.record"))
        assertTrue(jobService.contains("backendStartFailureHandler.record(reason)"))
        assertTrue(scheduler.contains("val snapshot = stateStore.snapshot()"))
        assertTrue(stateStore.contains("pauseOrphanedActiveDownloads"))
        assertTrue(stateStore.contains("reconcileStartupActiveClaims"))
        assertTrue(!stateStore.contains("hasRowsToNormalize"))
        assertTrue(dao.contains("activeBackendRunId IS NULL OR activeBackendRunId != :activeBackendRunId"))
    }

    private fun readSource(relativePath: String): String {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val rootOrModuleDir =
            generateSequence(userDir) { it.parentFile }
                .firstOrNull {
                    File(it, "src/main/java").exists() || File(it, "app/src/main/java").exists()
                }
                ?: error("Could not locate Android app source root from $userDir")
        val moduleDir =
            if (File(rootOrModuleDir, "src/main/java").exists()) {
                rootOrModuleDir
            } else {
                File(rootOrModuleDir, "app")
            }
        return File(moduleDir, relativePath).readText()
    }
}
