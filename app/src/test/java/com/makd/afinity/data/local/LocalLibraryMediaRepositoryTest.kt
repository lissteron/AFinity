package com.makd.afinity.data.local

import com.makd.afinity.data.models.media.AfinityEpisode
import com.makd.afinity.data.models.media.AfinityMovie
import com.makd.afinity.data.models.media.AfinitySeason
import com.makd.afinity.data.models.media.AfinityShow
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
    private val secondRootId = UUID.fromString("00000000-0000-0000-0000-00000000bb11")
    private val mediaFileId = UUID.fromString("00000000-0000-0000-0000-00000000bb02")
    private val userId = "00000000-0000-0000-0000-00000000bb03"
    private val firstSeriesId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val secondSeriesId = UUID.fromString("10000000-0000-0000-0000-000000000002")
    private val firstSeasonId = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val secondSeasonId = UUID.fromString("20000000-0000-0000-0000-000000000002")

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
                    userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
                )
                .getVisibleMovies(userId)

        assertTrue(movies.isEmpty())
    }

    @Test
    fun findVisibleItemByIdRestoresLocalRouteTargetsFromIndex() = runBlocking {
        val root = root()
        val movie = movieRecord(root)
        val episode = episodeRecord(root)
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(root, listOf(movie, episode))
        val repository =
            LocalLibraryMediaRepository(
                rootStore = FakeRootStore(root),
                indexRepository = index,
                userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
            )
        val homeShow = repository.getVisibleShows(userId).single()
        val homeSeason = homeShow.seasons.single()

        val movieItem =
            repository.findVisibleItemById(
                itemId = UUID.nameUUIDFromBytes("local-movie".toByteArray()),
                itemType = "Movie",
                profileUserId = userId,
            )
        val episodeItem =
            repository.findVisibleItemById(
                itemId = UUID.nameUUIDFromBytes("local-episode".toByteArray()),
                itemType = "Episode",
                profileUserId = userId,
            )
        val showItem =
            repository.findVisibleItemById(
                itemId = homeShow.id,
                itemType = "Series",
                profileUserId = userId,
            )
        val seasonItem =
            repository.findVisibleItemById(
                itemId = homeSeason.id,
                itemType = "Season",
                seriesId = homeShow.id,
                profileUserId = userId,
            )

        assertTrue(movieItem is AfinityMovie)
        assertTrue(episodeItem is AfinityEpisode)
        assertTrue(showItem is AfinityShow)
        assertTrue(seasonItem is AfinitySeason)
        assertEquals("Keepy Uppy", (showItem as AfinityShow).seasons.single().episodes.single().name)
        assertEquals("Keepy Uppy", (seasonItem as AfinitySeason).episodes.single().name)
    }

    @Test
    fun homeCatalogUsesLocalUserStateForContinueWatchingWithoutDownloads() = runBlocking {
        val root = root()
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(root, listOf(movieRecord(root), episodeRecord(root)))
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
                            favorite = false,
                            updatedAt = 1L,
                        ),
                    "local-episode" to
                        LocalMediaUserStateRecord(
                            localItemId = "local-episode",
                            profileUserId = userId,
                            serverId = null,
                            jellyfinUserId = userId,
                            jellyfinItemId = null,
                            playbackPositionTicks = 84_000_000L,
                            played = false,
                            favorite = false,
                            updatedAt = 2L,
                        ),
                )
            )

        val catalog =
            LocalLibraryMediaRepository(
                    rootStore = FakeRootStore(root),
                    indexRepository = index,
                    userStateRepository = userStateRepository,
                )
                .getHomeCatalog(profileUserId = userId)

        assertEquals(1, catalog.movies.size)
        assertEquals(1, catalog.shows.size)
        assertEquals(
            listOf(
                UUID.nameUUIDFromBytes("local-episode".toByteArray()),
                UUID.nameUUIDFromBytes("local-movie".toByteArray()),
            ),
            catalog.continueWatching.map { it.id },
        )
    }

    @Test
    fun homeCatalogUsesLazyLocalSourcesWithoutResolvingPlaybackUris() = runBlocking {
        val root = root()
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(root, listOf(movieRecord(root), episodeRecord(root)))
        val repository =
            LocalLibraryMediaRepository(
                rootStore = FakeRootStore(root),
                indexRepository = index,
                userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
            )

        val catalog = repository.getHomeCatalog(profileUserId = userId)
        val movieSource = catalog.movies.single().sources.single()
        val episodeSource = catalog.shows.single().seasons.single().episodes.single().sources.single()

        assertEquals(mediaFileId.toString(), movieSource.id)
        assertEquals("local://$mediaFileId", movieSource.path)
        assertEquals("00000000-0000-0000-0000-00000000bb04", episodeSource.id)
        assertEquals("local://00000000-0000-0000-0000-00000000bb04", episodeSource.path)
    }

    @Test
    fun visibleRouteLookupRejectsWrongProfileAndUnavailableRoots() = runBlocking {
        val ownerRoot = root()
        val unavailableRoot = root(enabled = true).copy(lastKnownAvailable = false)
        val ownerIndex = InMemoryLocalLibraryIndexRepository()
        ownerIndex.replaceRootScan(ownerRoot, listOf(episodeRecord(ownerRoot, ownerUserId = userId)))
        val unavailableIndex = InMemoryLocalLibraryIndexRepository()
        unavailableIndex.replaceRootScan(unavailableRoot, listOf(episodeRecord(unavailableRoot)))

        val ownerRepository =
            LocalLibraryMediaRepository(
                rootStore = FakeRootStore(ownerRoot),
                indexRepository = ownerIndex,
                userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
            )
        val unavailableRepository =
            LocalLibraryMediaRepository(
                rootStore = FakeRootStore(unavailableRoot),
                indexRepository = unavailableIndex,
                userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
            )
        val showId = ownerRepository.getVisibleShows(userId).single().id
        val seasonId = ownerRepository.getVisibleShows(userId).single().seasons.single().id

        assertEquals(
            null,
            ownerRepository.findVisibleItemById(
                itemId = showId,
                itemType = "Series",
                profileUserId = "other-user",
                visibilityContext =
                    LocalLibraryVisibilityContext(
                        currentUserId = "other-user",
                        kidModeEnabled = false,
                        parentUnlocked = false,
                    ),
            ),
        )
        assertEquals(
            null,
            ownerRepository.resolvePlayableItem(
                itemId = seasonId,
                itemType = "Season",
                seriesId = showId,
                profileUserId = "other-user",
                visibilityContext =
                    LocalLibraryVisibilityContext(
                        currentUserId = "other-user",
                        kidModeEnabled = false,
                        parentUnlocked = false,
                    ),
            ),
        )
        assertTrue(unavailableRepository.getVisibleShows(userId).isEmpty())
    }

    @Test
    fun sameShowTitleFromDifferentRootsDoesNotMergeIntoOneLocalRouteTarget() = runBlocking {
        val firstRoot = root()
        val secondRoot = root(registryId = secondRootId)
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(firstRoot, listOf(episodeRecord(firstRoot, localItemId = "bluey-root-1")))
        index.replaceRootScan(secondRoot, listOf(episodeRecord(secondRoot, localItemId = "bluey-root-2")))

        val shows =
            LocalLibraryMediaRepository(
                    rootStore = FakeRootStore(firstRoot, secondRoot),
                    indexRepository = index,
                    userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
                )
                .getVisibleShows(userId)

        assertEquals(2, shows.size)
        assertEquals(2, shows.map { it.id }.toSet().size)
    }

    @Test
    fun jellyfinParentIdentitySeparatesSameTitledShowsWithinOneRoot() = runBlocking {
        val root = root()
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(
            root,
            listOf(
                episodeRecord(
                    root = root,
                    localItemId = "bluey-series-1",
                    relativePath = "Shows/Bluey/Season 01/Bluey - S01E01 - One.mkv",
                    jellyfinSeriesId = firstSeriesId.toString(),
                    jellyfinSeasonId = firstSeasonId.toString(),
                ),
                episodeRecord(
                    root = root,
                    localItemId = "bluey-series-2",
                    relativePath = "Shows/Bluey/Season 01/Bluey - S01E02 - Two.mkv",
                    jellyfinSeriesId = secondSeriesId.toString(),
                    jellyfinSeasonId = secondSeasonId.toString(),
                ),
            ),
        )

        val shows =
            LocalLibraryMediaRepository(
                    rootStore = FakeRootStore(root),
                    indexRepository = index,
                    userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
                )
                .getVisibleShows(userId)

        assertEquals(setOf(firstSeriesId, secondSeriesId), shows.map { it.id }.toSet())
        assertEquals(setOf(firstSeasonId, secondSeasonId), shows.flatMap { it.seasons }.map { it.id }.toSet())
    }

    @Test
    fun jellyfinParentIdentityRouteTargetsOpenLocalShowAndSeasonContainers() = runBlocking {
        val root = root()
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(
            root,
            listOf(
                episodeRecord(
                    root = root,
                    localItemId = "bluey-series-1",
                    jellyfinSeriesId = firstSeriesId.toString(),
                    jellyfinSeasonId = firstSeasonId.toString(),
                )
            ),
        )
        val repository =
            LocalLibraryMediaRepository(
                rootStore = FakeRootStore(root),
                indexRepository = index,
                userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
            )

        val show =
            repository.findVisibleItemById(
                itemId = firstSeriesId,
                itemType = "Series",
                profileUserId = userId,
            )
        val season =
            repository.findVisibleItemById(
                itemId = firstSeasonId,
                itemType = "Season",
                seriesId = firstSeriesId,
                profileUserId = userId,
            )

        assertTrue(show is AfinityShow)
        assertEquals("Keepy Uppy", (show as AfinityShow).seasons.single().episodes.single().name)
        assertTrue(season is AfinitySeason)
        assertEquals("Keepy Uppy", (season as AfinitySeason).episodes.single().name)
    }

    @Test
    fun contentForLocalContainerOpensShowFoldersFromIndexWithoutRemoteLibraryPaging() = runBlocking {
        val root = root()
        val index = InMemoryLocalLibraryIndexRepository()
        index.replaceRootScan(root, listOf(movieRecord(root), episodeRecord(root)))
        val repository =
            LocalLibraryMediaRepository(
                rootStore = FakeRootStore(root),
                indexRepository = index,
                userStateRepository = FakeLocalMediaUserStateRepository(emptyMap()),
            )
        val show = repository.getVisibleShows(userId).single()

        val showFolderById =
            repository.getContentForContainer(
                containerId = show.id,
                profileUserId = userId,
            )
        val showFolderByName =
            repository.getContentForContainer(
                containerId = null,
                containerName = show.name,
                profileUserId = userId,
            )
        val rootContent =
            repository.getContentForContainer(
                containerId = UUID.fromString("00000000-0000-0000-0000-00000000ffff"),
                profileUserId = userId,
            )

        assertEquals(listOf("Keepy Uppy"), showFolderById.map { it.name })
        assertEquals(listOf("Keepy Uppy"), showFolderByName.map { it.name })
        assertEquals(setOf("Movie", "Bluey"), rootContent.map { it.name }.toSet())
    }

    private fun root(enabled: Boolean = true): LocalLibraryRootRecord =
        root(registryId = rootId, enabled = enabled)

    private fun root(
        registryId: UUID,
        enabled: Boolean = true,
    ): LocalLibraryRootRecord =
        LocalLibraryRootRecord(
            registryId = registryId,
            stableRootId = registryId,
            displayName = "Library",
            kind = LocalLibraryRootKind.APP_PRIVATE,
            uriOrPath = File("/tmp/library/$registryId").absolutePath,
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

    private fun episodeRecord(
        root: LocalLibraryRootRecord,
        localItemId: String = "local-episode",
        ownerUserId: String? = null,
        relativePath: String = "Shows/Bluey/Season 01/Bluey - S01E03 - Keepy Uppy.mkv",
        jellyfinSeriesId: String? = null,
        jellyfinSeasonId: String? = null,
    ): LocalMediaFileRecord =
        LocalMediaFileRecord(
            mediaFileId = UUID.fromString("00000000-0000-0000-0000-00000000bb04"),
            rootRegistryId = root.registryId,
            stableRootId = root.stableRootId,
            relativePath = relativePath,
            sidecarRelativePath = "Shows/Bluey/Season 01/Bluey - S01E03 - Keepy Uppy.afinity.json",
            ownerUserId = ownerUserId,
            mediaKind = LocalMediaKind.EPISODE,
            identity =
                LocalMediaIdentity(
                    localItemId = localItemId,
                    serverId = null,
                    jellyfinItemId = null,
                    jellyfinSourceId = null,
                    jellyfinSeriesId = jellyfinSeriesId,
                    jellyfinSeasonId = jellyfinSeasonId,
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

    private class FakeRootStore(private vararg val roots: LocalLibraryRootRecord) :
        LocalLibraryRootStore {
        override fun rootsFlow(): Flow<List<LocalLibraryRootRecord>> = flowOf(roots.toList())

        override suspend fun getRoots(): List<LocalLibraryRootRecord> = roots.toList()

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
