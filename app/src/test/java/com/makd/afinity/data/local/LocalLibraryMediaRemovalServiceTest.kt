package com.makd.afinity.data.local

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryMediaRemovalServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun removeFromLocalLibraryHidesItemWithoutDeletingPhysicalFile() = runBlocking {
        val userId = "user-1"
        val rootDir = temporaryFolder.newFolder("library")
        val media = File(rootDir, "Movies/Detached (2026)/Detached (2026).mkv")
        media.parentFile?.mkdirs()
        media.writeBytes(ByteArray(8))
        val root =
            LocalLibraryRootRecord(
                registryId = UUID.fromString("00000000-0000-0000-0000-00000000fb01"),
                stableRootId = UUID.fromString("00000000-0000-0000-0000-00000000fb02"),
                displayName = "Library",
                kind = LocalLibraryRootKind.APP_PRIVATE,
                uriOrPath = rootDir.absolutePath,
            )
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(root, listOf(record(root)))
        val visibilityRepository = FakeVisibilityRepository()

        LocalLibraryMediaRemovalService(
                indexRepository = index,
                visibilityRepository = visibilityRepository,
                deletionPolicy = LocalLibraryDeletionPolicy(),
            )
            .removeFromLocalLibrary(index.visibleMediaFiles().single().mediaFileId, userId)

        val movies =
            LocalLibraryMediaRepository(
                    rootStore = FakeRootStore(root),
                    indexRepository = index,
                    fileSystem = FilePathLibraryFileSystem(),
                    userStateRepository = NoopLocalMediaUserStateRepository,
                    visibilityRepository = visibilityRepository,
                )
                .getVisibleMovies(userId)

        assertTrue(media.exists())
        assertTrue(movies.isEmpty())
    }

    private fun record(root: LocalLibraryRootRecord): LocalMediaFileRecord =
        LocalMediaFileRecord(
            mediaFileId = UUID.fromString("00000000-0000-0000-0000-00000000fb03"),
            rootRegistryId = root.registryId,
            stableRootId = root.stableRootId,
            relativePath = "Movies/Detached (2026)/Detached (2026).mkv",
            sidecarRelativePath = null,
            mediaKind = LocalMediaKind.MOVIE,
            identity =
                LocalMediaIdentity(
                    localItemId = "detached",
                    serverId = null,
                    jellyfinItemId = null,
                    jellyfinSourceId = null,
                    stableRootId = root.stableRootId,
                    fingerprint = LocalMediaFingerprint("test", "detached"),
                ),
            title = LocalLibraryTitle(name = "Detached", year = 2026),
            sizeBytes = 8,
            modifiedAt = 1,
            container = "mkv",
            runtimeTicks = null,
        )

    private class FakeRootStore(private val root: LocalLibraryRootRecord) : LocalLibraryRootStore {
        override fun rootsFlow(): Flow<List<LocalLibraryRootRecord>> = flowOf(listOf(root))

        override suspend fun getRoots(): List<LocalLibraryRootRecord> = listOf(root)

        override suspend fun replaceRoots(roots: List<LocalLibraryRootRecord>) = Unit

        override suspend fun upsertRoot(root: LocalLibraryRootRecord) = Unit

        override suspend fun removeRoot(registryId: UUID) = Unit

        override suspend fun setDefaultDownloadRoot(registryId: UUID) = Unit
    }

    private object NoopLocalMediaUserStateRepository : LocalMediaUserStateRepository {
        override fun getState(
            localItemId: String,
            profileUserId: String,
        ): LocalMediaUserStateRecord? = null

        override fun getStates(profileUserId: String): Map<String, LocalMediaUserStateRecord> =
            emptyMap()

        override fun savePlaybackProgress(
            mediaFileId: UUID,
            profileUserId: String,
            positionTicks: Long,
            played: Boolean,
        ): Boolean = false
    }

    private class FakeVisibilityRepository : LocalMediaVisibilityRepository {
        private val hidden = mutableMapOf<String, MutableSet<String>>()

        override fun hiddenLocalItemIds(profileUserId: String): Set<String> =
            hidden[profileUserId].orEmpty()

        override fun hideLocalItem(
            localItemId: String,
            profileUserId: String,
            reason: String,
        ) {
            hidden.getOrPut(profileUserId) { mutableSetOf() } += localItemId
        }
    }
}
