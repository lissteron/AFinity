package com.makd.afinity.data.local

import com.makd.afinity.data.database.dao.LocalLibraryDao
import com.makd.afinity.data.database.entities.LocalLibraryItemEntity
import com.makd.afinity.data.database.entities.LocalLibraryRootSnapshotEntity
import com.makd.afinity.data.database.entities.LocalMediaFileEntity
import com.makd.afinity.data.database.entities.LocalMediaIdentityEntity
import com.makd.afinity.data.database.entities.LocalMediaImportJobEntity
import com.makd.afinity.data.database.entities.LocalMediaSidecarEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class RoomLocalLibraryIndexRepository
@Inject
constructor(private val dao: LocalLibraryDao) : LocalLibraryIndexRepository {
    private val json = Json { encodeDefaults = true }

    override fun catalogGenerationFlow(): Flow<String> = dao.catalogGenerationFlow()

    override fun replaceRootScan(
        root: LocalLibraryRootRecord,
        files: List<LocalMediaFileRecord>,
        importJobs: List<LocalMediaImportJobRecord>,
    ) {
        dao.deleteMediaFilesForRoot(root.registryId.toString())
        dao.deleteImportJobsForRoot(root.registryId.toString())
        dao.upsertRootSnapshot(
            LocalLibraryRootSnapshotEntity(
                registryId = root.registryId.toString(),
                stableRootId = root.stableRootId?.toString(),
                enabled = root.enabled,
                available = root.lastKnownAvailable,
                writable = root.writable,
                defaultForDownloads = root.defaultForDownloads,
                priority = root.priority,
                lastScanStartedAt = System.currentTimeMillis(),
                lastScanCompletedAt = System.currentTimeMillis(),
                lastScanStatus = LocalLibraryScanStatus.COMPLETED.name,
                lastError = null,
            )
        )
        val existingIdentitiesByLocalItemId = dao.getIdentities().associateBy { it.localItemId }
        dao.upsertIdentities(
            files.map { it.toIdentityEntity().mergeParentIdentity(existingIdentitiesByLocalItemId[it.identity.localItemId]) }
        )
        dao.upsertItems(files.map { it.toItemEntity() })
        dao.upsertMediaFiles(files.map { it.toFileEntity() })
        dao.upsertSidecars(files.mapNotNull { it.toSidecarEntity() })
        dao.upsertImportJobs(importJobs.map { it.toEntity() })
        dao.deleteOrphanedIdentities()
        dao.deleteOrphanedItems()
        dao.deleteOrphanedSidecars()
    }

    override fun markRootUnavailable(root: LocalLibraryRootRecord) {
        dao.upsertRootSnapshot(
            LocalLibraryRootSnapshotEntity(
                registryId = root.registryId.toString(),
                stableRootId = root.stableRootId?.toString(),
                enabled = root.enabled,
                available = false,
                writable = root.writable,
                defaultForDownloads = root.defaultForDownloads,
                priority = root.priority,
                lastScanStartedAt = null,
                lastScanCompletedAt = System.currentTimeMillis(),
                lastScanStatus = LocalLibraryScanStatus.FAILED.name,
                lastError = "Root unavailable",
            )
        )
    }

    override fun allMediaFiles(): List<LocalMediaFileRecord> =
        dao.getAllMediaFiles().toRecords(dao.getIdentities())

    override fun visibleMediaFiles(): List<LocalMediaFileRecord> {
        return visibleMediaFiles { it.visibleByDefault }
    }

    override fun visibleMediaFiles(
        visibilityContext: LocalLibraryVisibilityContext
    ): List<LocalMediaFileRecord> =
        visibleMediaFiles {
            it.visibleByDefault &&
                LocalLibraryVisibilityPolicy().isVisible(it.ownerUserId, visibilityContext)
        }

    private fun visibleMediaFiles(
        predicate: (LocalMediaFileEntity) -> Boolean
    ): List<LocalMediaFileRecord> {
        val rootsById = dao.getRootSnapshots().associateBy { it.registryId }
        val identitiesByLocalItemId = dao.getIdentities().associateBy { it.localItemId }
        return dao.getAllMediaFiles()
            .filter { file -> predicate(file) && rootsById[file.rootRegistryId].isVisibleRoot() }
            .map { it.toRecord(identitiesByLocalItemId[it.localItemId]) }
            .groupBy { it.identity.durableKey }
            .values
            .map { group ->
                group
                    .sortedWith(
                        compareBy<LocalMediaFileRecord> {
                                rootsById[it.rootRegistryId.toString()]?.priority ?: Int.MAX_VALUE
                            }
                            .thenBy { it.rootRegistryId }
                            .thenBy { it.relativePath }
                    )
                    .first()
            }
    }

    private fun LocalLibraryRootSnapshotEntity?.isVisibleRoot(): Boolean =
        this == null || (enabled && available)

    override fun findByMediaFileId(mediaFileId: UUID): LocalMediaFileRecord? =
        dao.getMediaFile(mediaFileId.toString())?.let { it.toRecord(dao.getIdentity(it.localItemId)) }

    override fun findByLocalItemId(localItemId: String): LocalMediaFileRecord? =
        dao.getMediaFileForLocalItem(localItemId)?.toRecord(dao.getIdentity(localItemId))

    override fun duplicateGroupCount(): Int =
        dao.getAllMediaFiles().groupBy { it.durableKey }.count { it.value.size > 1 }

    override fun recordImportJob(job: LocalMediaImportJobRecord) {
        dao.upsertImportJob(job.toEntity())
    }

    override fun importJobs(): List<LocalMediaImportJobRecord> =
        dao.getImportJobs().map { it.toRecord() }

    private fun LocalMediaFileRecord.toIdentityEntity(): LocalMediaIdentityEntity =
        LocalMediaIdentityEntity(
            localItemId = identity.localItemId,
            durableKey = identity.durableKey,
            serverId = identity.serverId,
            jellyfinItemId = identity.jellyfinItemId,
            jellyfinSourceId = identity.jellyfinSourceId,
            jellyfinSeriesId = identity.jellyfinSeriesId,
            jellyfinSeasonId = identity.jellyfinSeasonId,
            providerIdsJson = json.encodeToString(identity.providerIds),
            stableRootId = identity.stableRootId?.toString(),
            fingerprintStrategy = identity.fingerprint.strategy,
            fingerprintValue = identity.fingerprint.value,
        )

    private fun LocalMediaIdentityEntity.mergeParentIdentity(
        existing: LocalMediaIdentityEntity?
    ): LocalMediaIdentityEntity =
        copy(
            jellyfinSeriesId = jellyfinSeriesId ?: existing?.jellyfinSeriesId,
            jellyfinSeasonId = jellyfinSeasonId ?: existing?.jellyfinSeasonId,
        )

    private fun LocalMediaFileRecord.toItemEntity(): LocalLibraryItemEntity =
        LocalLibraryItemEntity(
            localItemId = identity.localItemId,
            durableKey = identity.durableKey,
            mediaKind = mediaKind.name,
            name = title.name,
            showName = title.showName,
            year = title.year,
            seasonNumber = title.seasonNumber,
            episodeNumber = title.episodeNumber,
            updatedAt = System.currentTimeMillis(),
        )

    private fun LocalMediaFileRecord.toFileEntity(): LocalMediaFileEntity =
        LocalMediaFileEntity(
            mediaFileId = mediaFileId.toString(),
            localItemId = identity.localItemId,
            durableKey = identity.durableKey,
            rootRegistryId = rootRegistryId.toString(),
            stableRootId = stableRootId?.toString(),
            relativePath = relativePath,
            sidecarRelativePath = sidecarRelativePath,
            ownerUserId = ownerUserId,
            mediaKind = mediaKind.name,
            sizeBytes = sizeBytes,
            modifiedAt = modifiedAt,
            container = container,
            runtimeTicks = runtimeTicks,
            state = state.name,
            visibleByDefault = visibleByDefault,
            titleName = title.name,
            titleShowName = title.showName,
            titleYear = title.year,
            titleSeasonNumber = title.seasonNumber,
            titleEpisodeNumber = title.episodeNumber,
        )

    private fun LocalMediaFileRecord.toSidecarEntity(): LocalMediaSidecarEntity? {
        val path = sidecarRelativePath ?: return null
        return LocalMediaSidecarEntity(
            mediaFileId = mediaFileId.toString(),
            sidecarRelativePath = path,
            parseStatus = "PARSED",
            lastError = null,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun LocalMediaImportJobRecord.toEntity(): LocalMediaImportJobEntity =
        LocalMediaImportJobEntity(
            jobId = jobId,
            rootRegistryId = rootRegistryId.toString(),
            relativePath = relativePath,
            mediaFileId = mediaFileId?.toString(),
            state = state.name,
            lastError = lastError,
            updatedAt = updatedAt,
        )

    private fun LocalMediaImportJobEntity.toRecord(): LocalMediaImportJobRecord =
        LocalMediaImportJobRecord(
            jobId = jobId,
            rootRegistryId = UUID.fromString(rootRegistryId),
            relativePath = relativePath,
            mediaFileId = mediaFileId?.let { UUID.fromString(it) },
            state = LocalMediaImportState.valueOf(state),
            lastError = lastError,
            updatedAt = updatedAt,
        )

    private fun List<LocalMediaFileEntity>.toRecords(
        identities: List<LocalMediaIdentityEntity>
    ): List<LocalMediaFileRecord> {
        val identitiesByLocalItemId = identities.associateBy { it.localItemId }
        return map { file -> file.toRecord(identitiesByLocalItemId[file.localItemId]) }
    }

    private fun LocalMediaFileEntity.toRecord(
        identityEntity: LocalMediaIdentityEntity?
    ): LocalMediaFileRecord {
        val stableRootUuid =
            (identityEntity?.stableRootId ?: stableRootId)?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
        val fingerprint =
            identityEntity?.let {
                LocalMediaFingerprint(strategy = it.fingerprintStrategy, value = it.fingerprintValue)
            } ?: LocalMediaFingerprint(
                strategy = "room-index-v1",
                value = "$sizeBytes:$modifiedAt:$relativePath",
            )
        val providerIds =
            identityEntity?.providerIdsJson?.let {
                runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
        return LocalMediaFileRecord(
            mediaFileId = UUID.fromString(mediaFileId),
            rootRegistryId = UUID.fromString(rootRegistryId),
            stableRootId = stableRootUuid,
            relativePath = relativePath,
            sidecarRelativePath = sidecarRelativePath,
            ownerUserId = ownerUserId,
            mediaKind = LocalMediaKind.valueOf(mediaKind),
            identity =
                LocalMediaIdentity(
                    localItemId = localItemId,
                    serverId = identityEntity?.serverId,
                    jellyfinItemId = identityEntity?.jellyfinItemId,
                    jellyfinSourceId = identityEntity?.jellyfinSourceId,
                    jellyfinSeriesId = identityEntity?.jellyfinSeriesId,
                    jellyfinSeasonId = identityEntity?.jellyfinSeasonId,
                    providerIds = providerIds,
                    stableRootId = stableRootUuid,
                    fingerprint = fingerprint,
                ),
            title =
                LocalLibraryTitle(
                    name = titleName,
                    showName = titleShowName,
                    year = titleYear,
                    seasonNumber = titleSeasonNumber,
                    episodeNumber = titleEpisodeNumber,
                ),
            sizeBytes = sizeBytes,
            modifiedAt = modifiedAt,
            container = container,
            runtimeTicks = runtimeTicks,
            state = LocalMediaImportState.valueOf(state),
            visibleByDefault = visibleByDefault,
        )
    }
}
