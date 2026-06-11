package com.makd.afinity.data.local

import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.models.download.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class LocalLibraryMigrationServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun migratesLegacyMovieDownloadIntoCanonicalLayoutAndScannerIndexesIt() {
        val rootDir = temporaryFolder.newFolder("library")
        val root =
            LocalLibraryRootRecord(
                registryId = UUID.fromString("00000000-0000-0000-0000-00000000cc01"),
                stableRootId = UUID.fromString("00000000-0000-0000-0000-00000000cc02"),
                displayName = "Library",
                kind = LocalLibraryRootKind.APP_PRIVATE,
                uriOrPath = rootDir.absolutePath,
            )
        val itemId = UUID.fromString("00000000-0000-0000-0000-00000000cc03")
        val sourceId = "source-1"
        val legacyMedia = File(rootDir, "server/movies/$itemId/media/$sourceId.mkv")
        legacyMedia.parentFile?.mkdirs()
        legacyMedia.writeBytes(ByteArray(32))
        val download =
            download(
                itemId = itemId,
                sourceId = sourceId,
                itemName = "Legacy Movie",
                itemType = "Movie",
                folderPath = "server/movies/$itemId",
                filePath = legacyMedia.absolutePath,
                releaseYear = "2026",
            )

        val summary =
            LocalLibraryMigrationService(LocalLibraryPathPolicy(), LocalLibrarySidecarReader())
                .migrateLegacyDownloads(root, listOf(download))

        assertEquals(1, summary.migrated)
        val canonicalMedia = File(rootDir, "Movies/Legacy Movie (2026)/Legacy Movie (2026).mkv")
        assertTrue(canonicalMedia.exists())
        assertTrue(File(rootDir, "Movies/Legacy Movie (2026)/Legacy Movie (2026).afinity.json").exists())
        assertTrue(legacyMedia.exists())

        val index = InMemoryLocalLibraryIndexRepository()
        LocalLibraryScanner(
                fileSystem = FilePathLibraryFileSystem(),
                sidecarReader = LocalLibrarySidecarReader(),
                indexRepository = index,
                pathPolicy = LocalLibraryPathPolicy(),
            )
            .scanRoot(
                root,
                LocalLibraryVisibilityContext(
                    currentUserId = download.userId.toString(),
                    kidModeEnabled = false,
                    parentUnlocked = false,
                ),
            )

        val movie = index.visibleMediaFiles().single()
        assertEquals(LocalMediaKind.MOVIE, movie.mediaKind)
        assertEquals(itemId.toString(), movie.identity.localItemId)
        assertEquals("Legacy Movie", movie.title.name)
    }

    private fun download(
        itemId: UUID,
        sourceId: String,
        itemName: String,
        itemType: String,
        folderPath: String,
        filePath: String,
        releaseYear: String? = null,
    ): DownloadDto =
        DownloadDto(
            id = UUID.fromString("00000000-0000-0000-0000-00000000cc04"),
            itemId = itemId,
            itemName = itemName,
            itemType = itemType,
            sourceId = sourceId,
            sourceName = "Original",
            status = DownloadStatus.COMPLETED,
            progress = 1f,
            bytesDownloaded = 32,
            totalBytes = 32,
            filePath = filePath,
            error = null,
            createdAt = 1L,
            updatedAt = 2L,
            serverId = "server",
            userId = UUID.fromString("00000000-0000-0000-0000-00000000cc05"),
            imageUrl = null,
            seriesImageUrl = null,
            seriesName = null,
            seasonNumber = null,
            episodeNumber = null,
            releaseYear = releaseYear,
            runtimeTicks = 100L,
            folderPath = folderPath,
            seriesId = null,
        )
}
