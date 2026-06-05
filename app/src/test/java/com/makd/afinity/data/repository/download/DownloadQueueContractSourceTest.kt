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
        assertTrue(source.contains("network != null && network == currentNetwork"))
        assertTrue(source.contains("NetworkChangeResult.Unchanged"))
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
    fun uidtProgressUpdatesKeepNotificationBoundToJob() {
        val source =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/MediaDownloadQueueJobService.kt"
            )
        val progressObserverIndex = source.indexOf(") { progress ->")
        assertTrue(progressObserverIndex >= 0)

        val finishLogIndex = source.indexOf("Timber.i(", progressObserverIndex)
        assertTrue(finishLogIndex > progressObserverIndex)

        val progressObserverSource = source.substring(progressObserverIndex, finishLogIndex)
        assertTrue(progressObserverSource.contains("setNotification("))
        assertTrue(progressObserverSource.contains("JOB_END_NOTIFICATION_POLICY_REMOVE"))
        assertTrue(!progressObserverSource.contains("NotificationManagerCompat.from"))
        assertTrue(!source.contains("import androidx.core.app.NotificationManagerCompat"))
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
    fun downloadQueueRecoveryIsNotBlockedByStaleServerUnreachableOfflineState() {
        val offlineManager =
            readSource("src/main/java/com/makd/afinity/data/manager/OfflineModeManager.kt")
        val repository =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/JellyfinDownloadRepository.kt"
            )

        assertTrue(offlineManager.contains("suspend fun isHardOffline()"))
        assertTrue(!offlineManager.substringAfter("suspend fun isHardOffline()").contains("isServerReachable"))

        val scheduleQueueSource =
            repository.substring(
                repository.indexOf("private suspend fun scheduleQueue"),
                repository.indexOf("private fun buildQueuedDownload"),
            )
        assertTrue(scheduleQueueSource.contains("offlineModeManager.isHardOffline()"))
        assertTrue(!scheduleQueueSource.contains("offlineModeManager.isCurrentlyOffline()"))

        val startDownloadSource =
            repository.substring(
                repository.indexOf("override suspend fun startDownload"),
                repository.indexOf("private suspend fun scheduleQueue"),
            )
        assertTrue(startDownloadSource.contains("offlineModeManager.isHardOffline()"))

        val resumeDownloadSource =
            repository.substring(
                repository.indexOf("override suspend fun resumeDownload"),
                repository.indexOf("override suspend fun cancelDownload"),
            )
        assertTrue(resumeDownloadSource.contains("offlineModeManager.isHardOffline()"))
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
    fun interruptedActiveDownloadsRecoverToQueuedNotPaused() {
        val stateStore =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/DownloadQueueStateStore.kt"
            )
        val dao = readSource("src/main/java/com/makd/afinity/data/database/dao/ServerDatabaseDao.kt")
        val migration =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/DownloadQueueMigration.kt"
            )
        val runner =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/DownloadQueueRunner.kt"
            )

        assertTrue(stateStore.contains("databaseRepository.requeueOrphanedActiveDownloads"))
        assertTrue(stateStore.contains("databaseRepository.requeueAllActiveDownloads"))
        assertTrue(stateStore.contains("suspend fun requeueAllActiveForTransientStop"))
        assertTrue(stateStore.contains("databaseRepository.requeuePausedDownloadsByError"))
        assertTrue(stateStore.contains("databaseRepository.requeuePausedDownloadsByErrorPattern"))
        assertTrue(stateStore.contains("databaseRepository.requeueZeroByteFailedDownloadsByError"))
        assertTrue(stateStore.contains("LEGACY_INTERRUPTED_PAUSED_REASON"))
        assertTrue(stateStore.contains("UIDT_STOPPED_PAUSED_REASON_PATTERN"))
        assertTrue(stateStore.contains("TRANSIENT_ZERO_BYTE_FAILED_REASONS"))
        assertTrue(stateStore.contains("\"Unknown IO error occurred!\""))
        assertTrue(stateStore.contains("\"HTTP host unreachable\""))
        assertTrue(!stateStore.contains("databaseRepository.pauseOrphanedActiveDownloads"))
        assertTrue(
            !stateStore
                .substringAfter("suspend fun reconcileStartupActiveClaims")
                .contains("pauseAllActiveDownloads")
        )
        assertTrue(dao.contains("abstract suspend fun requeueOrphanedActiveDownloads"))
        assertTrue(dao.contains("abstract suspend fun requeueAllActiveDownloads"))
        assertTrue(dao.contains("abstract suspend fun requeuePausedDownloadsByError"))
        assertTrue(dao.contains("abstract suspend fun requeuePausedDownloadsByErrorPattern"))
        assertTrue(dao.contains("abstract suspend fun requeueZeroByteFailedDownloadsByError"))
        assertTrue(dao.contains("status = 'FAILED'"))
        assertTrue(dao.contains("bytesDownloaded = 0"))
        assertTrue(dao.contains("totalBytes = 0"))
        assertTrue(dao.contains("SET status = 'QUEUED'"))
        assertTrue(migration.contains("Interrupted download recovered to queued state"))
        assertTrue(migration.contains("stateStore.requeuePausedInterruptedDownloads()"))
        assertTrue(migration.contains("stateStore.requeuePausedUidtStoppedDownloads()"))
        assertTrue(migration.contains("stateStore.requeueZeroByteTransientFailedDownloads()"))
        assertTrue(migration.contains("Requeued ${'$'}"))
        assertTrue(runner.contains("orphaned DOWNLOADING media rows to QUEUED"))
    }

    @Test
    fun systemUidtStopRequeuesInsteadOfPausingActiveDownload() {
        val jobService =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/MediaDownloadQueueJobService.kt"
            )
        val runner =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/DownloadQueueRunner.kt"
            )

        assertTrue(jobService.contains("queueRunner.requestPolicyRequeue("))
        assertTrue(jobService.contains("DownloadQueueScheduleTrigger.VISIBLE_LIVENESS"))
        assertTrue(jobService.contains("queueRunner.requestPauseActive("))
        assertTrue(jobService.contains("return startRequeueAllActiveCleanup("))
        assertTrue(!jobService.contains("startPauseAllActiveCleanup"))
        assertTrue(jobService.contains("stateStore.requeueAllActiveForTransientStop(reason = reason)"))
        assertTrue(jobService.contains("finishIfCurrent(runId, params, wantsReschedule = true)"))
        assertTrue(runner.contains("DownloadQueueStopDisposition.REQUEUE"))
        assertTrue(runner.contains("requestPolicyRequeue(\n                reason = reason"))
        assertTrue(!runner.contains("stopActive(\"UIDT required network is unavailable\")"))
        assertTrue(
            runner.contains(
                "stateStore.requeueOwned(\n                        downloadId = claim.downloadId"
            )
        )
    }

    @Test
    fun uidtTransientTransferFailuresRequeueAndTerminalFailuresKeepDiagnosticContext() {
        val source =
            readSource(
                "src/main/java/com/makd/afinity/data/repository/download/MediaDownloadTransferRunner.kt"
            )

        assertTrue(source.contains("requeueActiveDownload("))
        assertTrue(source.contains("UIDT required network changed repeatedly"))
        assertTrue(source.contains("result.message.isUidtTransientReason()"))
        assertTrue(source.contains("private fun String.isUidtTransientReason()"))
        assertTrue(source.contains("var failureStage = \"initializing media download\""))
        assertTrue(source.contains("opening media HTTP response"))
        assertTrue(source.contains("copying media stream"))
        assertTrue(source.contains("private fun Throwable.toDownloadFailureMessage(stage: String)"))
        assertTrue(source.contains("Media download failed at ${'$'}failureStage"))
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
        assertTrue(stateStore.contains("requeueOrphanedActiveDownloads"))
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
