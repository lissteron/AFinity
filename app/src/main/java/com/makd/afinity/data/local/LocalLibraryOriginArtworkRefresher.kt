package com.makd.afinity.data.local

import android.net.Uri
import com.makd.afinity.data.models.extensions.toAfinityEpisode
import com.makd.afinity.data.models.extensions.toAfinityMovie
import com.makd.afinity.data.models.extensions.toAfinitySeason
import com.makd.afinity.data.models.extensions.toAfinityShow
import com.makd.afinity.data.models.media.AfinityImages
import com.makd.afinity.data.repository.download.SessionRestoreResolver
import com.makd.afinity.data.repository.download.SessionRestoreResult
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber

data class LocalLibraryOriginArtworkProgress(
    val completed: Int,
    val total: Int,
    val currentItemName: String?,
)

data class LocalLibraryOriginArtworkSummary(
    val refreshedItems: Int,
    val writtenFiles: Int,
    val removedFiles: Int,
    val updatedSidecars: Int,
    val skippedItems: Int,
    val failedItems: Int,
)

data class LocalLibraryArtworkOrigin(
    val serverId: String,
    val userId: UUID,
    val itemId: UUID,
    val sourceId: String?,
    val itemName: String,
    val mediaKind: LocalMediaKind,
)

data class LocalLibraryResolvedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
)

data class LocalLibraryResolvedImageSet(
    val primary: LocalLibraryResolvedImage? = null,
    val backdrop: LocalLibraryResolvedImage? = null,
    val thumb: LocalLibraryResolvedImage? = null,
    val logo: LocalLibraryResolvedImage? = null,
) {
    val isEmpty: Boolean
        get() = primary == null && backdrop == null && thumb == null && logo == null
}

data class LocalLibraryResolvedArtwork(
    val itemImages: LocalLibraryResolvedImageSet = LocalLibraryResolvedImageSet(),
    val showImages: LocalLibraryResolvedImageSet = LocalLibraryResolvedImageSet(),
    val seasonImages: LocalLibraryResolvedImageSet = LocalLibraryResolvedImageSet(),
    val seriesId: String? = null,
    val seasonId: String? = null,
)

interface LocalLibraryRemoteArtworkResolver {
    suspend fun resolve(origin: LocalLibraryArtworkOrigin): LocalLibraryResolvedArtwork?
}

@Singleton
class LocalLibraryOriginArtworkRefresher
@Inject
constructor(
    private val rootStore: LocalLibraryRootStore,
    private val indexRepository: LocalLibraryIndexRepository,
    private val fileSystem: LocalLibraryFileSystem,
    private val sidecarReader: LocalLibrarySidecarReader,
    private val remoteArtworkResolver: LocalLibraryRemoteArtworkResolver,
) {
    suspend fun refreshCandidateCount(forceRefreshItemArtwork: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            val rootsById = rootStore.getRoots().associateBy { it.registryId }
            val mediaFiles = indexRepository.allMediaFiles()
            val corruptedItemIds =
                if (forceRefreshItemArtwork) emptySet() else mediaFiles.duplicatedItemArtworkLocalItemIds(rootsById)
            mediaFiles.count { mediaFile ->
                mediaFile.refreshCandidate(
                    rootsById = rootsById,
                    forceRefreshItemArtwork = forceRefreshItemArtwork ||
                        mediaFile.identity.localItemId in corruptedItemIds,
                ) != null
            }
        }

    suspend fun refreshMissingArtwork(
        progress: suspend (LocalLibraryOriginArtworkProgress) -> Unit = {},
        overwriteExistingItemArtwork: Boolean = false,
    ): LocalLibraryOriginArtworkSummary =
        withContext(Dispatchers.IO) {
            val rootsById = rootStore.getRoots().associateBy { it.registryId }
            val mediaFiles = indexRepository.allMediaFiles()
            val corruptedItemIds =
                if (overwriteExistingItemArtwork) {
                    emptySet()
                } else {
                    mediaFiles.duplicatedItemArtworkLocalItemIds(rootsById)
                }
            if (corruptedItemIds.isNotEmpty()) {
                Timber.i(
                    "Found duplicated item artwork for %d local library items; refreshing item artwork from origin",
                    corruptedItemIds.size,
                )
            }
            val candidates =
                mediaFiles.mapNotNull { mediaFile ->
                    mediaFile.refreshCandidate(
                        rootsById = rootsById,
                        forceRefreshItemArtwork = overwriteExistingItemArtwork ||
                            mediaFile.identity.localItemId in corruptedItemIds,
                    )
                }
            val processedTargets = mutableSetOf<String>()
            val blockedSessions = mutableSetOf<Pair<String, UUID>>()
            var refreshedItems = 0
            var writtenFiles = 0
            var removedFiles = 0
            var updatedSidecars = 0
            var skippedItems = 0
            var failedItems = 0

            progress(
                LocalLibraryOriginArtworkProgress(
                    completed = 0,
                    total = candidates.size,
                    currentItemName = null,
                )
            )
            candidates.forEachIndexed { index, candidate ->
                currentCoroutineContext().ensureActive()
                val sessionKey = candidate.origin.serverId to candidate.origin.userId
                progress(
                    LocalLibraryOriginArtworkProgress(
                        completed = index,
                        total = candidates.size,
                        currentItemName = candidate.origin.itemName,
                    )
                )
                if (sessionKey in blockedSessions) {
                    skippedItems += 1
                    progress(
                        LocalLibraryOriginArtworkProgress(
                            completed = index + 1,
                            total = candidates.size,
                            currentItemName = null,
                        )
                    )
                    return@forEachIndexed
                }

                try {
                    val resolved = remoteArtworkResolver.resolve(candidate.origin)
                    if (resolved == null) {
                        skippedItems += 1
                    } else {
                        val changes =
                            writeResolvedArtwork(
                                candidate.root,
                                candidate.mediaFile,
                                resolved,
                                processedTargets,
                                candidate.overwriteExistingItemArtwork,
                            )
                        val sidecarUpdated =
                            updateSidecarParentIdentity(candidate.root, candidate.mediaFile, resolved)
                        if (changes.hasChanges || sidecarUpdated) refreshedItems += 1 else skippedItems += 1
                        writtenFiles += changes.writtenFiles
                        removedFiles += changes.removedFiles
                        if (sidecarUpdated) updatedSidecars += 1
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    blockedSessions += sessionKey
                    failedItems += 1
                    Timber.w(error, "Failed to refresh local library artwork from origin")
                }
                progress(
                    LocalLibraryOriginArtworkProgress(
                        completed = index + 1,
                        total = candidates.size,
                        currentItemName = null,
                    )
                )
            }

            LocalLibraryOriginArtworkSummary(
                refreshedItems = refreshedItems,
                writtenFiles = writtenFiles,
                removedFiles = removedFiles,
                updatedSidecars = updatedSidecars,
                skippedItems = skippedItems,
                failedItems = failedItems,
            ).also { summary ->
                if (
                    corruptedItemIds.isNotEmpty() ||
                        summary.writtenFiles > 0 ||
                        summary.removedFiles > 0 ||
                        summary.failedItems > 0
                ) {
                    Timber.i(
                        "Local library origin artwork refresh finished: refreshed=%d, written=%d, removed=%d, sidecars=%d, skipped=%d, failed=%d",
                        summary.refreshedItems,
                        summary.writtenFiles,
                        summary.removedFiles,
                        summary.updatedSidecars,
                        summary.skippedItems,
                        summary.failedItems,
                    )
                }
            }
        }

    private fun LocalMediaFileRecord.refreshCandidate(
        rootsById: Map<UUID, LocalLibraryRootRecord>,
        forceRefreshItemArtwork: Boolean,
    ): LocalArtworkRefreshCandidate? {
        val root = rootsById[rootRegistryId]?.takeIf { it.enabled && it.lastKnownAvailable && it.writable }
            ?: return null
        if (!needsOriginArtworkRefresh(root, forceRefreshItemArtwork)) return null
        val origin =
            LocalLibraryArtworkOrigin(
                serverId = identity.serverId?.takeIf { it.isNotBlank() } ?: return null,
                userId = ownerUserId?.toUuidOrNull() ?: return null,
                itemId = identity.jellyfinItemId?.toUuidOrNull() ?: return null,
                sourceId = identity.jellyfinSourceId,
                itemName = title.name,
                mediaKind = mediaKind,
            )
        return LocalArtworkRefreshCandidate(
            mediaFile = this,
            root = root,
            origin = origin,
            overwriteExistingItemArtwork = forceRefreshItemArtwork,
        )
    }

    private fun writeResolvedArtwork(
        root: LocalLibraryRootRecord,
        mediaFile: LocalMediaFileRecord,
        resolved: LocalLibraryResolvedArtwork,
        processedTargets: MutableSet<String>,
        overwriteExistingItemArtwork: Boolean,
    ): ArtworkFileChanges {
        if (mediaFile.mediaKind == LocalMediaKind.MOVIE) {
            return writeImageSet(
                root,
                LocalLibraryArtworkPaths.itemImagesDirectory(mediaFile.relativePath, mediaFile.mediaKind),
                resolved.itemImages,
                processedTargets,
                overwriteExisting = overwriteExistingItemArtwork,
                removeMissingExisting = overwriteExistingItemArtwork,
            )
        }

        return writeImageSet(
            root,
            LocalLibraryArtworkPaths.itemImagesDirectory(mediaFile.relativePath, mediaFile.mediaKind),
            resolved.itemImages,
            processedTargets,
            overwriteExisting = overwriteExistingItemArtwork,
            removeMissingExisting = overwriteExistingItemArtwork,
        ) + writeImageSet(
            root,
            LocalLibraryArtworkPaths.seasonImagesDirectory(mediaFile.relativePath),
            resolved.seasonImages,
            processedTargets,
            overwriteExisting = false,
        ) + writeImageSet(
            root,
            LocalLibraryArtworkPaths.showImagesDirectory(mediaFile.relativePath),
            resolved.showImages,
            processedTargets,
            overwriteExisting = false,
        )
    }

    private fun LocalMediaFileRecord.needsOriginArtworkRefresh(
        root: LocalLibraryRootRecord,
        forceRefreshItemArtwork: Boolean,
    ): Boolean =
        when (mediaKind) {
            LocalMediaKind.MOVIE ->
                forceRefreshItemArtwork ||
                    !hasDisplayImage(root, LocalLibraryArtworkPaths.itemImagesDirectory(relativePath, mediaKind))

            LocalMediaKind.EPISODE ->
                forceRefreshItemArtwork ||
                    !hasDisplayImage(root, LocalLibraryArtworkPaths.itemImagesDirectory(relativePath, mediaKind)) ||
                    !hasDisplayImage(root, LocalLibraryArtworkPaths.seasonImagesDirectory(relativePath)) ||
                    !hasDisplayImage(root, LocalLibraryArtworkPaths.showImagesDirectory(relativePath))
        }

    private fun List<LocalMediaFileRecord>.duplicatedItemArtworkLocalItemIds(
        rootsById: Map<UUID, LocalLibraryRootRecord>
    ): Set<String> {
        val entries =
            mapNotNull { mediaFile ->
                val root =
                    rootsById[mediaFile.rootRegistryId]
                        ?.takeIf { it.enabled && it.lastKnownAvailable && it.writable }
                        ?: return@mapNotNull null
                val directory =
                    LocalLibraryArtworkPaths.itemImagesDirectory(
                        mediaFile.relativePath,
                        mediaFile.mediaKind,
                    )
                val relativePath =
                    firstDisplayImageVariant(root, directory)
                        ?: return@mapNotNull null
                val bytes =
                    fileSystem.readBytes(root, relativePath)
                        ?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                DuplicatedArtworkEntry(
                    localItemId = mediaFile.identity.localItemId,
                    fingerprint = bytes.sha256Fingerprint(),
                )
            }
        return entries
            .groupBy { it.fingerprint }
            .values
            .filter { group -> group.map { it.localItemId }.distinct().size > 1 }
            .flatMap { group -> group.map { it.localItemId } }
            .toSet()
    }

    private fun firstDisplayImageVariant(
        root: LocalLibraryRootRecord,
        directory: String,
    ): String? =
        existingImageVariant(root, directory, "primary")
            ?: existingImageVariant(root, directory, "thumb")
            ?: existingImageVariant(root, directory, "backdrop")

    private fun ByteArray.sha256Fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return "${size}:${digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
    }

    private fun hasDisplayImage(
        root: LocalLibraryRootRecord,
        directory: String,
    ): Boolean =
        existingImageVariant(root, directory, "primary") != null ||
            existingImageVariant(root, directory, "thumb") != null ||
            existingImageVariant(root, directory, "backdrop") != null

    private fun writeImageSet(
        root: LocalLibraryRootRecord,
        directory: String,
        images: LocalLibraryResolvedImageSet,
        processedTargets: MutableSet<String>,
        overwriteExisting: Boolean,
        removeMissingExisting: Boolean = false,
    ): ArtworkFileChanges {
        if (directory.isBlank() || (images.isEmpty && !removeMissingExisting)) return ArtworkFileChanges()
        var changes = ArtworkFileChanges()
        changes += syncImage(root, directory, "primary", images.primary, processedTargets, overwriteExisting, removeMissingExisting)
        changes += syncImage(root, directory, "backdrop", images.backdrop, processedTargets, overwriteExisting, removeMissingExisting)
        changes += syncImage(root, directory, "thumb", images.thumb, processedTargets, overwriteExisting, removeMissingExisting)
        changes += syncImage(root, directory, "logo", images.logo, processedTargets, overwriteExisting, removeMissingExisting)
        return changes
    }

    private fun syncImage(
        root: LocalLibraryRootRecord,
        directory: String,
        baseName: String,
        image: LocalLibraryResolvedImage?,
        processedTargets: MutableSet<String>,
        overwriteExisting: Boolean,
        removeMissingExisting: Boolean,
    ): ArtworkFileChanges {
        if (image != null) return writeImage(root, directory, baseName, image, processedTargets, overwriteExisting)
        if (!removeMissingExisting) return ArtworkFileChanges()
        return ArtworkFileChanges(
            removedFiles = deleteExistingImageVariants(root, directory, baseName, keepRelativePath = null, processedTargets)
        )
    }

    private fun writeImage(
        root: LocalLibraryRootRecord,
        directory: String,
        baseName: String,
        image: LocalLibraryResolvedImage?,
        processedTargets: MutableSet<String>,
        overwriteExisting: Boolean,
    ): ArtworkFileChanges {
        image ?: return ArtworkFileChanges()
        val targetKey = "${root.registryId}:$directory/$baseName"
        if (targetKey in processedTargets) return ArtworkFileChanges()
        processedTargets += targetKey
        if (!overwriteExisting && existingImageVariant(root, directory, baseName) != null) return ArtworkFileChanges()
        val relativePath = "$directory/$baseName.${image.extension}"
        val written = fileSystem.writeBytes(root, relativePath, image.bytes, image.mimeType)
        if (!written) return ArtworkFileChanges()
        val removed = deleteExistingImageVariants(root, directory, baseName, relativePath)
        return ArtworkFileChanges(writtenFiles = 1, removedFiles = removed)
    }

    private fun deleteExistingImageVariants(
        root: LocalLibraryRootRecord,
        directory: String,
        baseName: String,
        keepRelativePath: String?,
        processedTargets: MutableSet<String>? = null,
    ): Int {
        val targetKey = "${root.registryId}:$directory/$baseName"
        if (processedTargets != null) {
            if (targetKey in processedTargets) return 0
            processedTargets += targetKey
        }
        return IMAGE_EXTENSIONS
            .map { extension -> "$directory/$baseName.$extension" }
            .filter { it != keepRelativePath }
            .count { relativePath ->
                fileSystem.exists(root, relativePath) && fileSystem.delete(root, relativePath)
            }
    }

    private fun existingImageVariant(
        root: LocalLibraryRootRecord,
        directory: String,
        baseName: String,
    ): String? =
        IMAGE_EXTENSIONS.firstNotNullOfOrNull { extension ->
            "$directory/$baseName.$extension".takeIf { relativePath ->
                fileSystem.exists(root, relativePath)
            }
        }

    private fun updateSidecarParentIdentity(
        root: LocalLibraryRootRecord,
        mediaFile: LocalMediaFileRecord,
        resolved: LocalLibraryResolvedArtwork,
    ): Boolean {
        val sidecarPath = mediaFile.sidecarRelativePath ?: return false
        if (resolved.seriesId.isNullOrBlank() && resolved.seasonId.isNullOrBlank()) return false
        val raw = fileSystem.readText(root, sidecarPath) ?: return false
        val sidecar = sidecarReader.readMediaSidecar(raw).sidecar ?: return false
        val identity = sidecar.identity ?: AfinitySidecarIdentity()
        val updatedIdentity =
            identity.copy(
                seriesId = identity.seriesId ?: resolved.seriesId,
                seasonId = identity.seasonId ?: resolved.seasonId,
            )
        if (updatedIdentity == identity) return false
        return fileSystem.writeText(
            root,
            sidecarPath,
            sidecarReader.encodeMediaSidecar(sidecar.copy(identity = updatedIdentity)),
        )
    }

    private data class LocalArtworkRefreshCandidate(
        val mediaFile: LocalMediaFileRecord,
        val root: LocalLibraryRootRecord,
        val origin: LocalLibraryArtworkOrigin,
        val overwriteExistingItemArtwork: Boolean,
    )

    private data class DuplicatedArtworkEntry(
        val localItemId: String,
        val fingerprint: String,
    )

    private data class ArtworkFileChanges(
        val writtenFiles: Int = 0,
        val removedFiles: Int = 0,
    ) {
        val hasChanges: Boolean
            get() = writtenFiles > 0 || removedFiles > 0

        operator fun plus(other: ArtworkFileChanges): ArtworkFileChanges =
            ArtworkFileChanges(
                writtenFiles = writtenFiles + other.writtenFiles,
                removedFiles = removedFiles + other.removedFiles,
            )
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "gif")
    }
}

@Singleton
class JellyfinLocalLibraryRemoteArtworkResolver
@Inject
constructor(private val sessionRestoreResolver: SessionRestoreResolver) :
    LocalLibraryRemoteArtworkResolver {
    override suspend fun resolve(origin: LocalLibraryArtworkOrigin): LocalLibraryResolvedArtwork? {
        val session =
            when (
                val result =
                    sessionRestoreResolver.restore(
                        serverId = origin.serverId,
                        userId = origin.userId,
                    )
            ) {
                is SessionRestoreResult.Restored -> result.session
                is SessionRestoreResult.Failed -> error(result.message)
                is SessionRestoreResult.Paused -> error(result.message)
            }

        val item = fetchItem(session.apiClient, origin.userId, origin.itemId) ?: return null
        val baseUrl = session.apiClient.baseUrl ?: session.serverUrl
        return when (item.type) {
            BaseItemKind.MOVIE -> {
                val movie = item.toAfinityMovie(baseUrl)
                LocalLibraryResolvedArtwork(
                    itemImages =
                        downloadImageSet(
                            apiClient = session.apiClient,
                            okHttpClient = session.okHttpClient,
                            images = movie.images,
                        )
                )
            }
            BaseItemKind.EPISODE -> {
                val episode = item.toAfinityEpisode(baseUrl) ?: return null
                val showImages =
                    fetchItem(session.apiClient, origin.userId, episode.seriesId)
                        ?.toAfinityShow(baseUrl)
                        ?.images
                val seasonImages =
                    fetchItem(session.apiClient, origin.userId, episode.seasonId)
                        ?.toAfinitySeason(baseUrl)
                        ?.images
                LocalLibraryResolvedArtwork(
                    itemImages =
                        downloadImageSet(
                            apiClient = session.apiClient,
                            okHttpClient = session.okHttpClient,
                            images = episode.images,
                        ),
                    showImages =
                        showImages?.let {
                            downloadImageSet(
                                apiClient = session.apiClient,
                                okHttpClient = session.okHttpClient,
                                images = it.copy(thumb = null),
                            )
                        } ?: LocalLibraryResolvedImageSet(),
                    seasonImages =
                        seasonImages?.let {
                            downloadImageSet(
                                apiClient = session.apiClient,
                                okHttpClient = session.okHttpClient,
                                images = it.copy(thumb = null, logo = null),
                            )
                        } ?: LocalLibraryResolvedImageSet(),
                    seriesId = episode.seriesId.toString(),
                    seasonId = episode.seasonId.toString(),
                )
            }
            else -> null
        }
    }

    private suspend fun fetchItem(
        apiClient: ApiClient,
        userId: UUID,
        itemId: UUID,
    ): BaseItemDto? =
        ItemsApi(apiClient)
            .getItems(
                userId = userId,
                ids = listOf(itemId),
                fields =
                    listOf(
                        ItemFields.MEDIA_SOURCES,
                        ItemFields.MEDIA_STREAMS,
                        ItemFields.OVERVIEW,
                        ItemFields.GENRES,
                        ItemFields.PEOPLE,
                        ItemFields.TAGLINES,
                        ItemFields.CHAPTERS,
                    ),
                enableImages = true,
                enableUserData = true,
            )
            .content
            .items
            .firstOrNull()

    private suspend fun downloadImageSet(
        apiClient: ApiClient,
        okHttpClient: okhttp3.OkHttpClient,
        images: AfinityImages,
    ): LocalLibraryResolvedImageSet =
        LocalLibraryResolvedImageSet(
            primary = downloadImage(apiClient, okHttpClient, images.primary),
            backdrop = downloadImage(apiClient, okHttpClient, images.backdrop),
            thumb = downloadImage(apiClient, okHttpClient, images.thumb),
            logo = downloadImage(apiClient, okHttpClient, images.logo),
        )

    private suspend fun downloadImage(
        apiClient: ApiClient,
        okHttpClient: okhttp3.OkHttpClient,
        uri: Uri?,
    ): LocalLibraryResolvedImage? {
        if (!uri.isDownloadableRemoteUri()) return null
        val request =
            Request.Builder()
                .url(uri.toString())
                .header("X-Emby-Token", apiClient.accessToken ?: "")
                .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body.bytes()
            if (bytes.isEmpty()) return null
            val contentType = response.header("Content-Type").orEmpty()
            val extension = imageExtension(contentType)
            return LocalLibraryResolvedImage(
                bytes = bytes,
                mimeType = contentType.ifBlank { extension.imageMimeType() },
                extension = extension,
            )
        }
    }

    private fun Uri?.isDownloadableRemoteUri(): Boolean {
        val scheme = this?.scheme ?: return false
        return scheme == "http" || scheme == "https"
    }

    private fun imageExtension(contentType: String?): String =
        when {
            contentType?.contains("png", ignoreCase = true) == true -> "png"
            contentType?.contains("webp", ignoreCase = true) == true -> "webp"
            contentType?.contains("gif", ignoreCase = true) == true -> "gif"
            else -> "jpg"
        }
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private fun String.imageMimeType(): String =
    when (this) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }
