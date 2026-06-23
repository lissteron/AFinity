package com.makd.afinity.data.local

import com.makd.afinity.data.database.entities.DownloadDto
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
        private val indexRepository: LocalLibraryIndexRepository,
        private val migrationService: LocalLibraryMigrationService,
        private val artworkBackfillService: LocalLibraryArtworkBackfillService,
        private val originArtworkRefresher: LocalLibraryOriginArtworkRefresher,
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

    suspend fun scanEnabledRootsWithArtworkBackfill(
        visibilityContext: LocalLibraryVisibilityContext
    ): List<LocalLibraryScanSummary> {
        if (indexRepository.allMediaFiles().isNotEmpty()) {
            val artworkBackfill = backfillDownloadedArtwork()
            val localScan = scanEnabledRoots(visibilityContext)
            val originRefresh = refreshLocalLibraryArtworkFromOrigins()
            return if (artworkBackfill.writtenFiles > 0 || originRefresh.changedFiles()) {
                scanEnabledRoots(visibilityContext)
            } else {
                localScan
            }
        }
        val initialScan = scanEnabledRoots(visibilityContext)
        val artworkBackfill = backfillDownloadedArtwork()
        val originRefresh = refreshLocalLibraryArtworkFromOrigins()
        return if (artworkBackfill.writtenFiles > 0 || originRefresh.changedFiles()) {
            scanEnabledRoots(visibilityContext)
        } else {
            initialScan
        }
    }

    suspend fun scanRoot(
        root: LocalLibraryRootRecord,
        visibilityContext: LocalLibraryVisibilityContext,
    ): LocalLibraryScanSummary = withContext(Dispatchers.IO) {
        val scanContext = currentCoroutineContext()
        scanner.scanRoot(root, visibilityContext, shouldCancel = { !scanContext.isActive })
    }

    suspend fun backfillDownloadedArtwork(): LocalLibraryArtworkBackfillSummary =
        backfillDownloadedArtwork(completedDownloads())

    suspend fun backfillDownloadedArtwork(
        downloads: List<DownloadDto>
    ): LocalLibraryArtworkBackfillSummary =
        withContext(Dispatchers.IO) { artworkBackfillService.backfillDownloads(downloads) }

    suspend fun refreshableLocalLibraryArtworkCount(): Int =
        originArtworkRefresher.refreshCandidateCount()

    suspend fun refreshLocalLibraryArtworkFromOrigins(
        progress: suspend (LocalLibraryOriginArtworkProgress) -> Unit = {}
    ): LocalLibraryOriginArtworkSummary =
        originArtworkRefresher.refreshMissingArtwork(progress)

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

    private fun LocalLibraryOriginArtworkSummary.changedFiles(): Boolean =
        writtenFiles > 0 || updatedSidecars > 0
}

data class LocalLibraryLegacyMigrationResult(
    val migration: LocalLibraryMigrationSummary,
    val scan: LocalLibraryScanSummary,
)
