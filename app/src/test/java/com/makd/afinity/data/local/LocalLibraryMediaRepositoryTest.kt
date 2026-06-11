package com.makd.afinity.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalLibraryMediaRepositoryTest {
    private val rootId = UUID.fromString("00000000-0000-0000-0000-00000000bb01")
    private val mediaFileId = UUID.fromString("00000000-0000-0000-0000-00000000bb02")
    private val userId = "00000000-0000-0000-0000-00000000bb03"

    @Test
    fun localMoviesUseLocalUserStateForProgressAfterIndexRebuild() = runBlocking {
        val root = root()
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(root, listOf(movieRecord(root)))
        val userStateRepository =
            FakeLocalMediaUserStateRepository(
                mapOf(
                    "local-movie" to
                        LocalMediaUserStateRecord(
                            localItemId = "local-movie",
                            profileUserId = userId,
                            serverId = null,
                            jellyfinUserId = userId,
                            jellyfinItemId = null,
                            playbackPositionTicks = 42_000_000L,
                            played = false,
                            favorite = true,
                            updatedAt = 1L,
                        )
                )
            )

        val movies =
            LocalLibraryMediaRepository(
                    rootStore = FakeRootStore(root),
                    indexRepository = index,
                    fileSystem = FilePathLibraryFileSystem(),
                    userStateRepository = userStateRepository,
                )
                .getVisibleMovies(userId)

        assertEquals(1, movies.size)
        assertEquals(42_000_000L, movies.single().playbackPositionTicks)
        assertTrue(movies.single().favorite)
    }

    @Test
    fun localShowsAndSeasonsAreSynthesizedFromEpisodeRecordsAfterIndexRebuild() = runBlocking {
        val root = root()
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(root, listOf(episodeRecord(root)))

        val shows =
            LocalLibraryMediaRepository(
                    rootStore = FakeRootStore(root),
                    indexRepository = index,
                    fileSystem = FilePathLibraryFileSystem(),
                    userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
                )
                .getVisibleShows(userId)

        val show = shows.single()
        assertEquals("Bluey", show.name)
        assertEquals(1, show.seasons.single().indexNumber)
        assertEquals("Keepy Uppy", show.seasons.single().episodes.single().name)
    }

    @Test
    fun localMoviesAreFilteredByCurrentProfileOwnerHint() = runBlocking {
        val root = root()
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(root, listOf(movieRecord(root, ownerUserId = userId)))

        val repository =
            LocalLibraryMediaRepository(
                rootStore = FakeRootStore(root),
                indexRepository = index,
                fileSystem = FilePathLibraryFileSystem(),
                userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
            )

        assertEquals(1, repository.getVisibleMovies(userId).size)
        assertTrue(repository.getVisibleMovies("other-user").isEmpty())
    }

    @Test
    fun localMoviesDoNotExposeDisabledRoots() = runBlocking {
        val root = root(enabled = false)
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(root.copy(enabled = true), listOf(movieRecord(root)))

        val movies =
            LocalLibraryMediaRepository(
                    rootStore = FakeRootStore(root),
                    indexRepository = index,
                    fileSystem = FilePathLibraryFileSystem(),
                    userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
                )
                .getVisibleMovies(userId)

        assertTrue(movies.isEmpty())
    }

    private fun root(enabled: Boolean = true): LocalLibraryRootRecord =
        LocalLibraryRootRecord(
            registryId = rootId,
            stableRootId = rootId,
            displayName = "Library",
            kind = LocalLibraryRootKind.APP_PRIVATE,
            uriOrPath = File("/tmp/library").absolutePath,
            enabled = enabled,
        )

    private fun movieRecord(
        root: LocalLibraryRootRecord,
        ownerUserId: String? = null,
    ): LocalMediaFileRecord =
        LocalMediaFileRecord(
            mediaFileId = mediaFileId,
            rootRegistryId = root.registryId,
            stableRootId = root.stableRootId,
            relativePath = "Movies/Movie (2026)/Movie (2026).mkv",
            sidecarRelativePath = null,
            ownerUserId = ownerUserId,
            mediaKind = LocalMediaKind.MOVIE,
            identity =
                LocalMediaIdentity(
                    localItemId = "local-movie",
                    serverId = null,
                    jellyfinItemId = null,
                    jellyfinSourceId = null,
                    stableRootId = root.stableRootId,
                    fingerprint = LocalMediaFingerprint("test", "fingerprint"),
                ),
            title = LocalLibraryTitle("Movie", year = 2026),
            sizeBytes = 10L,
            modifiedAt = 1L,
            container = "mkv",
            runtimeTicks = 100_000_000L,
        )

    private fun episodeRecord(root: LocalLibraryRootRecord): LocalMediaFileRecord =
        LocalMediaFileRecord(
            mediaFileId = UUID.fromString("00000000-0000-0000-0000-00000000bb04"),
            rootRegistryId = root.registryId,
            stableRootId = root.stableRootId,
            relativePath = "Shows/Bluey/Season 01/Bluey - S01E03 - Keepy Uppy.mkv",
            sidecarRelativePath = "Shows/Bluey/Season 01/Bluey - S01E03 - Keepy Uppy.afinity.json",
            mediaKind = LocalMediaKind.EPISODE,
            identity =
                LocalMediaIdentity(
                    localItemId = "local-episode",
                    serverId = null,
                    jellyfinItemId = null,
                    jellyfinSourceId = null,
                    stableRootId = root.stableRootId,
                    fingerprint = LocalMediaFingerprint("test", "episode-fingerprint"),
                ),
            title =
                LocalLibraryTitle(
                    name = "Keepy Uppy",
                    showName = "Bluey",
                    seasonNumber = 1,
                    episodeNumber = 3,
                ),
            sizeBytes = 10L,
            modifiedAt = 1L,
            container = "mkv",
            runtimeTicks = 100_000_000L,
        )

    private class FakeRootStore(private val root: LocalLibraryRootRecord) : LocalLibraryRootStore {
        override fun rootsFlow(): Flow<List<LocalLibraryRootRecord>> = flowOf(listOf(root))

        override suspend fun getRoots(): List<LocalLibraryRootRecord> = listOf(root)

        override suspend fun replaceRoots(roots: List<LocalLibraryRootRecord>) = Unit

        override suspend fun upsertRoot(root: LocalLibraryRootRecord) = Unit

        override suspend fun removeRoot(registryId: UUID) = Unit

        override suspend fun setDefaultDownloadRoot(registryId: UUID) = Unit
    }

    private class FakeLocalMediaUserStateRepository(
        private val statesByLocalItemId: Map<String, LocalMediaUserStateRecord>
    ) : LocalMediaUserStateRepository {
        override fun getState(
            localItemId: String,
            profileUserId: String,
        ): LocalMediaUserStateRecord? = statesByLocalItemId[localItemId]

        override fun getStates(profileUserId: String): Map<String, LocalMediaUserStateRecord> =
            statesByLocalItemId

        override fun savePlaybackProgress(
            mediaFileId: UUID,
            profileUserId: String,
            positionTicks: Long,
            played: Boolean,
        ): Boolean = false
    }
}
