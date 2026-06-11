package com.makd.afinity.data.local

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalLibraryPhysicalDeletionResult(
    val deletedPaths: List<String>,
    val failedPaths: List<String>,
    val scanSummary: LocalLibraryScanSummary?,
)

@Singleton
class LocalLibraryMediaDeletionService
@Inject
constructor(
    private val rootStore: LocalLibraryRootStore,
    private val indexRepository: LocalLibraryIndexRepository,
    private val fileSystem: LocalLibraryFileSystem,
    private val scanner: LocalLibraryScanner,
    private val deletionPolicy: LocalLibraryDeletionPolicy,
) {
    suspend fun deletePhysicalMedia(
        mediaFileId: UUID,
        confirmed: Boolean,
        visibilityContext: LocalLibraryVisibilityContext,
    ): Result<LocalLibraryPhysicalDeletionResult> =
        withContext(Dispatchers.IO) {
            deletionPolicy.deletePhysicalMedia(confirmed)
            val mediaFile =
                indexRepository.findByMediaFileId(mediaFileId)
                    ?: return@withContext Result.failure(IllegalArgumentException("Local media not found"))
            val root =
                rootStore.getRoots().firstOrNull { it.registryId == mediaFile.rootRegistryId }
                    ?: return@withContext Result.failure(IllegalArgumentException("Local root not configured"))

            val paths = deletionPaths(root, mediaFile)
            val deleted = mutableListOf<String>()
            val failed = mutableListOf<String>()
            paths.forEach { relativePath ->
                if (fileSystem.delete(root, relativePath)) {
                    deleted += relativePath
                } else {
                    failed += relativePath
                }
            }

            if (failed.isNotEmpty()) {
                return@withContext Result.success(
                    LocalLibraryPhysicalDeletionResult(deleted, failed, scanSummary = null)
                )
            }

            val scanSummary = scanner.scanRoot(root, visibilityContext)
            Result.success(LocalLibraryPhysicalDeletionResult(deleted, emptyList(), scanSummary))
        }

    private fun deletionPaths(
        root: LocalLibraryRootRecord,
        mediaFile: LocalMediaFileRecord,
    ): List<String> {
        val directory = mediaFile.relativePath.substringBeforeLast('/', "")
        val baseName = mediaFile.relativePath.substringAfterLast('/').substringBeforeLast('.')
        val paths = linkedSetOf(mediaFile.relativePath)
        mediaFile.sidecarRelativePath?.let(paths::add)

        fileSystem.list(root, directory).forEach { node ->
            if (node.isDirectory) return@forEach
            val extension = node.name.substringAfterLast('.', "").lowercase()
            val isCompanion =
                (node.name == "$baseName.$extension" || node.name.startsWith("$baseName.")) &&
                    extension in
                        setOf(
                            "nfo",
                            "srt",
                            "vtt",
                            "ass",
                            "ssa",
                            "jpg",
                            "jpeg",
                            "png",
                            "webp",
                        )
            if (isCompanion) paths += node.relativePath
        }

        if (mediaFile.mediaKind == LocalMediaKind.MOVIE) {
            listOf("movie.nfo", "poster.jpg", "fanart.jpg", "poster.png", "fanart.png").forEach {
                paths += listOf(directory, it).filter { segment -> segment.isNotBlank() }.joinToString("/")
            }
        }

        return paths.toList()
    }
}
