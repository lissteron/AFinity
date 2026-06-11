package com.makd.afinity.data.local

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryRootMarkerRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun reattachedMarkedRootReusesStableRootIdAfterRegistryReset() {
        val rootDir = temporaryFolder.newFolder("library")
        val repository =
            LocalLibraryRootMarkerRepository(
                fileSystem = FilePathLibraryFileSystem(),
                sidecarReader = LocalLibrarySidecarReader(),
            )
        val firstAttachment =
            LocalLibraryRootRecord(
                registryId = UUID.fromString("00000000-0000-0000-0000-00000000ee01"),
                stableRootId = null,
                displayName = "Library",
                kind = LocalLibraryRootKind.APP_PRIVATE,
                uriOrPath = rootDir.absolutePath,
            )
        val stableRootId = repository.readOrCreateStableRootId(firstAttachment, "Library")
        val reattachedAfterReset =
            firstAttachment.copy(
                registryId = UUID.fromString("00000000-0000-0000-0000-00000000ee02"),
                stableRootId = null,
            )

        val restoredStableRootId =
            repository.readOrCreateStableRootId(reattachedAfterReset, "Library")

        assertEquals(stableRootId, restoredStableRootId)
        assertTrue(rootDir.resolve(LocalLibraryRootMarkerRepository.ROOT_MARKER_PATH).exists())
    }
}
