package com.makd.afinity.data.local

import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.models.download.DownloadStatus
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryArtworkBackfillServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun backfillCopiesLegacyShowAndSeasonArtworkIntoPortableSafRoot() = runBlocking {
        val legacyRootDir = temporaryFolder.newFolder("legacy-root")
        val serverId = "server-1"
        val itemId = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000202")
        val safRoot =
            root(
                id = UUID.fromString("00000000-0000-0000-0000-00000000aa02"),
                kind = LocalLibraryRootKind.SAF_TREE,
                uriOrPath = "content://provider/tree/Movies",
            )
        val legacyRoot =
            root(
                id = UUID.fromString("00000000-0000-0000-0000-00000000aa01"),
                kind = LocalLibraryRootKind.DEVICE_SHARED,
                uriOrPath = legacyRootDir.absolutePath,
            )
        writeBytes(
            File(legacyRootDir, "$serverId/shows/$seriesId/images/primary.png"),
            byteArrayOf(1, 2, 3),
        )
        writeBytes(
            File(legacyRootDir, "$serverId/shows/$seriesId/seasons/0/images/primary.jpg"),
            byteArrayOf(4, 5, 6),
        )
        val index =
            InMemoryLocalLibraryIndexRepository().also {
                it.replaceRootScan(
                    safRoot,
                    listOf(
                        localEpisode(
                            root = safRoot,
                            itemId = itemId,
                            serverId = serverId,
                            seriesId = seriesId,
                        )
                    ),
                )
            }
        val fileSystem = WritableMemoryFileSystem()
        val service =
            LocalLibraryArtworkBackfillService(
                rootStore = StaticRootStore(listOf(legacyRoot, safRoot)),
                indexRepository = index,
                fileSystem = fileSystem,
                sourceRootProvider = StaticArtworkSourceRootProvider(emptyList()),
            )

        val summary =
            service.backfillDownloads(
                listOf(
                    download(
                        itemId = itemId,
                        serverId = serverId,
                        seriesId = seriesId,
                    )
                )
            )

        assertEquals(2, summary.writtenFiles)
        assertEquals(null, fileSystem.bytes("Shows/Kote/Season 00/images/Kote - S00E00 - Test/primary.jpg"))
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            fileSystem.bytes("Shows/Kote/images/primary.png"),
        )
        assertArrayEquals(
            byteArrayOf(4, 5, 6),
            fileSystem.bytes("Shows/Kote/Season 00/images/primary.jpg"),
        )
    }

    @Test
    fun backfillDoesNotOverwritePortableArtworkAlreadyNextToMedia() = runBlocking {
        val legacyRootDir = temporaryFolder.newFolder("legacy-root-existing")
        val serverId = "server-1"
        val itemId = UUID.fromString("00000000-0000-0000-0000-000000000111")
        val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000222")
        val safRoot =
            root(
                id = UUID.fromString("00000000-0000-0000-0000-00000000bb02"),
                kind = LocalLibraryRootKind.SAF_TREE,
                uriOrPath = "content://provider/tree/Movies",
            )
        val legacyRoot =
            root(
                id = UUID.fromString("00000000-0000-0000-0000-00000000bb01"),
                kind = LocalLibraryRootKind.DEVICE_SHARED,
                uriOrPath = legacyRootDir.absolutePath,
            )
        writeBytes(
            File(legacyRootDir, "$serverId/shows/$seriesId/seasons/0/images/primary.jpg"),
            byteArrayOf(9, 9, 9),
        )
        val index =
            InMemoryLocalLibraryIndexRepository().also {
                it.replaceRootScan(
                    safRoot,
                    listOf(
                        localEpisode(
                            root = safRoot,
                            itemId = itemId,
                            serverId = serverId,
                            seriesId = seriesId,
                        )
                    ),
                )
            }
        val fileSystem =
            WritableMemoryFileSystem().also {
                it.writeBytes(
                    safRoot,
                    "Shows/Kote/Season 00/images/primary.png",
                    byteArrayOf(7, 7, 7),
                )
                it.writeBytes(
                    safRoot,
                    "Shows/Kote/Season 00/images/Kote - S00E00 - Test/primary.png",
                    byteArrayOf(6, 6, 6),
                )
            }
        val service =
            LocalLibraryArtworkBackfillService(
                rootStore = StaticRootStore(listOf(legacyRoot, safRoot)),
                indexRepository = index,
                fileSystem = fileSystem,
                sourceRootProvider = StaticArtworkSourceRootProvider(emptyList()),
            )

        val summary =
            service.backfillDownloads(
                listOf(download(itemId = itemId, serverId = serverId, seriesId = seriesId))
            )

        assertEquals(0, summary.writtenFiles)
        assertArrayEquals(
            byteArrayOf(6, 6, 6),
            fileSystem.bytes("Shows/Kote/Season 00/images/Kote - S00E00 - Test/primary.png"),
        )
        assertArrayEquals(
            byteArrayOf(7, 7, 7),
            fileSystem.bytes("Shows/Kote/Season 00/images/primary.png"),
        )
        assertEquals(null, fileSystem.bytes("Shows/Kote/Season 00/images/primary.jpg"))
        assertEquals(null, fileSystem.bytes("Shows/Kote/images/primary.jpg"))
    }

    @Test
    fun backfillContinuesWhenFirstEpisodeItemDirectoryHasNoPrimaryImage() = runBlocking {
        val legacyRootDir = temporaryFolder.newFolder("legacy-root-second-image")
        val serverId = "server-1"
        val firstItemId = UUID.fromString("00000000-0000-0000-0000-000000000121")
        val secondItemId = UUID.fromString("00000000-0000-0000-0000-000000000122")
        val seriesId = UUID.fromString("00000000-0000-0000-0000-000000000223")
        val safRoot =
            root(
                id = UUID.fromString("00000000-0000-0000-0000-00000000cc02"),
                kind = LocalLibraryRootKind.SAF_TREE,
                uriOrPath = "content://provider/tree/Movies",
            )
        val firstFolder = "$serverId/shows/$seriesId/seasons/0/$firstItemId"
        val secondFolder = "$serverId/shows/$seriesId/seasons/0/$secondItemId"
        writeBytes(File(legacyRootDir, "$firstFolder/images/extra.jpg"), byteArrayOf(1))
        writeBytes(File(legacyRootDir, "$secondFolder/images/primary.jpg"), byteArrayOf(8, 8, 8))
        val index =
            InMemoryLocalLibraryIndexRepository().also {
                it.replaceRootScan(
                    safRoot,
                    listOf(
                        localEpisode(
                            root = safRoot,
                            itemId = firstItemId,
                            serverId = serverId,
                            seriesId = seriesId,
                        ),
                        localEpisode(
                            root = safRoot,
                            itemId = secondItemId,
                            serverId = serverId,
                            seriesId = seriesId,
                            episodeNumber = 1,
                        ),
                    ),
                )
            }
        val fileSystem = WritableMemoryFileSystem()
        val service =
            LocalLibraryArtworkBackfillService(
                rootStore = StaticRootStore(listOf(safRoot)),
                indexRepository = index,
                fileSystem = fileSystem,
                sourceRootProvider = StaticArtworkSourceRootProvider(listOf(legacyRootDir)),
            )

        val summary =
            service.backfillDownloads(
                listOf(
                    download(
                        itemId = firstItemId,
                        serverId = serverId,
                        seriesId = seriesId,
                        folderPath = firstFolder,
                    ),
                    download(
                        itemId = secondItemId,
                        serverId = serverId,
                        seriesId = seriesId,
                        folderPath = secondFolder,
                    ),
                )
            )

        assertEquals(1, summary.writtenFiles)
        assertArrayEquals(
            byteArrayOf(8, 8, 8),
            fileSystem.bytes("Shows/Kote/Season 00/images/Kote - S00E01 - Test/primary.jpg"),
        )
        assertEquals(null, fileSystem.bytes("Shows/Kote/Season 00/images/primary.jpg"))
        assertEquals(null, fileSystem.bytes("Shows/Kote/images/primary.jpg"))
    }

    private fun root(
        id: UUID,
        kind: LocalLibraryRootKind,
        uriOrPath: String,
    ): LocalLibraryRootRecord =
        LocalLibraryRootRecord(
            registryId = id,
            stableRootId = id,
            displayName = id.toString(),
            kind = kind,
            uriOrPath = uriOrPath,
            writable = true,
            lastKnownAvailable = true,
        )

    private fun localEpisode(
        root: LocalLibraryRootRecord,
        itemId: UUID,
        serverId: String,
        seriesId: UUID,
        episodeNumber: Int = 0,
    ): LocalMediaFileRecord =
        LocalMediaFileRecord(
            mediaFileId = UUID.nameUUIDFromBytes("media:$itemId".toByteArray()),
            rootRegistryId = root.registryId,
            stableRootId = root.stableRootId,
            relativePath = "Shows/Kote/Season 00/Kote - S00E${episodeNumber.twoDigits()} - Test.mp4",
            sidecarRelativePath =
                "Shows/Kote/Season 00/Kote - S00E${episodeNumber.twoDigits()} - Test.afinity.json",
            mediaKind = LocalMediaKind.EPISODE,
            identity =
                LocalMediaIdentity(
                    localItemId = itemId.toString(),
                    serverId = serverId,
                    jellyfinItemId = itemId.toString(),
                    jellyfinSourceId = "source-1",
                    jellyfinSeriesId = seriesId.toString(),
                    stableRootId = root.stableRootId,
                    fingerprint = LocalMediaFingerprint("test", "test"),
                ),
            title =
                LocalLibraryTitle(
                    name = "Test",
                    showName = "Kote",
                    seasonNumber = 0,
                    episodeNumber = episodeNumber,
                ),
            sizeBytes = 100,
            modifiedAt = 1,
            container = "mp4",
            runtimeTicks = null,
        )

    private fun download(
        itemId: UUID,
        serverId: String,
        seriesId: UUID,
        folderPath: String = "$serverId/shows/$seriesId/seasons/0/$itemId",
    ): DownloadDto =
        DownloadDto(
            id = UUID.fromString("00000000-0000-0000-0000-000000000404"),
            itemId = itemId,
            itemName = "Test",
            itemType = "Episode",
            sourceId = "source-1",
            sourceName = "source",
            status = DownloadStatus.COMPLETED,
            progress = 1f,
            bytesDownloaded = 100,
            totalBytes = 100,
            filePath = "content://provider/tree/Movies/document/Shows%2FKote%2FSeason%2000%2FTest.mp4",
            error = null,
            createdAt = 1,
            updatedAt = 1,
            serverId = serverId,
            userId = UUID.fromString("00000000-0000-0000-0000-000000000505"),
            imageUrl = null,
            seriesImageUrl = null,
            seriesName = "Kote",
            seasonNumber = 0,
            episodeNumber = 0,
            releaseYear = null,
            runtimeTicks = null,
            folderPath = folderPath,
            seriesId = seriesId.toString(),
        )

    private fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private class StaticRootStore(private val roots: List<LocalLibraryRootRecord>) :
        LocalLibraryRootStore {
        override fun rootsFlow(): Flow<List<LocalLibraryRootRecord>> = flowOf(roots)

        override suspend fun getRoots(): List<LocalLibraryRootRecord> = roots

        override suspend fun replaceRoots(roots: List<LocalLibraryRootRecord>) = Unit

        override suspend fun upsertRoot(root: LocalLibraryRootRecord) = Unit

        override suspend fun removeRoot(registryId: UUID) = Unit

        override suspend fun setDefaultDownloadRoot(registryId: UUID) = Unit
    }

    private class StaticArtworkSourceRootProvider(private val roots: List<File>) :
        LocalLibraryArtworkSourceRootProvider {
        override suspend fun sourceRoots(): List<File> = roots
    }

    private class WritableMemoryFileSystem : LocalLibraryFileSystem {
        private val files = linkedMapOf<String, ByteArray>()

        fun bytes(relativePath: String): ByteArray? = files[relativePath]

        override fun list(
            root: LocalLibraryRootRecord,
            relativePath: String,
        ): List<LocalLibraryNode> {
            val prefix = relativePath.takeIf { it.isNotBlank() }?.let { "$it/" }.orEmpty()
            return files.keys
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .filter { it.isNotBlank() }
                .map { it.substringBefore('/') }
                .distinct()
                .map { child ->
                    val childRelativePath = prefix + child
                    val isDirectory = files.keys.any { it.startsWith("$childRelativePath/") }
                    LocalLibraryNode(
                        relativePath = childRelativePath,
                        name = child,
                        isDirectory = isDirectory,
                        sizeBytes = if (isDirectory) 0 else files.getValue(childRelativePath).size.toLong(),
                        modifiedAt = 1L,
                    )
                }
        }

        override fun readText(
            root: LocalLibraryRootRecord,
            relativePath: String,
        ): String? = files[relativePath]?.toString(Charsets.UTF_8)

        override fun writeText(
            root: LocalLibraryRootRecord,
            relativePath: String,
            text: String,
            mimeType: String,
        ): Boolean {
            files[relativePath] = text.toByteArray()
            return true
        }

        override fun writeBytes(
            root: LocalLibraryRootRecord,
            relativePath: String,
            bytes: ByteArray,
            mimeType: String,
        ): Boolean {
            files[relativePath] = bytes
            return true
        }

        override fun exists(
            root: LocalLibraryRootRecord,
            relativePath: String,
        ): Boolean = files.containsKey(relativePath)

        override fun isReadable(
            root: LocalLibraryRootRecord,
            relativePath: String,
        ): Boolean = files.containsKey(relativePath)

        override fun playerUri(
            root: LocalLibraryRootRecord,
            relativePath: String,
        ): String = "${root.uriOrPath}/$relativePath"

        override fun assetUri(
            root: LocalLibraryRootRecord,
            relativePath: String,
        ): String? = playerUri(root, relativePath).takeIf { files.containsKey(relativePath) }

        override fun delete(
            root: LocalLibraryRootRecord,
            relativePath: String,
        ): Boolean {
            files.remove(relativePath)
            return true
        }

        override fun createMediaWriteTarget(
            root: LocalLibraryRootRecord,
            relativeMediaPath: String,
        ): LocalLibraryMediaWriteTarget = error("Not needed")
    }

    private fun Int.twoDigits(): String = toString().padStart(2, '0')
}
