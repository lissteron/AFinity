package com.makd.afinity.data.local

import com.makd.afinity.data.database.dao.LocalLibraryDao
import com.makd.afinity.data.database.entities.LocalLibraryItemEntity
import com.makd.afinity.data.database.entities.LocalLibraryRootSnapshotEntity
import com.makd.afinity.data.database.entities.LocalLibraryScanRunEntity
import com.makd.afinity.data.database.entities.LocalMediaFileEntity
import com.makd.afinity.data.database.entities.LocalMediaIdentityEntity
import com.makd.afinity.data.database.entities.LocalMediaImportJobEntity
import com.makd.afinity.data.database.entities.LocalMediaSidecarEntity
import com.makd.afinity.data.database.entities.LocalMediaUserStateEntity
import com.makd.afinity.data.database.entities.LocalMediaVisibilityEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class RoomLocalLibraryIndexRepositoryTest {
    private val rootId = UUID.fromString("00000000-0000-0000-0000-00000000aa01")
    private val stableRootId = UUID.fromString("00000000-0000-0000-0000-00000000aa02")
    private val mediaFileId = UUID.fromString("00000000-0000-0000-0000-00000000aa03")

    @Test
    fun roomIndexPreservesSidecarIdentityAcrossRepositoryRead() {
        val dao = FakeLocalLibraryDao()
        val repository = RoomLocalLibraryIndexRepository(dao)
        val record = record()

        repository.replaceRootScan(root(), listOf(record))

        val restored = repository.allMediaFiles().single()
        assertEquals(record.identity.localItemId, restored.identity.localItemId)
        assertEquals(record.identity.serverId, restored.identity.serverId)
        assertEquals(record.identity.jellyfinItemId, restored.identity.jellyfinItemId)
        assertEquals(record.identity.jellyfinSourceId, restored.identity.jellyfinSourceId)
        assertEquals(record.identity.jellyfinSeriesId, restored.identity.jellyfinSeriesId)
        assertEquals(record.identity.jellyfinSeasonId, restored.identity.jellyfinSeasonId)
        assertEquals(record.identity.providerIds, restored.identity.providerIds)
        assertEquals(record.identity.fingerprint, restored.identity.fingerprint)
        assertEquals(record.identity.durableKey, restored.identity.durableKey)
        assertEquals(record.stableRootId, restored.stableRootId)
    }

    @Test
    fun replacingRootScanWithNoFilesRemovesOrphanedIndexRows() {
        val dao = FakeLocalLibraryDao()
        val repository = RoomLocalLibraryIndexRepository(dao)
        repository.replaceRootScan(root(), listOf(record()))

        repository.replaceRootScan(root(), emptyList())

        assertEquals(emptyList<LocalMediaFileRecord>(), repository.allMediaFiles())
        assertNull(dao.getIdentity("local-movie"))
    }

    @Test
    fun visibleRowsExcludeDisabledRootSnapshots() {
        val dao = FakeLocalLibraryDao()
        val repository = RoomLocalLibraryIndexRepository(dao)

        repository.replaceRootScan(root(enabled = false), listOf(record()))

        assertEquals(emptyList<LocalMediaFileRecord>(), repository.visibleMediaFiles())
    }

    @Test
    fun rootRescanPreservesBackfilledParentIdentityWhenOldSidecarLacksIt() {
        val dao = FakeLocalLibraryDao()
        val repository = RoomLocalLibraryIndexRepository(dao)
        val indexedRecord = record()
        val scannedFromOldSidecar =
            indexedRecord.copy(
                identity =
                    indexedRecord.identity.copy(
                        jellyfinSeriesId = null,
                        jellyfinSeasonId = null,
                    )
            )

        repository.replaceRootScan(root(), listOf(indexedRecord))
        repository.replaceRootScan(root(), listOf(scannedFromOldSidecar))

        val restored = repository.allMediaFiles().single()
        assertEquals(indexedRecord.identity.jellyfinSeriesId, restored.identity.jellyfinSeriesId)
        assertEquals(indexedRecord.identity.jellyfinSeasonId, restored.identity.jellyfinSeasonId)
    }

    private fun root(enabled: Boolean = true): LocalLibraryRootRecord =
        LocalLibraryRootRecord(
            registryId = rootId,
            stableRootId = stableRootId,
            displayName = "Library",
            kind = LocalLibraryRootKind.APP_PRIVATE,
            uriOrPath = "/library",
            enabled = enabled,
        )

    private fun record(): LocalMediaFileRecord =
        LocalMediaFileRecord(
            mediaFileId = mediaFileId,
            rootRegistryId = rootId,
            stableRootId = stableRootId,
            relativePath = "Movies/WALL-E (2008)/WALL-E (2008).mkv",
            sidecarRelativePath = "Movies/WALL-E (2008)/WALL-E (2008).afinity.json",
            mediaKind = LocalMediaKind.MOVIE,
            identity =
                LocalMediaIdentity(
                    localItemId = "local-movie",
                    serverId = "server-1",
                    jellyfinItemId = "movie-1",
                    jellyfinSourceId = "source-1",
                    jellyfinSeriesId = "series-1",
                    jellyfinSeasonId = "season-1",
                    providerIds = mapOf("Imdb" to "tt0910970"),
                    stableRootId = stableRootId,
                    fingerprint = LocalMediaFingerprint("sha256-v1", "abc123"),
                ),
            title = LocalLibraryTitle(name = "WALL-E", year = 2008),
            sizeBytes = 123,
            modifiedAt = 456,
            container = "mkv",
            runtimeTicks = 789,
        )

    private class FakeLocalLibraryDao : LocalLibraryDao {
        private val roots = linkedMapOf<String, LocalLibraryRootSnapshotEntity>()
        private val items = linkedMapOf<String, LocalLibraryItemEntity>()
        private val files = linkedMapOf<String, LocalMediaFileEntity>()
        private val identities = linkedMapOf<String, LocalMediaIdentityEntity>()
        private val sidecars = linkedMapOf<String, LocalMediaSidecarEntity>()
        private val importJobs = linkedMapOf<String, LocalMediaImportJobEntity>()
        private val userStates = linkedMapOf<Pair<String, String>, LocalMediaUserStateEntity>()
        private val visibilities =
            linkedMapOf<Pair<String, String>, LocalMediaVisibilityEntity>()

        override fun catalogGenerationFlow(): Flow<String> = flowOf("fake")

        override fun upsertRootSnapshot(snapshot: LocalLibraryRootSnapshotEntity) {
            roots[snapshot.registryId] = snapshot
        }

        override fun upsertItems(items: List<LocalLibraryItemEntity>) {
            items.forEach { this.items[it.localItemId] = it }
        }

        override fun upsertMediaFiles(files: List<LocalMediaFileEntity>) {
            files.forEach { this.files[it.mediaFileId] = it }
        }

        override fun upsertIdentities(identities: List<LocalMediaIdentityEntity>) {
            identities.forEach { this.identities[it.localItemId] = it }
        }

        override fun upsertSidecars(sidecars: List<LocalMediaSidecarEntity>) {
            sidecars.forEach { this.sidecars[it.mediaFileId] = it }
        }

        override fun upsertImportJob(job: LocalMediaImportJobEntity) {
            importJobs[job.jobId] = job
        }

        override fun upsertImportJobs(jobs: List<LocalMediaImportJobEntity>) {
            jobs.forEach { importJobs[it.jobId] = it }
        }

        override fun upsertScanRun(scanRun: LocalLibraryScanRunEntity) = Unit

        override fun upsertUserState(state: LocalMediaUserStateEntity) {
            userStates[state.localItemId to state.profileUserId] = state
        }

        override fun upsertVisibility(visibility: LocalMediaVisibilityEntity) {
            visibilities[visibility.localItemId to visibility.profileUserId] = visibility
        }

        override fun deleteMediaFilesForRoot(rootRegistryId: String) {
            files.entries.removeAll { it.value.rootRegistryId == rootRegistryId }
        }

        override fun deleteImportJobsForRoot(rootRegistryId: String) {
            importJobs.entries.removeAll { it.value.rootRegistryId == rootRegistryId }
        }

        override fun deleteOrphanedIdentities() {
            val localItemIds = files.values.mapTo(mutableSetOf()) { it.localItemId }
            identities.keys.removeAll { it !in localItemIds }
        }

        override fun deleteOrphanedItems() {
            val localItemIds = files.values.mapTo(mutableSetOf()) { it.localItemId }
            items.keys.removeAll { it !in localItemIds }
        }

        override fun deleteOrphanedSidecars() {
            sidecars.keys.removeAll { it !in files.keys }
        }

        override fun getAllMediaFiles(): List<LocalMediaFileEntity> =
            files.values.sortedWith(compareBy<LocalMediaFileEntity> { it.rootRegistryId }.thenBy { it.relativePath })

        override fun getMediaFile(mediaFileId: String): LocalMediaFileEntity? = files[mediaFileId]

        override fun getMediaFileForLocalItem(localItemId: String): LocalMediaFileEntity? =
            getAllMediaFiles().firstOrNull { it.localItemId == localItemId }

        override fun getIdentity(localItemId: String): LocalMediaIdentityEntity? = identities[localItemId]

        override fun getIdentities(): List<LocalMediaIdentityEntity> = identities.values.toList()

        override fun getRootSnapshot(registryId: String): LocalLibraryRootSnapshotEntity? = roots[registryId]

        override fun getRootSnapshots(): List<LocalLibraryRootSnapshotEntity> = roots.values.toList()

        override fun getImportJobs(): List<LocalMediaImportJobEntity> = importJobs.values.toList()

        override fun getUserState(
            localItemId: String,
            profileUserId: String,
        ): LocalMediaUserStateEntity? = userStates[localItemId to profileUserId]

        override fun getUserStates(profileUserId: String): List<LocalMediaUserStateEntity> =
            userStates.values.filter { it.profileUserId == profileUserId }

        override fun getVisibilities(profileUserId: String): List<LocalMediaVisibilityEntity> =
            visibilities.values.filter { it.profileUserId == profileUserId }

        override fun hideMediaFile(mediaFileId: String): Int {
            val file = files[mediaFileId] ?: return 0
            files[mediaFileId] = file.copy(visibleByDefault = false)
            return 1
        }
    }
}
