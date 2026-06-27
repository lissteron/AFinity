package com.makd.afinity.data.local

import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.models.download.DownloadStatus
import com.makd.afinity.data.storage.PortableMediaArtworkPaths
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalLibraryArtworkBackfillSummary(
    val writtenFiles: Int,
    val skippedItems: Int,
    val failedItems: Int,
)

@Singleton
class LocalLibraryArtworkBackfillService
@Inject
constructor(
    private val rootStore: LocalLibraryRootStore,
    private val indexRepository: LocalLibraryIndexRepository,
    private val fileSystem: LocalLibraryFileSystem,
    private val sourceRootProvider: LocalLibraryArtworkSourceRootProvider,
) {
    suspend fun backfillDownloads(
        downloads: List<DownloadDto>
    ): LocalLibraryArtworkBackfillSummary =
        withContext(Dispatchers.IO) {
            val roots = rootStore.getRoots()
            val rootsById = roots.associateBy { it.registryId }
            val fileBackedSourceRoots =
                (roots
                        .filter { it.enabled && it.lastKnownAvailable && it.isFileBackedRoot() }
                        .map { File(it.uriOrPath) } + sourceRootProvider.sourceRoots())
                    .filter { it.exists() && it.isDirectory }
                    .distinctBy { it.absoluteFile.absolutePath }
            val downloadsByItemId =
                downloads
                    .filter {
                        it.status == DownloadStatus.COMPLETED &&
                            it.itemType.uppercase() in setOf("MOVIE", "EPISODE")
                    }
                    .groupBy { it.itemId.toString() }
            val processedTargets = mutableSetOf<String>()

            var writtenFiles = 0
            var skippedItems = 0
            var failedItems = 0

            indexRepository.allMediaFiles().forEach { mediaFile ->
                val root = rootsById[mediaFile.rootRegistryId]
                val download = mediaFile.matchingDownload(downloadsByItemId)
                if (root == null || !root.writable || download == null) {
                    skippedItems += 1
                    return@forEach
                }

                runCatching {
                        backfillMediaFile(
                            root = root,
                            mediaFile = mediaFile,
                            download = download,
                            sourceRoots = fileBackedSourceRoots,
                            processedTargets = processedTargets,
                        )
                    }
                    .onSuccess { count ->
                        if (count == 0) skippedItems += 1 else writtenFiles += count
                    }
                    .onFailure { failedItems += 1 }
            }

            LocalLibraryArtworkBackfillSummary(
                writtenFiles = writtenFiles,
                skippedItems = skippedItems,
                failedItems = failedItems,
            )
        }

    private fun backfillMediaFile(
        root: LocalLibraryRootRecord,
        mediaFile: LocalMediaFileRecord,
        download: DownloadDto,
        sourceRoots: List<File>,
        processedTargets: MutableSet<String>,
    ): Int {
        val mediaDir = mediaFile.relativePath.substringBeforeLast('/', "")
        if (mediaDir.isBlank()) return 0
        val itemImageDirs = download.itemImageDirectories(sourceRoots)
        if (mediaFile.mediaKind == LocalMediaKind.MOVIE) {
            return copyImageSet(
                root,
                LocalLibraryArtworkPaths.itemImagesDirectory(mediaFile.relativePath, mediaFile.mediaKind),
                itemImageDirs,
                processedTargets,
            )
        }

        val seasonImageDirs = download.seasonImageDirectories(sourceRoots)
        val showImageDirs = download.showImageDirectories(sourceRoots)
        val itemWritten =
            copyImageSet(
                root = root,
                targetDirectory =
                    LocalLibraryArtworkPaths.itemImagesDirectory(
                        mediaFile.relativePath,
                        mediaFile.mediaKind,
                    ),
                sourceDirectories = itemImageDirs,
                processedTargets = processedTargets,
            )
        val seasonWritten =
            copyImageSet(
                root = root,
                targetDirectory = LocalLibraryArtworkPaths.seasonImagesDirectory(mediaFile.relativePath),
                sourceDirectories = seasonImageDirs,
                processedTargets = processedTargets,
            )
        val showWritten =
            copyImageSet(
                root = root,
                targetDirectory = LocalLibraryArtworkPaths.showImagesDirectory(mediaFile.relativePath),
                sourceDirectories = showImageDirs,
                processedTargets = processedTargets,
            )
        return itemWritten + seasonWritten + showWritten
    }

    private fun LocalMediaFileRecord.matchingDownload(
        downloadsByItemId: Map<String, List<DownloadDto>>
    ): DownloadDto? {
        val candidates =
            listOfNotNull(identity.jellyfinItemId, identity.localItemId)
                .flatMap { downloadsByItemId[it].orEmpty() }
        return candidates.firstOrNull { download ->
            identity.serverId == null || identity.serverId == download.serverId
        } ?: candidates.firstOrNull()
    }

    private fun DownloadDto.itemImageDirectories(sourceRoots: List<File>): List<File> {
        val mediaRoot = filePath?.localItemImagesDirectoryFromMediaPath(itemType)
        val folderRoots =
            folderPath
                ?.takeIf { it.isNotBlank() }
                ?.let { folder -> sourceRoots.map { root -> File(root, "$folder/images") } }
                .orEmpty()
        return (listOfNotNull(mediaRoot) + folderRoots).existingDistinctDirectories()
    }

    private fun DownloadDto.showImageDirectories(sourceRoots: List<File>): List<File> {
        val series = seriesId?.takeIf { it.isNotBlank() } ?: return emptyList()
        return sourceRoots
            .map { root -> File(root, "$serverId/shows/$series/images") }
            .existingDistinctDirectories()
    }

    private fun DownloadDto.seasonImageDirectories(sourceRoots: List<File>): List<File> {
        val series = seriesId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val season = seasonNumber ?: return emptyList()
        return sourceRoots
            .map { root -> File(root, "$serverId/shows/$series/seasons/$season/images") }
            .existingDistinctDirectories()
    }

    private fun copyImageSet(
        root: LocalLibraryRootRecord,
        targetDirectory: String,
        sourceDirectories: List<File>,
        processedTargets: MutableSet<String>,
    ): Int {
        if (targetDirectory.isBlank() || sourceDirectories.isEmpty()) return 0
        var written = 0
        written +=
            copyFirstImage(
                root,
                targetDirectory,
                "primary",
                sourceDirectories,
                "primary",
                "poster",
                "folder",
                "cover",
                processedTargets = processedTargets,
            )
        written +=
            copyFirstImage(
                root,
                targetDirectory,
                "backdrop",
                sourceDirectories,
                "backdrop",
                "fanart",
                "landscape",
                processedTargets = processedTargets,
            )
        written +=
            copyFirstImage(
                root,
                targetDirectory,
                "thumb",
                sourceDirectories,
                "thumb",
                "thumbnail",
                processedTargets = processedTargets,
            )
        written +=
            copyFirstImage(
                root,
                targetDirectory,
                "logo",
                sourceDirectories,
                "logo",
                "clearlogo",
                processedTargets = processedTargets,
            )
        return written
    }

    private fun copyFirstImage(
        root: LocalLibraryRootRecord,
        targetDirectory: String,
        targetBaseName: String,
        sourceDirectories: List<File>,
        vararg sourceBaseNames: String,
        processedTargets: MutableSet<String>,
    ): Int {
        val targetKey = "${root.registryId}:$targetDirectory/$targetBaseName"
        if (targetKey in processedTargets) return 0
        if (existingImageVariant(root, targetDirectory, targetBaseName) != null) {
            processedTargets += targetKey
            return 0
        }
        val sourceFile = firstImage(sourceDirectories, sourceBaseNames.toSet()) ?: return 0
        processedTargets += targetKey
        val extension = sourceFile.extension.lowercase().ifBlank { "jpg" }
        val bytes = sourceFile.readBytes()
        val targetRelativePath = "$targetDirectory/$targetBaseName.$extension"
        val written =
            fileSystem.writeBytes(
                root = root,
                relativePath = targetRelativePath,
                bytes = bytes,
                mimeType = extension.imageMimeType(),
            )
        if (!written) return 0
        deleteExistingImageVariants(root, targetDirectory, targetBaseName, targetRelativePath)
        return 1
    }

    private fun firstImage(
        directories: List<File>,
        baseNames: Set<String>,
    ): File? =
        directories.firstNotNullOfOrNull { directory ->
            directory
                .listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                ?.sortedBy { it.name }
                ?.firstOrNull { it.name.substringBeforeLast('.').lowercase() in baseNames }
        }

    private fun deleteExistingImageVariants(
        root: LocalLibraryRootRecord,
        directory: String,
        baseName: String,
        keepRelativePath: String,
    ) {
        fileSystem
            .list(root, directory)
            .filter {
                !it.isDirectory &&
                    it.name.substringBeforeLast('.').equals(baseName, ignoreCase = true) &&
                    it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS &&
                    it.relativePath != keepRelativePath
            }
            .forEach { fileSystem.delete(root, it.relativePath) }
    }

    private fun existingImageVariant(
        root: LocalLibraryRootRecord,
        directory: String,
        baseName: String,
    ): LocalLibraryNode? =
        fileSystem
            .list(root, directory)
            .firstOrNull {
                !it.isDirectory &&
                    it.name.substringBeforeLast('.').equals(baseName, ignoreCase = true) &&
                    it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
            }

    private fun String.localItemImagesDirectoryFromMediaPath(itemType: String): File? {
        if (startsWith("content://")) return null
        return PortableMediaArtworkPaths.itemImagesDirectoryForMediaFile(
            File(this),
            isEpisode = itemType.equals("EPISODE", ignoreCase = true),
        )
    }

    private fun List<File>.existingDistinctDirectories(): List<File> =
        filter { it.exists() && it.isDirectory }.distinctBy { it.absoluteFile.absolutePath }

    private fun LocalLibraryRootRecord.isFileBackedRoot(): Boolean =
        kind != LocalLibraryRootKind.SAF_TREE && !uriOrPath.startsWith("content://")

    private fun String.imageMimeType(): String =
        when (this) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
    }
}
