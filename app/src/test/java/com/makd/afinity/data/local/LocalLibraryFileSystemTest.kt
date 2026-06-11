package com.makd.afinity.data.local

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryFileSystemTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun filePathMediaWriteTargetStagesPartFileAndFinalizesCanonicalMedia() {
        val rootDir = temporaryFolder.newFolder("library")
        val root =
            LocalLibraryRootRecord(
                registryId = UUID.fromString("00000000-0000-0000-0000-00000000fc01"),
                stableRootId = UUID.fromString("00000000-0000-0000-0000-00000000fc02"),
                displayName = "Library",
                kind = LocalLibraryRootKind.APP_PRIVATE,
                uriOrPath = rootDir.absolutePath,
            )
        val fileSystem = FilePathLibraryFileSystem()
        val target =
            fileSystem.createMediaWriteTarget(
                root,
                "Movies/Write Target (2026)/Write Target (2026).mkv",
            )

        target.openOutputStream(append = false).use { it.write(ByteArray(9)) }
        assertEquals(9L, target.resumeSize)
        assertTrue(rootDir.resolve("Movies/Write Target (2026)/Write Target (2026).mkv.part").exists())
        assertFalse(rootDir.resolve("Movies/Write Target (2026)/Write Target (2026).mkv").exists())

        val completed = target.finish()

        assertEquals(9L, completed.sizeBytes)
        assertFalse(rootDir.resolve("Movies/Write Target (2026)/Write Target (2026).mkv.part").exists())
        assertTrue(rootDir.resolve("Movies/Write Target (2026)/Write Target (2026).mkv").exists())
    }

    @Test
    fun filePathMediaWriteTargetCanReplaceExistingFinalFromVerifiedPart() {
        val rootDir = temporaryFolder.newFolder("library")
        val root =
            LocalLibraryRootRecord(
                registryId = UUID.fromString("00000000-0000-0000-0000-00000000fc03"),
                stableRootId = UUID.fromString("00000000-0000-0000-0000-00000000fc04"),
                displayName = "Library",
                kind = LocalLibraryRootKind.APP_PRIVATE,
                uriOrPath = rootDir.absolutePath,
            )
        val finalFile = rootDir.resolve("Movies/Replace (2026)/Replace (2026).mkv")
        finalFile.parentFile?.mkdirs()
        finalFile.writeBytes(ByteArray(3) { 1 })
        val fileSystem = FilePathLibraryFileSystem()
        val target =
            fileSystem.createMediaWriteTarget(
                root,
                "Movies/Replace (2026)/Replace (2026).mkv",
            )

        target.openOutputStream(append = false).use { it.write(ByteArray(7) { 2 }) }
        val completed = target.finish()

        assertEquals(7L, completed.sizeBytes)
        assertEquals(7L, finalFile.length())
        assertEquals(2, finalFile.readBytes().first().toInt())
        assertFalse(rootDir.resolve("Movies/Replace (2026)/Replace (2026).mkv.part").exists())
    }
}
