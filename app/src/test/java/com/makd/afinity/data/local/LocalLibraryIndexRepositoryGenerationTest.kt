package com.makd.afinity.data.local

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalLibraryIndexRepositoryGenerationTest {
    @Test
    fun replaceRootScanAdvancesCatalogGeneration() = runBlocking {
        val repository = InMemoryLocalLibraryIndexRepository()
        val before = repository.catalogGenerationFlow().first()

        repository.replaceRootScan(root(), listOf(movieRecord()))

        val after = repository.catalogGenerationFlow().first()
        assertNotEquals(before, after)
    }

    private fun root(): LocalLibraryRootRecord =
        LocalLibraryRootRecord(
            registryId = rootId,
            stableRootId = rootId,
            displayName = "Library",
            kind = LocalLibraryRootKind.APP_PRIVATE,
            uriOrPath = "/library",
        )

    private fun movieRecord(): LocalMediaFileRecord =
        LocalMediaFileRecord(
            mediaFileId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            rootRegistryId = rootId,
            stableRootId = rootId,
            relativePath = "Movies/Movie (2026)/Movie (2026).mkv",
            sidecarRelativePath = null,
            mediaKind = LocalMediaKind.MOVIE,
            identity =
                LocalMediaIdentity(
                    localItemId = "local-movie",
                    serverId = "server-a",
                    jellyfinItemId = "movie-a",
                    jellyfinSourceId = "source-a",
                    stableRootId = rootId,
                    fingerprint = LocalMediaFingerprint("test", "movie-a"),
                ),
            title = LocalLibraryTitle(name = "Movie", year = 2026),
            sizeBytes = 1L,
            modifiedAt = 1L,
            container = "mkv",
            runtimeTicks = 1L,
        )

    private companion object {
        val rootId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
