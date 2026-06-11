package com.makd.afinity.data.local

import com.makd.afinity.data.models.download.DownloadStatus
import com.makd.afinity.data.repository.DatabaseRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class LocalLibraryScanService
@Inject
    constructor(
        private val rootStore: LocalLibraryRootStore,
        private val rootBootstrapper: LocalLibraryRootBootstrapper,
        private val scanner: LocalLibraryScanner,
        private val migrationService: LocalLibraryMigrationService,
    private val databaseRepository: DatabaseRepository,
) {
    suspend fun scanEnabledRoots(
        visibilityContext: LocalLibraryVisibilityContext
    ): List<LocalLibraryScanSummary> =
        withContext(Dispatchers.IO) {
            val scanContext = currentCoroutineContext()
            rootBootstrapper.ensureDefaultRoot()
            rootStore
                .getRoots()
                .filter { it.enabled }
                .map { root ->
                    scanner.scanRoot(
                        root,
                        visibilityContext,
                        shouldCancel = { !scanContext.isActive },
                    )
                }
        }

    suspend fun scanRoot(
        root: LocalLibraryRootRecord,
        visibilityContext: LocalLibraryVisibilityContext,
    ): LocalLibraryScanSummary = withContext(Dispatchers.IO) {
        val scanContext = currentCoroutineContext()
        scanner.scanRoot(root, visibilityContext, shouldCancel = { !scanContext.isActive })
    }

    suspend fun migrateLegacyDownloads(
        root: LocalLibraryRootRecord,
        visibilityContext: LocalLibraryVisibilityContext,
    ): LocalLibraryLegacyMigrationResult =
        withContext(Dispatchers.IO) {
            val scanContext = currentCoroutineContext()
            val migration = migrationService.migrateLegacyDownloads(root, completedDownloads())
            val scan =
                scanner.scanRoot(root, visibilityContext, shouldCancel = { !scanContext.isActive })
            LocalLibraryLegacyMigrationResult(migration, scan)
        }

    private suspend fun completedDownloads() =
        databaseRepository.getDownloadsByStatusFlow(listOf(DownloadStatus.COMPLETED)).first()
}

data class LocalLibraryLegacyMigrationResult(
    val migration: LocalLibraryMigrationSummary,
    val scan: LocalLibraryScanSummary,
)
