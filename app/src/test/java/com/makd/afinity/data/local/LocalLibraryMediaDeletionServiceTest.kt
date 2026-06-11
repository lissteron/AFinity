package com.makd.afinity.data.local

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryMediaDeletionServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun confirmedPhysicalDeleteUsesFileSystemAndRebuildsIndex() = runBlocking {
        val rootDir = temporaryFolder.newFolder("library")
        val root =
            LocalLibraryRootRecord(
                registryId = UUID.fromString("00000000-0000-0000-0000-00000000dd01"),
                stableRootId = UUID.fromString("00000000-0000-0000-0000-00000000dd02"),
                displayName = "Library",
                kind = LocalLibraryRootKind.APP_PRIVATE,
                uriOrPath = rootDir.absolutePath,
            )
        val media = File(rootDir, "Movies/Delete Me (2026)/Delete Me (2026).mkv")
        writeBytes(media, 24)
        writeText(
            File(rootDir, "Movies/Delete Me (2026)/Delete Me (2026).afinity.json"),
            """
            {
              "schemaVersion": 1,
              "mediaKind": "movie",
              "localIdentity": {
                "localItemId": "delete-me",
                "stableRootId": "${root.stableRootId}",
                "relativePathAtWrite": "Movies/Delete Me (2026)/Delete Me (2026).mkv"
              },
              "titles": { "name": "Delete Me", "year": 2026 },
              "mediaFile": {
                "relativePath": "Movies/Delete Me (2026)/Delete Me (2026).mkv",
                "container": "mkv",
                "sizeBytes": 24
              }
            }
            """
                .trimIndent(),
        )
        val subtitle = File(rootDir, "Movies/Delete Me (2026)/Delete Me (2026).en.srt")
        writeText(subtitle, "1\n00:00:00,000 --> 00:00:01,000\nHi\n")
        val nfo = File(rootDir, "Movies/Delete Me (2026)/movie.nfo")
        writeText(nfo, "<movie><title>Delete Me</title><year>2026</year></movie>")

        val fileSystem = FilePathLibraryFileSystem()
        val index = InMemoryLocalLibraryIndexRepository()
        val scanner =
            LocalLibraryScanner(
                fileSystem = fileSystem,
                sidecarReader = LocalLibrarySidecarReader(),
                indexRepository = index,
                pathPolicy = LocalLibraryPathPolicy(),
            )
        scanner.scanRoot(root)
        val mediaFileId = index.visibleMediaFiles().single().mediaFileId

        val result =
            LocalLibraryMediaDeletionService(
                    rootStore = FakeRootStore(root),
                    indexRepository = index,
                    fileSystem = fileSystem,
                    scanner = scanner,
                    deletionPolicy = LocalLibraryDeletionPolicy(),
                )
                .deletePhysicalMedia(
                    mediaFileId = mediaFileId,
                    confirmed = true,
                    visibilityContext =
                        LocalLibraryVisibilityContext(
                            currentUserId = null,
                            kidModeEnabled = false,
                            parentUnlocked = false,
                        ),
                )

        assertTrue(result.isSuccess)
        assertFalse(media.exists())
        assertFalse(subtitle.exists())
        assertFalse(nfo.exists())
        assertTrue(index.visibleMediaFiles().isEmpty())
    }

    private fun writeBytes(file: File, size: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size))
    }

    private fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private class FakeRootStore(private val root: LocalLibraryRootRecord) : LocalLibraryRootStore {
        override fun rootsFlow(): Flow<List<LocalLibraryRootRecord>> = flowOf(listOf(root))

        override suspend fun getRoots(): List<LocalLibraryRootRecord> = listOf(root)

        override suspend fun replaceRoots(roots: List<LocalLibraryRootRecord>) = Unit

        override suspend fun upsertRoot(root: LocalLibraryRootRecord) = Unit

        override suspend fun removeRoot(registryId: UUID) = Unit

        override suspend fun setDefaultDownloadRoot(registryId: UUID) = Unit
    }
}
