package com.makd.afinity.data.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "local_library_root_snapshots")
data class LocalLibraryRootSnapshotEntity(
    @PrimaryKey val registryId: String,
    val stableRootId: String?,
    val enabled: Boolean,
    val available: Boolean,
    val writable: Boolean,
    val defaultForDownloads: Boolean,
    val priority: Int,
    val lastScanStartedAt: Long?,
    val lastScanCompletedAt: Long?,
    val lastScanStatus: String?,
    val lastError: String?,
)

@Entity(
    tableName = "local_library_items",
    indices = [Index(value = ["durableKey"], unique = true), Index(value = ["mediaKind"])],
)
data class LocalLibraryItemEntity(
    @PrimaryKey val localItemId: String,
    val durableKey: String,
    val mediaKind: String,
    val name: String,
    val showName: String?,
    val year: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val updatedAt: Long,
)

@Entity(
    tableName = "local_media_files",
    indices =
        [
            Index(value = ["rootRegistryId", "relativePath"], unique = true),
            Index(value = ["localItemId"]),
            Index(value = ["durableKey"]),
        ],
)
data class LocalMediaFileEntity(
    @PrimaryKey val mediaFileId: String,
    val localItemId: String,
    val durableKey: String,
    val rootRegistryId: String,
    val stableRootId: String?,
    val relativePath: String,
    val sidecarRelativePath: String?,
    val ownerUserId: String?,
    val mediaKind: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val container: String?,
    val runtimeTicks: Long?,
    val state: String,
    val visibleByDefault: Boolean,
    val titleName: String,
    val titleShowName: String?,
    val titleYear: Int?,
    val titleSeasonNumber: Int?,
    val titleEpisodeNumber: Int?,
)

@Entity(tableName = "local_media_identities", indices = [Index(value = ["durableKey"])])
data class LocalMediaIdentityEntity(
    @PrimaryKey val localItemId: String,
    val durableKey: String,
    val serverId: String?,
    val jellyfinItemId: String?,
    val jellyfinSourceId: String?,
    val jellyfinSeriesId: String?,
    val jellyfinSeasonId: String?,
    val providerIdsJson: String,
    val stableRootId: String?,
    val fingerprintStrategy: String,
    val fingerprintValue: String,
)

@Entity(tableName = "local_media_sidecars")
data class LocalMediaSidecarEntity(
    @PrimaryKey val mediaFileId: String,
    val sidecarRelativePath: String,
    val parseStatus: String,
    val lastError: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "local_media_user_state",
    primaryKeys = ["localItemId", "profileUserId"],
)
data class LocalMediaUserStateEntity(
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

@Entity(tableName = "local_media_import_jobs")
data class LocalMediaImportJobEntity(
    @PrimaryKey val jobId: String,
    val rootRegistryId: String,
    val relativePath: String,
    val mediaFileId: String?,
    val state: String,
    val lastError: String?,
    val updatedAt: Long,
)

@Entity(tableName = "local_library_scan_runs")
data class LocalLibraryScanRunEntity(
    @PrimaryKey val scanRunId: String,
    val rootRegistryId: String,
    val startedAt: Long,
    val completedAt: Long?,
    val status: String,
    val discoveredFiles: Int,
    val importedItems: Int,
    val updatedItems: Int,
    val unavailableItems: Int,
    val duplicateGroups: Int,
    val parseWarnings: Int,
    val errorsJson: String,
)

@Entity(
    tableName = "local_media_visibility",
    primaryKeys = ["localItemId", "profileUserId"],
)
data class LocalMediaVisibilityEntity(
    val localItemId: String,
    val profileUserId: String,
    val visible: Boolean,
    val reason: String,
    val updatedAt: Long,
)
