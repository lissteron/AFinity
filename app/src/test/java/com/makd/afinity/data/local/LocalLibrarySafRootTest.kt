package com.makd.afinity.data.local

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibrarySafRootTest {
    @Test
    fun readOnlySafRootCanBeScannedAndPlayedThroughFileSystemBoundary() {
        val root =
            LocalLibraryRootRecord(
                registryId = UUID.fromString("00000000-0000-0000-0000-00000000fa01"),
                stableRootId = UUID.fromString("00000000-0000-0000-0000-00000000fa02"),
                displayName = "Read-only SAF",
                kind = LocalLibraryRootKind.SAF_TREE,
                uriOrPath = "content://provider/tree/library",
                writable = false,
                persistedUriPermission = true,
            )
        val fileSystem =
            FakeSafFileSystem(
                mapOf(
                    "Movies/SAF Movie (2026)/SAF Movie (2026).mkv" to ByteArray(12),
                    "Movies/SAF Movie (2026)/SAF Movie (2026).afinity.json" to
                        """
                        {
                          "schemaVersion": 1,
                          "mediaKind": "movie",
                          "localIdentity": {
                            "localItemId": "saf-movie",
                            "stableRootId": "${root.stableRootId}",
                            "relativePathAtWrite": "Movies/SAF Movie (2026)/SAF Movie (2026).mkv"
                          },
                          "titles": { "name": "SAF Movie", "year": 2026 },
                          "mediaFile": {
                            "relativePath": "Movies/SAF Movie (2026)/SAF Movie (2026).mkv",
                            "container": "mkv",
                            "sizeBytes": 12
                          }
                        }
                        """
                            .trimIndent()
                            .toByteArray(),
                )
            )
        val index = InMemoryLocalLibraryIndexRepository()

        val summary =
            LocalLibraryScanner(
                    fileSystem = fileSystem,
                    sidecarReader = LocalLibrarySidecarReader(),
                    indexRepository = index,
                    pathPolicy = LocalLibraryPathPolicy(),
                )
                .scanRoot(root)
        val playback =
            LocalPlaybackSourceResolver(
                    roots = { listOf(root) },
                    fileSystem = fileSystem,
                    indexRepository = index,
                )
                .resolve(LocalPlaybackResolutionRequest(localItemId = "saf-movie"))

        assertEquals(1, summary.importedItems)
        assertEquals("SAF Movie", index.visibleMediaFiles().single().title.name)
        assertTrue(playback is LocalPlaybackResolution.Resolved)
        playback as LocalPlaybackResolution.Resolved
        assertEquals("content://provider/tree/library/Movies/SAF Movie (2026)/SAF Movie (2026).mkv", playback.playerUri)
    }

    private class FakeSafFileSystem(private val files: Map<String, ByteArray>) :
        LocalLibraryFileSystem {
        override fun list(
            root: LocalLibraryRootRecord,
            relativePath: String,
        ): List<LocalLibraryNode> {
            val prefix = relativePath.takeIf { it.isNotBlank() }?.let { "$it/" }.orEmpty()
            val directChildren =
                files.keys
                    .filter { it.startsWith(prefix) }
                    .map { it.removePrefix(prefix) }
                    .filter { it.isNotBlank() }
                    .map { it.substringBefore('/') }
                    .distinct()
            return directChildren.map { child ->
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
        ): Boolean = false

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

        override fun delete(
            root: LocalLibraryRootRecord,
            relativePath: String,
        ): Boolean = false

        override fun createMediaWriteTarget(
            root: LocalLibraryRootRecord,
            relativeMediaPath: String,
        ): LocalLibraryMediaWriteTarget = error("Read-only SAF test filesystem")
    }
}
