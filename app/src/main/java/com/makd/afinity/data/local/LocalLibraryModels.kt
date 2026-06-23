package com.makd.afinity.data.local

import java.util.UUID

enum class LocalLibraryRootKind {
    APP_PRIVATE,
    DEVICE_SHARED,
    SECONDARY_FILE_PATH,
    SAF_TREE,
}

enum class LocalLibraryScanStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

enum class LocalMediaKind {
    MOVIE,
    EPISODE,
}

enum class LocalMediaImportState {
    IMPORTED,
    IMPORT_PENDING,
    UNAVAILABLE,
}

data class LocalLibraryRootRecord(
    val registryId: UUID,
    val stableRootId: UUID?,
    val displayName: String,
    val kind: LocalLibraryRootKind,
    val uriOrPath: String,
    val enabled: Boolean = true,
    val writable: Boolean = true,
    val removable: Boolean = false,
    val defaultForDownloads: Boolean = false,
    val priority: Int = 0,
    val persistedUriPermission: Boolean = false,
    val lastKnownAvailable: Boolean = true,
)

data class LocalLibraryRootSnapshot(
    val registryId: UUID,
    val stableRootId: UUID?,
    val enabled: Boolean,
    val available: Boolean,
    val writable: Boolean,
    val lastScanStartedAt: Long?,
    val lastScanCompletedAt: Long?,
    val lastScanStatus: LocalLibraryScanStatus?,
    val lastError: String?,
)

data class LocalMediaFingerprint(
    val strategy: String,
    val value: String,
)

data class LocalMediaIdentity(
    val localItemId: String,
    val serverId: String?,
    val jellyfinItemId: String?,
    val jellyfinSourceId: String?,
    val jellyfinSeriesId: String? = null,
    val jellyfinSeasonId: String? = null,
    val providerIds: Map<String, String> = emptyMap(),
    val stableRootId: UUID?,
    val fingerprint: LocalMediaFingerprint,
) {
    val durableKey: String =
        when {
            !serverId.isNullOrBlank() &&
                !jellyfinItemId.isNullOrBlank() &&
                !jellyfinSourceId.isNullOrBlank() ->
                "jellyfin:$serverId:$jellyfinItemId:$jellyfinSourceId"

            providerIds.isNotEmpty() ->
                "providers:" + providerIds.toSortedMap().entries.joinToString("|") {
                    "${it.key}:${it.value}"
                }

            else -> "local:$localItemId"
        }
}

data class LocalLibraryTitle(
    val name: String,
    val showName: String? = null,
    val year: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class LocalMediaFileRecord(
    val mediaFileId: UUID,
    val rootRegistryId: UUID,
    val stableRootId: UUID?,
    val relativePath: String,
    val sidecarRelativePath: String?,
    val ownerUserId: String? = null,
    val mediaKind: LocalMediaKind,
    val identity: LocalMediaIdentity,
    val title: LocalLibraryTitle,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val container: String?,
    val runtimeTicks: Long?,
    val state: LocalMediaImportState = LocalMediaImportState.IMPORTED,
    val visibleByDefault: Boolean = true,
)

data class LocalLibraryScanSummary(
    val rootId: UUID,
    val discoveredFiles: Int,
    val importedItems: Int,
    val updatedItems: Int,
    val unavailableItems: Int,
    val duplicateGroups: Int,
    val parseWarnings: Int,
    val cancelled: Boolean,
    val errors: List<String>,
)

data class LocalMediaImportJobRecord(
    val jobId: String,
    val rootRegistryId: UUID,
    val relativePath: String,
    val mediaFileId: UUID?,
    val state: LocalMediaImportState,
    val lastError: String?,
    val updatedAt: Long,
)

data class LocalMediaUserStateRecord(
    val localItemId: String,
    val profileUserId: String,
    val serverId: String?,
    val jellyfinUserId: String?,
    val jellyfinItemId: String?,
    val playbackPositionTicks: Long,
    val played: Boolean,
    val favorite: Boolean,
    val updatedAt: Long,
)

data class LocalPlaybackResolutionRequest(
    val mediaFileId: UUID? = null,
    val localItemId: String? = null,
    val visibilityContext: LocalLibraryVisibilityContext =
        LocalLibraryVisibilityContext(currentUserId = null, kidModeEnabled = false, parentUnlocked = false),
)

sealed interface LocalPlaybackResolution {
    data class Resolved(
        val mediaFile: LocalMediaFileRecord,
        val playerUri: String,
        val subtitles: List<String>,
    ) : LocalPlaybackResolution

    data class Unavailable(val reason: String) : LocalPlaybackResolution
}
