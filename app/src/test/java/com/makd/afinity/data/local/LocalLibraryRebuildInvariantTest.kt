package com.makd.afinity.data.local

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryRebuildInvariantTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val userId = "00000000-0000-0000-0000-00000000ff01"

    @Test
    fun roomIndexWipeRebuildsOfflineHomeAndPlaybackFromFiles() = runBlocking {
        val rootDir = temporaryFolder.newFolder("library")
        val root =
            LocalLibraryRootRecord(
                registryId = UUID.fromString("00000000-0000-0000-0000-00000000ff02"),
                stableRootId = UUID.fromString("00000000-0000-0000-0000-00000000ff03"),
                displayName = "Library",
                kind = LocalLibraryRootKind.APP_PRIVATE,
                uriOrPath = rootDir.absolutePath,
            )
        writeMovie(rootDir, root)

        val fileSystem = FilePathLibraryFileSystem()
        val rebuiltIndex = InMemoryLocalLibraryIndexRepository()
        LocalLibraryScanner(
                fileSystem = fileSystem,
                sidecarReader = LocalLibrarySidecarReader(),
                indexRepository = rebuiltIndex,
                pathPolicy = LocalLibraryPathPolicy(),
            )
            .scanRoot(
                root,
                LocalLibraryVisibilityContext(
                    currentUserId = userId,
                    kidModeEnabled = false,
                    parentUnlocked = false,
                ),
            )

        val movies =
            LocalLibraryMediaRepository(
                    rootStore = FakeRootStore(root),
                    indexRepository = rebuiltIndex,
                    fileSystem = fileSystem,
                    userStateRepository = EmptyLocalMediaUserStateRepository,
                )
                .getVisibleMovies(userId)
        val mediaFileId = rebuiltIndex.visibleMediaFiles().single().mediaFileId
        val playback =
            LocalPlaybackSourceResolver(
                    roots = { listOf(root) },
                    fileSystem = fileSystem,
                    indexRepository = rebuiltIndex,
                )
                .resolve(
                    LocalPlaybackResolutionRequest(
                        mediaFileId = mediaFileId,
                        visibilityContext =
                            LocalLibraryVisibilityContext(
                                currentUserId = userId,
                                kidModeEnabled = false,
                                parentUnlocked = false,
                            ),
                    )
                )

        assertEquals("Rebuild Movie", movies.single().name)
        assertTrue(playback is LocalPlaybackResolution.Resolved)
    }

    private fun writeMovie(rootDir: File, root: LocalLibraryRootRecord) {
        val media = File(rootDir, "Movies/Rebuild Movie (2026)/Rebuild Movie (2026).mkv")
        media.parentFile?.mkdirs()
        media.writeBytes(ByteArray(32))
        File(rootDir, "Movies/Rebuild Movie (2026)/Rebuild Movie (2026).afinity.json")
            .writeText(
                """
                {
                  "schemaVersion": 1,
                  "mediaKind": "movie",
                  "server": { "serverId": "server" },
                  "user": { "userId": "$userId" },
                  "identity": {
                    "itemId": "00000000-0000-0000-0000-00000000ff04",
                    "sourceId": "source-1"
                  },
                  "localIdentity": {
                    "localItemId": "00000000-0000-0000-0000-00000000ff04",
                    "stableRootId": "${root.stableRootId}",
                    "relativePathAtWrite": "Movies/Rebuild Movie (2026)/Rebuild Movie (2026).mkv"
                  },
                  "titles": { "name": "Rebuild Movie", "year": 2026 },
                  "mediaFile": {
                    "relativePath": "Movies/Rebuild Movie (2026)/Rebuild Movie (2026).mkv",
                    "container": "mkv",
                    "sizeBytes": 32,
                    "runtimeTicks": 100
                  }
                }
                """
                    .trimIndent()
            )
    }

    private class FakeRootStore(private val root: LocalLibraryRootRecord) : LocalLibraryRootStore {
        override fun rootsFlow(): Flow<List<LocalLibraryRootRecord>> = flowOf(listOf(root))

        override suspend fun getRoots(): List<LocalLibraryRootRecord> = listOf(root)

        override suspend fun replaceRoots(roots: List<LocalLibraryRootRecord>) = Unit

        override suspend fun upsertRoot(root: LocalLibraryRootRecord) = Unit

        override suspend fun removeRoot(registryId: UUID) = Unit

        override suspend fun setDefaultDownloadRoot(registryId: UUID) = Unit
    }

    private object EmptyLocalMediaUserStateRepository : LocalMediaUserStateRepository {
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
}
