package com.makd.afinity.data.local

import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.models.download.DownloadStatus
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LocalLibraryMigrationSummary(
    val migrated: Int,
    val skipped: Int,
    val failed: Int,
    val errors: List<String>,
)

@Singleton
class LocalLibraryMigrationService
@Inject
constructor(
    private val pathPolicy: LocalLibraryPathPolicy,
    private val sidecarReader: LocalLibrarySidecarReader,
) {
    fun migrateLegacyDownloads(
        root: LocalLibraryRootRecord,
        downloads: List<DownloadDto>,
    ): LocalLibraryMigrationSummary {
        if (root.kind == LocalLibraryRootKind.SAF_TREE || root.uriOrPath.startsWith("content://")) {
            return LocalLibraryMigrationSummary(0, downloads.size, 0, emptyList())
        }
        val rootDir = File(root.uriOrPath)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return LocalLibraryMigrationSummary(0, downloads.size, 0, listOf("Root unavailable"))
        }

        var migrated = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        downloads
            .filter { it.status == DownloadStatus.COMPLETED }
            .forEach { download ->
                val plan = download.toLegacyMigrationPlan(rootDir, root.stableRootId)
                if (plan == null) {
                    skipped += 1
                    return@forEach
                }
                runCatching {
                        plan.copyMedia()
                        plan.writeSidecar()
                        plan.writeNfo()
                    }
                    .onSuccess { migrated += 1 }
                    .onFailure { error ->
                        failed += 1
                        errors += "${download.id}: ${error.message ?: error::class.simpleName}"
                    }
            }
        return LocalLibraryMigrationSummary(migrated, skipped, failed, errors)
    }

    private fun DownloadDto.toLegacyMigrationPlan(
        rootDir: File,
        stableRootId: UUID?,
    ): LegacyMigrationPlan? {
        val legacyMedia = findLegacyMediaFile(rootDir) ?: return null
        if (!legacyMedia.absoluteFile.toPath().startsWith(rootDir.absoluteFile.toPath())) return null
        val extension = legacyMedia.extension.ifBlank { "mkv" }
        val targetRelativePath =
            when (itemType.lowercase()) {
                "movie" ->
                    pathPolicy.movieMediaPath(
                        title = itemName,
                        year = releaseYear?.toIntOrNull(),
                        extension = extension,
                    )

                "episode" ->
                    pathPolicy.episodeMediaPath(
                        showTitle = seriesName ?: "Show ${seriesId ?: itemId}",
                        seasonNumber = seasonNumber ?: 0,
                        episodeNumber = episodeNumber ?: 0,
                        episodeTitle = itemName,
                        extension = extension,
                    )

                else -> return null
            }
        val target = File(rootDir, targetRelativePath)
        if (target.absoluteFile == legacyMedia.absoluteFile) return null
        return LegacyMigrationPlan(
            download = this,
            stableRootId = stableRootId,
            legacyMedia = legacyMedia,
            target = target,
            relativePath = targetRelativePath,
        )
    }

    private fun DownloadDto.findLegacyMediaFile(rootDir: File): File? {
        filePath?.let { path ->
            val direct = File(path)
            if (direct.exists() && direct.isFile && direct.parentFile?.name == "media") {
                return direct
            }
        }
        val folder = folderPath?.takeIf { it.isNotBlank() } ?: return null
        val mediaDir = File(rootDir, "$folder/media")
        if (!mediaDir.exists() || !mediaDir.isDirectory) return null
        return mediaDir
            .listFiles()
            ?.firstOrNull { file ->
                file.isFile &&
                    !file.name.endsWith(".download") &&
                    !file.name.endsWith(".part") &&
                    file.extension.lowercase() in VIDEO_EXTENSIONS
            }
    }

    private fun LegacyMigrationPlan.copyMedia() {
        if (target.exists() && target.length() == legacyMedia.length()) return
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        legacyMedia.inputStream().use { input ->
            part.outputStream().use { output -> input.copyTo(output) }
        }
        if (part.length() != legacyMedia.length()) {
            part.delete()
            error("Copied size mismatch")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            error("Failed to finalize copied media")
        }
    }

    private fun LegacyMigrationPlan.writeSidecar() {
        val sidecar = File(target.parentFile, "${target.nameWithoutExtension}.afinity.json")
        val mediaKind = if (download.itemType.equals("episode", ignoreCase = true)) "episode" else "movie"
        val sidecarModel =
            AfinityMediaSidecar(
                schemaVersion = 1,
                mediaKind = mediaKind,
                server = AfinitySidecarServer(serverId = download.serverId),
                user = AfinitySidecarUser(userId = download.userId.toString()),
                identity =
                    AfinitySidecarIdentity(
                        itemId = download.itemId.toString(),
                        sourceId = download.sourceId,
                        seriesId =
                            download.seriesId.takeIf {
                                download.itemType.equals("episode", ignoreCase = true)
                            },
                    ),
                localIdentity =
                    AfinitySidecarLocalIdentity(
                        localItemId = download.itemId.toString(),
                        stableRootId = stableRootId?.toString(),
                        relativePathAtWrite = relativePath,
                        fingerprint =
                            AfinitySidecarFingerprint(
                                strategy = "size-mtime-path-v1",
                                value = "${target.length()}:${target.lastModified()}:$relativePath",
                            ),
                    ),
                titles =
                    AfinitySidecarTitles(
                        name = download.itemName,
                        showName = download.seriesName,
                        year = download.releaseYear?.toIntOrNull(),
                        seasonNumber = download.seasonNumber,
                        episodeNumber = download.episodeNumber,
                    ),
                mediaFile =
                    AfinitySidecarMediaFile(
                        relativePath = relativePath,
                        container = target.extension,
                        sizeBytes = target.length(),
                        runtimeTicks = download.runtimeTicks,
                    ),
                download =
                    AfinitySidecarDownload(
                        qualityMode = download.sourceName,
                        downloadedAt = download.updatedAt,
                        downloadedByAFinity = true,
                    ),
            )
        sidecar.writeText(sidecarReader.encodeMediaSidecar(sidecarModel))
    }

    private fun LegacyMigrationPlan.writeNfo() {
        if (download.itemType.equals("episode", ignoreCase = true)) {
            File(target.parentFile, "${target.nameWithoutExtension}.nfo")
                .writeText(
                    """
                    <episodedetails>
                      <title>${download.itemName.xmlEscaped()}</title>
                      <showtitle>${download.seriesName.orEmpty().xmlEscaped()}</showtitle>
                      <season>${download.seasonNumber ?: 0}</season>
                      <episode>${download.episodeNumber ?: 0}</episode>
                    </episodedetails>
                    """
                        .trimIndent()
                )
        } else {
            File(target.parentFile, "movie.nfo")
                .writeText(
                    """
                    <movie>
                      <title>${download.itemName.xmlEscaped()}</title>
                      <year>${download.releaseYear.orEmpty().xmlEscaped()}</year>
                    </movie>
                    """
                        .trimIndent()
                )
        }
    }

    private fun String.xmlEscaped(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private data class LegacyMigrationPlan(
        val download: DownloadDto,
        val stableRootId: UUID?,
        val legacyMedia: File,
        val target: File,
        val relativePath: String,
    )

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm")
    }
}
