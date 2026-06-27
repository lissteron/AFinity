package com.makd.afinity.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class LocalLibraryScannerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val fileSystem = FilePathLibraryFileSystem()
    private val sidecarReader = LocalLibrarySidecarReader()
    private val pathPolicy = LocalLibraryPathPolicy()

    @Test
    fun scannerRebuildsMovieAndEpisodeFromFolderWithoutDownloadRowsOrMediaCache() {
        val rootDir = temporaryFolder.newFolder("library")
        val root = root(rootDir, stableRootId = UUID.fromString("00000000-0000-0000-0000-00000000aaa1"))
        writeMovie(rootDir)
        writeEpisode(rootDir)
        val index = InMemoryLocalLibraryIndexRepository()

        val summary = scanner(index).scanRoot(root)

        assertEquals(2, summary.importedItems)
        assertEquals(2, index.visibleMediaFiles().size)
        assertTrue(index.visibleMediaFiles().any { it.mediaKind == LocalMediaKind.MOVIE && it.title.name == "WALL-E" })
        val episode = index.visibleMediaFiles().single { it.mediaKind == LocalMediaKind.EPISODE }
        assertEquals("Bluey", episode.title.showName)
        assertEquals("series-1", episode.identity.jellyfinSeriesId)
        assertEquals("season-1", episode.identity.jellyfinSeasonId)
    }

    @Test
    fun scannerIndexesTwoRootsAndReportsDuplicateIdentity() {
        val firstRoot = temporaryFolder.newFolder("library-a")
        val secondRoot = temporaryFolder.newFolder("library-b")
        writeMovie(firstRoot)
        writeMovie(secondRoot)
        val index = InMemoryLocalLibraryIndexRepository()

        scanner(index).scanRoot(root(firstRoot, priority = 10))
        val preferredRoot = root(secondRoot, priority = 0)
        val summary = scanner(index).scanRoot(preferredRoot)

        assertEquals(2, index.allMediaFiles().size)
        assertEquals(1, index.visibleMediaFiles().size)
        assertEquals(1, summary.duplicateGroups)
        assertEquals(preferredRoot.registryId, index.visibleMediaFiles().single().rootRegistryId)
    }

    @Test
    fun scannerIgnoresPartialAndDownloadStagingFiles() {
        val rootDir = temporaryFolder.newFolder("library")
        writeBytes(File(rootDir, "Movies/Partial (2026)/Partial (2026).mkv.part"), 20)
        writeBytes(File(rootDir, "Movies/Partial (2026)/Other (2026).mp4.download"), 20)
        val index = InMemoryLocalLibraryIndexRepository()

        val summary = scanner(index).scanRoot(root(rootDir))

        assertEquals(0, summary.discoveredFiles)
        assertTrue(index.visibleMediaFiles().isEmpty())
        assertEquals(2, index.importJobs().size)
        assertTrue(index.importJobs().all { it.state == LocalMediaImportState.IMPORT_PENDING })
    }

    @Test
    fun filenameFallbackImportsEpisodeWithoutSidecarAsLocalOnly() {
        val rootDir = temporaryFolder.newFolder("library")
        writeBytes(File(rootDir, "Shows/Bluey/Season 01/Bluey - S01E02 - Hospital.mkv"), 20)
        val index = InMemoryLocalLibraryIndexRepository()

        val summary = scanner(index).scanRoot(root(rootDir))

        assertEquals(1, summary.importedItems)
        val episode = index.visibleMediaFiles().single()
        assertEquals(LocalMediaKind.EPISODE, episode.mediaKind)
        assertEquals("Bluey", episode.title.showName)
        assertEquals(2, episode.title.episodeNumber)
        assertTrue(episode.identity.jellyfinItemId == null)
    }

    @Test
    fun scannerImportsMovieTitleFromNfoWithoutAfinitySidecar() {
        val rootDir = temporaryFolder.newFolder("library")
        writeBytes(File(rootDir, "Movies/Movie Folder/Movie File.mkv"), 20)
        writeText(
            File(rootDir, "Movies/Movie Folder/movie.nfo"),
            """
            <movie>
              <title>NFO Movie</title>
              <year>2025</year>
            </movie>
            """
                .trimIndent(),
        )
        val index = InMemoryLocalLibraryIndexRepository()

        val summary = scanner(index).scanRoot(root(rootDir))

        assertEquals(1, summary.importedItems)
        val movie = index.visibleMediaFiles().single()
        assertEquals(LocalMediaKind.MOVIE, movie.mediaKind)
        assertEquals("NFO Movie", movie.title.name)
        assertEquals(2025, movie.title.year)
    }

    @Test
    fun scannerIndexesPortableArtworkFilesNextToLocalMedia() {
        val rootDir = temporaryFolder.newFolder("library")
        writeMovie(rootDir)
        writeEpisode(rootDir)
        writeBytes(File(rootDir, "Movies/WALL-E (2008)/images/primary.jpg"), 20)
        writeBytes(File(rootDir, "Movies/WALL-E (2008)/images/backdrop.webp"), 20)
        writeBytes(File(rootDir, "Shows/Bluey/images/primary.png"), 20)
        writeBytes(File(rootDir, "Shows/Bluey/Season 01/images/primary.jpg"), 20)
        writeBytes(
            File(
                rootDir,
                "Shows/Bluey/Season 01/images/Bluey - S01E01 - The Magic Xylophone/primary.jpg",
            ),
            20,
        )
        val index = InMemoryLocalLibraryIndexRepository()

        scanner(index).scanRoot(root(rootDir))

        val movie = index.visibleMediaFiles().single { it.mediaKind == LocalMediaKind.MOVIE }
        val episode = index.visibleMediaFiles().single { it.mediaKind == LocalMediaKind.EPISODE }
        assertTrue(movie.artwork.primaryUri!!.contains("/Movies/WALL-E%20(2008)/images/primary.jpg"))
        assertTrue(movie.artwork.backdropUri!!.contains("/Movies/WALL-E%20(2008)/images/backdrop.webp"))
        assertTrue(
            episode.artwork.primaryUri!!.contains(
                "/Shows/Bluey/Season%2001/images/Bluey%20-%20S01E01%20-%20The%20Magic%20Xylophone/primary.jpg"
            )
        )
        assertTrue(episode.artwork.showPrimaryUri!!.contains("/Shows/Bluey/images/primary.png"))
    }

    @Test
    fun scannerKeepsSeasonArtworkSeparateWhenEpisodeScopedArtworkIsMissing() {
        val rootDir = temporaryFolder.newFolder("library-season-separate")
        writeEpisode(rootDir)
        writeBytes(File(rootDir, "Shows/Bluey/Season 01/images/primary.jpg"), 20)
        val index = InMemoryLocalLibraryIndexRepository()

        scanner(index).scanRoot(root(rootDir))

        val episode = index.visibleMediaFiles().single { it.mediaKind == LocalMediaKind.EPISODE }
        assertEquals(null, episode.artwork.primaryUri)
        assertTrue(
            episode.artwork.seasonPrimaryUri!!.contains("/Shows/Bluey/Season%2001/images/primary.jpg")
        )
    }

    @Test
    fun scannerDoesNotPersistProfileVisibilityFromScanContext() {
        val rootDir = temporaryFolder.newFolder("library")
        writeOwnedMovie(rootDir, ownerUserId = "user-1")
        val index = InMemoryLocalLibraryIndexRepository()

        scanner(index)
            .scanRoot(
                root(rootDir),
                visibilityContext =
                    LocalLibraryVisibilityContext(
                        currentUserId = null,
                        kidModeEnabled = false,
                        parentUnlocked = false,
                    ),
            )

        assertTrue(index.allMediaFiles().single().visibleByDefault)
        assertEquals(
            1,
            index
                .visibleMediaFiles(
                    LocalLibraryVisibilityContext(
                        currentUserId = "user-1",
                        kidModeEnabled = false,
                        parentUnlocked = false,
                    )
                )
                .size,
        )
        assertEquals(
            0,
            index
                .visibleMediaFiles(
                    LocalLibraryVisibilityContext(
                        currentUserId = "other-user",
                        kidModeEnabled = false,
                        parentUnlocked = false,
                    )
                )
                .size,
        )
    }

    @Test
    fun scannerKeepsUnparsedMediaAsRepairableImportPendingJob() {
        val rootDir = temporaryFolder.newFolder("library")
        val mediaFile = File(rootDir, "Loose/Unknown.mkv")
        writeBytes(mediaFile, 20)
        val index = InMemoryLocalLibraryIndexRepository()

        val summary = scanner(index).scanRoot(root(rootDir))

        assertEquals(0, summary.importedItems)
        assertEquals(1, summary.parseWarnings)
        assertTrue(mediaFile.exists())
        val job = index.importJobs().single()
        assertEquals(LocalMediaImportState.IMPORT_PENDING, job.state)
        assertEquals("Loose/Unknown.mkv", job.relativePath)
    }

    @Test
    fun unavailableRootHidesVisibleRowsWithoutDeletingIndexAndRestoresOnRescan() {
        val rootDir = temporaryFolder.newFolder("library")
        val availableRoot = root(rootDir)
        writeMovie(rootDir)
        val index = InMemoryLocalLibraryIndexRepository()
        scanner(index).scanRoot(availableRoot)
        assertEquals(1, index.visibleMediaFiles().size)

        scanner(index).scanRoot(availableRoot.copy(lastKnownAvailable = false))

        assertEquals(1, index.allMediaFiles().size)
        assertEquals(0, index.visibleMediaFiles().size)

        scanner(index).scanRoot(availableRoot)
        assertEquals(1, index.visibleMediaFiles().size)
    }

    @Test
    fun inaccessibleFileRootIsMarkedUnavailableWithoutReplacingExistingRows() {
        val rootDir = temporaryFolder.newFolder("library")
        val availableRoot = root(rootDir)
        writeMovie(rootDir)
        val index = InMemoryLocalLibraryIndexRepository()
        scanner(index).scanRoot(availableRoot)

        val missingRoot = availableRoot.copy(uriOrPath = rootDir.resolve("missing").absolutePath)
        val summary = scanner(index).scanRoot(missingRoot)

        assertEquals(1, summary.unavailableItems)
        assertEquals(1, summary.errors.size)
        assertEquals(1, index.allMediaFiles().size)
        assertEquals(0, index.visibleMediaFiles().size)
    }

    @Test
    fun cancelledScanDoesNotReplaceExistingIndexWithPartialRows() {
        val rootDir = temporaryFolder.newFolder("library")
        val libraryRoot = root(rootDir)
        writeMovie(rootDir)
        val index = InMemoryLocalLibraryIndexRepository()
        scanner(index).scanRoot(libraryRoot)
        assertEquals(1, index.visibleMediaFiles().size)

        writeBytes(File(rootDir, "Movies/New Movie (2026)/New Movie (2026).mkv"), 20)
        val summary = scanner(index).scanRoot(libraryRoot, shouldCancel = { true })

        assertTrue(summary.cancelled)
        assertEquals(1, index.visibleMediaFiles().size)
        assertTrue(index.visibleMediaFiles().none { it.title.name == "New Movie" })
    }

    private fun scanner(index: LocalLibraryIndexRepository) =
        LocalLibraryScanner(
            fileSystem = fileSystem,
            sidecarReader = sidecarReader,
            indexRepository = index,
            pathPolicy = pathPolicy,
        )

    private fun root(
        rootDir: File,
        stableRootId: UUID? = null,
        priority: Int = 0,
    ): LocalLibraryRootRecord =
        LocalLibraryRootRecord(
            registryId = UUID.nameUUIDFromBytes(rootDir.absolutePath.toByteArray()),
            stableRootId = stableRootId,
            displayName = rootDir.name,
            kind = LocalLibraryRootKind.APP_PRIVATE,
            uriOrPath = rootDir.absolutePath,
            priority = priority,
        )

    private fun writeMovie(rootDir: File) {
        writeBytes(File(rootDir, "Movies/WALL-E (2008)/WALL-E (2008).mkv"), 100)
        writeText(
            File(rootDir, "Movies/WALL-E (2008)/WALL-E (2008).afinity.json"),
            """
            {
              "schemaVersion": 1,
              "mediaKind": "movie",
              "server": { "serverId": "server" },
              "identity": {
                "itemId": "movie-1",
                "sourceId": "source-1",
                "providerIds": { "Imdb": "tt0910970" }
              },
              "localIdentity": {
                "localItemId": "local-movie",
                "stableRootId": "00000000-0000-0000-0000-00000000aaa1",
                "relativePathAtWrite": "Movies/WALL-E (2008)/WALL-E (2008).mkv"
              },
              "titles": { "name": "WALL-E", "year": 2008 },
              "mediaFile": {
                "relativePath": "Movies/WALL-E (2008)/WALL-E (2008).mkv",
                "container": "mkv",
                "sizeBytes": 100
              }
            }
            """
                .trimIndent(),
        )
    }

    private fun writeOwnedMovie(rootDir: File, ownerUserId: String) {
        writeBytes(File(rootDir, "Movies/Owned (2026)/Owned (2026).mkv"), 100)
        writeText(
            File(rootDir, "Movies/Owned (2026)/Owned (2026).afinity.json"),
            """
            {
              "schemaVersion": 1,
              "mediaKind": "movie",
              "server": { "serverId": "server" },
              "user": { "userId": "$ownerUserId" },
              "identity": {
                "itemId": "owned-movie",
                "sourceId": "source-1"
              },
              "localIdentity": {
                "localItemId": "owned-local-movie",
                "relativePathAtWrite": "Movies/Owned (2026)/Owned (2026).mkv"
              },
              "titles": { "name": "Owned", "year": 2026 },
              "mediaFile": {
                "relativePath": "Movies/Owned (2026)/Owned (2026).mkv",
                "container": "mkv",
                "sizeBytes": 100
              }
            }
            """
                .trimIndent(),
        )
    }

    private fun writeEpisode(rootDir: File) {
        writeBytes(
            File(rootDir, "Shows/Bluey/Season 01/Bluey - S01E01 - The Magic Xylophone.mkv"),
            100,
        )
        writeText(
            File(rootDir, "Shows/Bluey/Season 01/Bluey - S01E01 - The Magic Xylophone.afinity.json"),
            """
            {
              "schemaVersion": 1,
              "mediaKind": "episode",
              "server": { "serverId": "server" },
              "identity": {
                "itemId": "episode-1",
                "sourceId": "source-1",
                "seriesId": "series-1",
                "seasonId": "season-1"
              },
              "localIdentity": {
                "localItemId": "local-episode",
                "relativePathAtWrite": "Shows/Bluey/Season 01/Bluey - S01E01 - The Magic Xylophone.mkv"
              },
              "titles": {
                "name": "The Magic Xylophone",
                "showName": "Bluey",
                "seasonNumber": 1,
                "episodeNumber": 1
              },
              "mediaFile": {
                "relativePath": "Shows/Bluey/Season 01/Bluey - S01E01 - The Magic Xylophone.mkv",
                "container": "mkv",
                "sizeBytes": 100
              }
            }
            """
                .trimIndent(),
        )
    }

    private fun writeBytes(file: File, size: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size))
    }

    private fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}
