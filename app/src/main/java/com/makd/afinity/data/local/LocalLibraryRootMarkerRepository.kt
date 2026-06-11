package com.makd.afinity.data.local

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class LocalLibraryRootMarkerRepository
@Inject
constructor(
    private val fileSystem: LocalLibraryFileSystem,
    private val sidecarReader: LocalLibrarySidecarReader,
) {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    fun readStableRootId(root: LocalLibraryRootRecord): UUID? =
        fileSystem.readText(root, ROOT_MARKER_PATH)?.let(sidecarReader::readRootSidecar)?.stableRootId()

    fun readOrCreateStableRootId(
        root: LocalLibraryRootRecord,
        displayName: String,
    ): UUID {
        readStableRootId(root)?.let { return it }
        val rootId = UUID.randomUUID()
        val marker =
            AfinityRootSidecar(
                schemaVersion = 1,
                rootId = rootId.toString(),
                createdBy = "AFinity",
                createdAt = System.currentTimeMillis(),
                displayName = displayName.ifBlank { "AFinity Library" },
                libraryLayout = "afinity-local-library-v2",
            )
        val written =
            fileSystem.writeText(
            root = root.copy(stableRootId = rootId),
            relativePath = ROOT_MARKER_PATH,
            text = json.encodeToString(marker),
            mimeType = "application/json",
        )
        require(written) { "Failed to write local library root marker" }
        return rootId
    }

    companion object {
        const val ROOT_MARKER_PATH = ".afinity-root.json"
    }
}
