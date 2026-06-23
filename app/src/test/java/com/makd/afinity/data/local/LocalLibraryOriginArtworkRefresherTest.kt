package com.makd.afinity.data.local

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryOriginArtworkRefresherTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val rootId = UUID.fromString("00000000-0000-0000-0000-00000000da01")
    private val userId = UUID.fromString("00000000-0000-0000-0000-00000000da02")
    private val itemId = UUID.fromString("00000000-0000-0000-0000-00000000da03")
    private val seriesId = UUID.fromString("00000000-0000-0000-0000-00000000da04")
    private val seasonId = UUID.fromString("00000000-0000-0000-0000-00000000da05")
    private val serverId = "server-1"
    private val sidecarReader = LocalLibrarySidecarReader()

    @Test
    fun refreshUsesLocalSidecarOriginWhenDownloadRowsAreGone() = runBlocking {
        val rootDir = temporaryFolder.newFolder("local-root")
        val root = root(rootDir)
        val sidecarPath = "Shows/Kote/Season 00/Kote - S00E00 - Test.afinity.json"
        writeSidecar(rootDir, sidecarPath, seriesId = null, seasonId = null)
        val index =
            InMemoryLocalLibraryIndexRepository().also {
                it.replaceRootScan(root, listOf(localEpisode(root, sidecarPath)))
            }
        val resolver =
            FakeRemoteArtworkResolver(
                LocalLibraryResolvedArtwork(
                    itemImages =
                        LocalLibraryResolvedImageSet(
                            thumb = LocalLibraryResolvedImage(byteArrayOf(7, 8, 9), "image/jpeg", "jpg")
                        ),
                    seasonImages =
                        LocalLibraryResolvedImageSet(
                            primary = LocalLibraryResolvedImage(byteArrayOf(1, 2, 3), "image/jpeg", "jpg")
                        ),
                    showImages =
                        LocalLibraryResolvedImageSet(
                            primary = LocalLibraryResolvedImage(byteArrayOf(4, 5, 6), "image/png", "png")
                        ),
                    seriesId = seriesId.toString(),
                    seasonId = seasonId.toString(),
                )
            )
        val service = service(root, index, resolver)

        val summary = service.refreshMissingArtwork()

        assertEquals(1, summary.refreshedItems)
        assertEquals(3, summary.writtenFiles)
        assertEquals(1, summary.updatedSidecars)
        assertEquals(1, resolver.seenOrigins.size)
        assertEquals(itemId, resolver.seenOrigins.single().itemId)
        assertArrayEquals(
            byteArrayOf(7, 8, 9),
            File(rootDir, "Shows/Kote/Season 00/images/Kote - S00E00 - Test/thumb.jpg").readBytes(),
        )
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            File(rootDir, "Shows/Kote/Season 00/images/primary.jpg").readBytes(),
        )
        assertArrayEquals(
            byteArrayOf(4, 5, 6),
            File(rootDir, "Shows/Kote/images/primary.png").readBytes(),
        )
        val updatedSidecar =
            sidecarReader
                .readMediaSidecar(File(rootDir, sidecarPath).readText())
                .sidecar
                ?: error("sidecar missing")
        assertEquals(seriesId.toString(), updatedSidecar.identity?.seriesId)
        assertEquals(seasonId.toString(), updatedSidecar.identity?.seasonId)
    }

    @Test
    fun refreshDoesNotOverwriteArtworkAlreadyStoredNextToLocalMedia() = runBlocking {
        val rootDir = temporaryFolder.newFolder("existing-art-root")
        val root = root(rootDir)
        val sidecarPath = "Shows/Kote/Season 00/Kote - S00E00 - Test.afinity.json"
        writeSidecar(rootDir, sidecarPath, seriesId = seriesId, seasonId = seasonId)
        writeBytes(
            File(rootDir, "Shows/Kote/Season 00/images/Kote - S00E00 - Test/primary.jpg"),
            byteArrayOf(8, 8, 8),
        )
        writeBytes(File(rootDir, "Shows/Kote/images/primary.jpg"), byteArrayOf(9, 9, 9))
        val index =
            InMemoryLocalLibraryIndexRepository().also {
                it.replaceRootScan(root, listOf(localEpisode(root, sidecarPath)))
            }
        val service =
            service(
                root,
                index,
                FakeRemoteArtworkResolver(
                    LocalLibraryResolvedArtwork(
                        showImages =
                            LocalLibraryResolvedImageSet(
                                primary = LocalLibraryResolvedImage(byteArrayOf(4, 5, 6), "image/png", "png")
                            )
                    )
                ),
            )

        val summary = service.refreshMissingArtwork()

        assertEquals(0, summary.writtenFiles)
        assertEquals(0, summary.updatedSidecars)
        assertArrayEquals(
            byteArrayOf(8, 8, 8),
            File(rootDir, "Shows/Kote/Season 00/images/Kote - S00E00 - Test/primary.jpg").readBytes(),
        )
        assertArrayEquals(
            byteArrayOf(9, 9, 9),
            File(rootDir, "Shows/Kote/images/primary.jpg").readBytes(),
        )
        assertEquals(false, File(rootDir, "Shows/Kote/images/primary.png").exists())
    }

    @Test
    fun refreshDoesNotTreatSeasonAndShowArtworkAsEpisodeItemArtwork() = runBlocking {
        val rootDir = temporaryFolder.newFolder("season-show-only-root")
        val root = root(rootDir)
        val sidecarPath = "Shows/Kote/Season 00/Kote - S00E00 - Test.afinity.json"
        writeSidecar(rootDir, sidecarPath, seriesId = seriesId, seasonId = seasonId)
        writeBytes(File(rootDir, "Shows/Kote/Season 00/images/primary.jpg"), byteArrayOf(1, 1, 1))
        writeBytes(File(rootDir, "Shows/Kote/images/primary.jpg"), byteArrayOf(2, 2, 2))
        val index =
            InMemoryLocalLibraryIndexRepository().also {
                it.replaceRootScan(root, listOf(localEpisode(root, sidecarPath)))
            }
        val resolver =
            FakeRemoteArtworkResolver(
                LocalLibraryResolvedArtwork(
                    itemImages =
                        LocalLibraryResolvedImageSet(
                            primary = LocalLibraryResolvedImage(byteArrayOf(3, 3, 3), "image/jpeg", "jpg")
                        )
                )
            )
        val service = service(root, index, resolver)

        val summary = service.refreshMissingArtwork()

        assertEquals(1, summary.writtenFiles)
        assertEquals(1, resolver.seenOrigins.size)
        assertArrayEquals(
            byteArrayOf(3, 3, 3),
            File(rootDir, "Shows/Kote/Season 00/images/Kote - S00E00 - Test/primary.jpg").readBytes(),
        )
        assertArrayEquals(
            byteArrayOf(1, 1, 1),
            File(rootDir, "Shows/Kote/Season 00/images/primary.jpg").readBytes(),
        )
        assertArrayEquals(
            byteArrayOf(2, 2, 2),
            File(rootDir, "Shows/Kote/images/primary.jpg").readBytes(),
        )
    }

    private fun service(
        root: LocalLibraryRootRecord,
        index: LocalLibraryIndexRepository,
        resolver: LocalLibraryRemoteArtworkResolver,
    ): LocalLibraryOriginArtworkRefresher =
        LocalLibraryOriginArtworkRefresher(
            rootStore = StaticRootStore(listOf(root)),
            indexRepository = index,
            fileSystem = FilePathLibraryFileSystem(),
            sidecarReader = sidecarReader,
            remoteArtworkResolver = resolver,
        )

    private fun root(rootDir: File): LocalLibraryRootRecord =
        LocalLibraryRootRecord(
            registryId = rootId,
            stableRootId = rootId,
            displayName = "Local",
            kind = LocalLibraryRootKind.APP_PRIVATE,
            uriOrPath = rootDir.absolutePath,
            writable = true,
            lastKnownAvailable = true,
        )

    private fun localEpisode(
        root: LocalLibraryRootRecord,
        sidecarPath: String,
    ): LocalMediaFileRecord =
        LocalMediaFileRecord(
            mediaFileId = UUID.nameUUIDFromBytes("media:$itemId".toByteArray()),
            rootRegistryId = root.registryId,
            stableRootId = root.stableRootId,
            relativePath = "Shows/Kote/Season 00/Kote - S00E00 - Test.mp4",
            sidecarRelativePath = sidecarPath,
            ownerUserId = userId.toString(),
            mediaKind = LocalMediaKind.EPISODE,
            identity =
                LocalMediaIdentity(
                    localItemId = itemId.toString(),
                    serverId = serverId,
                    jellyfinItemId = itemId.toString(),
                    jellyfinSourceId = "source-1",
                    stableRootId = root.stableRootId,
                    fingerprint = LocalMediaFingerprint("test", "fingerprint"),
                ),
            title =
                LocalLibraryTitle(
                    name = "Test",
                    showName = "Kote",
                    seasonNumber = 0,
                    episodeNumber = 0,
                ),
            sizeBytes = 100,
            modifiedAt = 1,
            container = "mp4",
            runtimeTicks = null,
        )

    private fun writeSidecar(
        rootDir: File,
        sidecarPath: String,
        seriesId: UUID?,
        seasonId: UUID?,
    ) {
        val sidecar =
            AfinityMediaSidecar(
                mediaKind = "episode",
                server = AfinitySidecarServer(serverId = serverId, baseUrlHint = "http://example.test"),
                user = AfinitySidecarUser(userId = userId.toString()),
                identity =
                    AfinitySidecarIdentity(
                        itemId = itemId.toString(),
                        sourceId = "source-1",
                        seriesId = seriesId?.toString(),
                        seasonId = seasonId?.toString(),
                    ),
                localIdentity =
                    AfinitySidecarLocalIdentity(
                        localItemId = itemId.toString(),
                        stableRootId = rootId.toString(),
                        relativePathAtWrite = "Shows/Kote/Season 00/Kote - S00E00 - Test.mp4",
                        fingerprint = AfinitySidecarFingerprint("test", "fingerprint"),
                    ),
                titles =
                    AfinitySidecarTitles(
                        name = "Test",
                        showName = "Kote",
                        seasonNumber = 0,
                        episodeNumber = 0,
                    ),
            )
        File(rootDir, sidecarPath).also { file ->
            file.parentFile?.mkdirs()
            file.writeText(sidecarReader.encodeMediaSidecar(sidecar))
        }
    }

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

    private class FakeRemoteArtworkResolver(
        private val artwork: LocalLibraryResolvedArtwork?
    ) : LocalLibraryRemoteArtworkResolver {
        val seenOrigins = mutableListOf<LocalLibraryArtworkOrigin>()

        override suspend fun resolve(origin: LocalLibraryArtworkOrigin): LocalLibraryResolvedArtwork? {
            seenOrigins += origin
            return artwork
        }
    }
}
