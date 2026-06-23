package com.makd.afinity.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class LocalPlaybackSourceResolverTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun resolverReturnsStorageUnavailableInsteadOfFallingBackToAnotherItem() {
        val rootDir = temporaryFolder.newFolder("library")
        val root = root(rootDir, available = false)
        val record = mediaRecord(root)
        val index = InMemoryLocalLibraryIndexRepository().also { it.replaceRootScan(root, listOf(record)) }

        val result =
            LocalPlaybackSourceResolver(
                    roots = { listOf(root) },
                    fileSystem = FilePathLibraryFileSystem(),
                    indexRepository = index,
                )
                .resolve(LocalPlaybackResolutionRequest(mediaFileId = record.mediaFileId))

        assertEquals(LocalPlaybackResolution.Unavailable("local_root_unavailable"), result)
    }

    @Test
    fun resolverReturnsPlayerUriAndAdjacentSubtitlesForReadableLocalFile() {
        val rootDir = temporaryFolder.newFolder("library")
        val root = root(rootDir)
        val media = File(rootDir, "Movies/Movie (2026)/Movie (2026).mkv")
        media.parentFile?.mkdirs()
        media.writeBytes(ByteArray(10))
        File(rootDir, "Movies/Movie (2026)/Movie (2026).en.srt").writeText("subtitle")
        val record = mediaRecord(root)
        val index = InMemoryLocalLibraryIndexRepository().also { it.replaceRootScan(root, listOf(record)) }

        val result =
            LocalPlaybackSourceResolver(
                    roots = { listOf(root) },
                    fileSystem = FilePathLibraryFileSystem(),
                    indexRepository = index,
                )
                .resolve(LocalPlaybackResolutionRequest(localItemId = "local-movie"))

        assertTrue(result is LocalPlaybackResolution.Resolved)
        result as LocalPlaybackResolution.Resolved
        assertTrue(result.playerUri.endsWith("Movie (2026).mkv"))
        assertTrue(result.playerUri.startsWith(rootDir.absolutePath))
        assertEquals(1, result.subtitles.size)
        assertTrue(result.subtitles.single().endsWith("Movie (2026).en.srt"))
    }

    @Test
    fun resolverRejectsHiddenLocalMediaEvenWhenRequestedByMediaFileId() {
        val rootDir = temporaryFolder.newFolder("library")
        val root = root(rootDir)
        val media = File(rootDir, "Movies/Movie (2026)/Movie (2026).mkv")
        media.parentFile?.mkdirs()
        media.writeBytes(ByteArray(10))
        val record = mediaRecord(root, visible = false)
        val index = InMemoryLocalLibraryIndexRepository().also { it.replaceRootScan(root, listOf(record)) }

        val result =
            LocalPlaybackSourceResolver(
                    roots = { listOf(root) },
                    fileSystem = FilePathLibraryFileSystem(),
                    indexRepository = index,
                )
                .resolve(LocalPlaybackResolutionRequest(mediaFileId = record.mediaFileId))

        assertEquals(LocalPlaybackResolution.Unavailable("local_media_not_visible"), result)
    }

    @Test
    fun resolverRejectsDifferentOwnerEvenWhenRequestedByMediaFileId() {
        val rootDir = temporaryFolder.newFolder("library")
        val root = root(rootDir)
        val media = File(rootDir, "Movies/Movie (2026)/Movie (2026).mkv")
        media.parentFile?.mkdirs()
        media.writeBytes(ByteArray(10))
        val record = mediaRecord(root, ownerUserId = "owner-user")
        val index = InMemoryLocalLibraryIndexRepository().also { it.replaceRootScan(root, listOf(record)) }

        val result =
            LocalPlaybackSourceResolver(
                    roots = { listOf(root) },
                    fileSystem = FilePathLibraryFileSystem(),
                    indexRepository = index,
                )
                .resolve(
                    LocalPlaybackResolutionRequest(
                        mediaFileId = record.mediaFileId,
                        visibilityContext =
                            LocalLibraryVisibilityContext(
                                currentUserId = "other-user",
                                kidModeEnabled = false,
                                parentUnlocked = false,
                            ),
                    )
                )

        assertEquals(LocalPlaybackResolution.Unavailable("local_media_not_visible"), result)
    }



    @Test
    fun resolverPlaysMediaAfterFreshIndexRebuildFromLocalFolder() {
        val rootDir = temporaryFolder.newFolder("library")
        val root = root(rootDir)
        File(rootDir, "Movies/Rebuilt (2026)/Rebuilt (2026).mkv").also { media ->
            media.parentFile?.mkdirs()
            media.writeBytes(ByteArray(10))
        }
        File(rootDir, "Movies/Rebuilt (2026)/Rebuilt (2026).afinity.json")
            .writeText(
                """
                {
                  "schemaVersion": 1,
                  "mediaKind": "movie",
                  "identity": { "providerIds": { "Imdb": "tt0000001" } },
                  "titles": { "name": "Rebuilt", "year": 2026 },
                  "mediaFile": {
                    "relativePath": "Movies/Rebuilt (2026)/Rebuilt (2026).mkv",
                    "container": "mkv",
                    "sizeBytes": 10
                  }
                }
                """
                    .trimIndent()
            )
        val rebuiltIndex = InMemoryLocalLibraryIndexRepository()
        LocalLibraryScanner(
                fileSystem = FilePathLibraryFileSystem(),
                sidecarReader = LocalLibrarySidecarReader(),
                indexRepository = rebuiltIndex,
                pathPolicy = LocalLibraryPathPolicy(),
            )
            .scanRoot(root)
        val rebuiltRecord = rebuiltIndex.visibleMediaFiles().single()

        val result =
            LocalPlaybackSourceResolver(
                    roots = { listOf(root) },
                    fileSystem = FilePathLibraryFileSystem(),
                    indexRepository = rebuiltIndex,
                )
                .resolve(LocalPlaybackResolutionRequest(mediaFileId = rebuiltRecord.mediaFileId))

        assertTrue(result is LocalPlaybackResolution.Resolved)
        result as LocalPlaybackResolution.Resolved
        assertEquals(rebuiltRecord.mediaFileId, result.mediaFile.mediaFileId)
        assertTrue(result.playerUri.endsWith("Rebuilt (2026).mkv"))
        assertTrue(result.playerUri.startsWith(rootDir.absolutePath))
    }

    private fun root(rootDir: File, available: Boolean = true): LocalLibraryRootRecord =
        LocalLibraryRootRecord(
            registryId = UUID.nameUUIDFromBytes(rootDir.absolutePath.toByteArray()),
            stableRootId = null,
            displayName = rootDir.name,
            kind = LocalLibraryRootKind.APP_PRIVATE,
            uriOrPath = rootDir.absolutePath,
            lastKnownAvailable = available,
        )

    private fun mediaRecord(
        root: LocalLibraryRootRecord,
        visible: Boolean = true,
        ownerUserId: String? = null,
    ): LocalMediaFileRecord =
        LocalMediaFileRecord(
            mediaFileId = UUID.fromString("00000000-0000-0000-0000-000000000111"),
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
                    stableRootId = null,
                    fingerprint = LocalMediaFingerprint("test", "fingerprint"),
                ),
            title = LocalLibraryTitle("Movie", year = 2026),
            sizeBytes = 10,
            modifiedAt = 1,
            container = "mkv",
            runtimeTicks = null,
            visibleByDefault = visible,
        )
}
