package com.makd.afinity.data.local

import java.util.UUID

class LocalLibraryScanner(
    private val fileSystem: LocalLibraryFileSystem,
    private val sidecarReader: LocalLibrarySidecarReader,
    private val indexRepository: LocalLibraryIndexRepository,
    private val pathPolicy: LocalLibraryPathPolicy = LocalLibraryPathPolicy(),
) {
    @Suppress("UNUSED_PARAMETER")
    fun scanRoot(
        root: LocalLibraryRootRecord,
        visibilityContext: LocalLibraryVisibilityContext =
            LocalLibraryVisibilityContext(currentUserId = null, kidModeEnabled = false, parentUnlocked = false),
        shouldCancel: () -> Boolean = { false },
    ): LocalLibraryScanSummary {
        if (!root.enabled) {
            indexRepository.replaceRootScan(root, emptyList())
            return LocalLibraryScanSummary(root.registryId, 0, 0, 0, 0, 0, 0, false, emptyList())
        }
        if (!root.lastKnownAvailable) {
            indexRepository.markRootUnavailable(root)
            return LocalLibraryScanSummary(root.registryId, 0, 0, 0, 0, 0, 0, false, listOf("Root unavailable"))
        }

        val warnings = mutableListOf<String>()
        val files = mutableListOf<LocalMediaFileRecord>()
        val importJobs = mutableListOf<LocalMediaImportJobRecord>()
        walk(root).forEach { node ->
            if (shouldCancel()) {
                return LocalLibraryScanSummary(
                    rootId = root.registryId,
                    discoveredFiles = files.size,
                    importedItems = files.size,
                    updatedItems = files.size,
                    unavailableItems = 0,
                    duplicateGroups = indexRepository.duplicateGroupCount(),
                    parseWarnings = warnings.size,
                    cancelled = true,
                    errors = warnings,
                )
            }
            if (node.name.isStagingFile()) {
                importJobs += node.toImportPendingJob(root, "Staging file is not importable until finish verifies final media")
                return@forEach
            }
            if (!node.isMediaFile()) return@forEach
            val sidecarPath = pathPolicy.sidecarPathForMedia(node.relativePath)
            val sidecarResult =
                fileSystem.readText(root, sidecarPath)?.let(sidecarReader::readMediaSidecar)
            sidecarResult?.warnings?.let(warnings::addAll)
            val record = buildRecord(root, node, sidecarPath.takeIf { sidecarResult?.sidecar != null }, sidecarResult?.sidecar)
            if (record != null) {
                files += record.copy(visibleByDefault = true)
            } else {
                val warning = "Unable to parse ${node.relativePath}"
                warnings += warning
                importJobs += node.toImportPendingJob(root, warning)
            }
        }

        indexRepository.replaceRootScan(root, files, importJobs)
        val duplicateGroups = indexRepository.duplicateGroupCount()
        return LocalLibraryScanSummary(
            rootId = root.registryId,
            discoveredFiles = files.size,
            importedItems = files.size,
            updatedItems = files.size,
            unavailableItems = 0,
            duplicateGroups = duplicateGroups,
            parseWarnings = warnings.size,
            cancelled = false,
            errors = warnings,
        )
    }

    private fun walk(root: LocalLibraryRootRecord): Sequence<LocalLibraryNode> =
        sequence {
            val pending = ArrayDeque<String>()
            pending += ""
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                fileSystem.list(root, current).forEach { node ->
                    if (node.isDirectory) {
                        pending += node.relativePath
                    } else {
                        yield(node)
                    }
                }
            }
        }

    private fun LocalLibraryNode.toImportPendingJob(
        root: LocalLibraryRootRecord,
        warning: String,
    ): LocalMediaImportJobRecord =
        LocalMediaImportJobRecord(
            jobId =
                UUID.nameUUIDFromBytes("${root.registryId}:$relativePath:import-pending".toByteArray())
                    .toString(),
            rootRegistryId = root.registryId,
            relativePath = relativePath,
            mediaFileId = null,
            state = LocalMediaImportState.IMPORT_PENDING,
            lastError = warning,
            updatedAt = System.currentTimeMillis(),
        )

    private fun buildRecord(
        root: LocalLibraryRootRecord,
        node: LocalLibraryNode,
        sidecarPath: String?,
        sidecar: AfinityMediaSidecar?,
    ): LocalMediaFileRecord? {
        val mediaKind = sidecar?.mediaKind?.toMediaKind() ?: parseMediaKind(node.relativePath) ?: return null
        val title =
            sidecar?.titles?.toLocalTitle(mediaKind)
                ?: readNfoTitle(root, node.relativePath, mediaKind)
                ?: parseTitle(node.relativePath, mediaKind)
        val fingerprint =
            sidecar?.localIdentity?.fingerprint?.let {
                LocalMediaFingerprint(it.strategy, it.value)
            } ?: LocalMediaFingerprint(
                strategy = "size-mtime-path-v1",
                value = "${node.sizeBytes}:${node.modifiedAt}:${node.relativePath}",
            )
        val stableRootId = sidecar?.localIdentity?.stableRootUuid() ?: root.stableRootId
        val localItemId =
            sidecar?.localIdentity?.localItemId?.takeIf { it.isNotBlank() }
                ?: UUID.nameUUIDFromBytes(
                    "${stableRootId ?: root.registryId}:${node.relativePath}:${fingerprint.value}"
                        .toByteArray()
                ).toString()
        val identity =
            LocalMediaIdentity(
                localItemId = localItemId,
                serverId = sidecar?.server?.serverId,
                jellyfinItemId = sidecar?.identity?.itemId,
                jellyfinSourceId = sidecar?.identity?.sourceId,
                providerIds = sidecar?.identity?.providerIds.orEmpty(),
                stableRootId = stableRootId,
                fingerprint = fingerprint,
            )
        return LocalMediaFileRecord(
            mediaFileId =
                UUID.nameUUIDFromBytes("${root.registryId}:${node.relativePath}:${identity.durableKey}".toByteArray()),
            rootRegistryId = root.registryId,
            stableRootId = stableRootId,
            relativePath = node.relativePath,
            sidecarRelativePath = sidecarPath,
            ownerUserId = sidecar?.user?.userId,
            mediaKind = mediaKind,
            identity = identity,
            title = title,
            sizeBytes = node.sizeBytes,
            modifiedAt = node.modifiedAt,
            container = sidecar?.mediaFile?.container ?: node.relativePath.substringAfterLast('.', ""),
            runtimeTicks = sidecar?.mediaFile?.runtimeTicks,
        )
    }

    private fun LocalLibraryNode.isMediaFile(): Boolean {
        if (name.isStagingFile()) return false
        return name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
    }

    private fun String.isStagingFile(): Boolean {
        val lower = lowercase()
        return lower.endsWith(".part") ||
            lower.endsWith(".download") ||
            lower.endsWith(".refresh") ||
            lower.contains(".part.") ||
            lower.contains(".download.")
    }

    private fun String.toMediaKind(): LocalMediaKind? =
        when (lowercase()) {
            "movie" -> LocalMediaKind.MOVIE
            "episode" -> LocalMediaKind.EPISODE
            else -> null
        }

    private fun parseMediaKind(relativePath: String): LocalMediaKind? =
        when {
            relativePath.startsWith("Movies/") -> LocalMediaKind.MOVIE
            relativePath.startsWith("Shows/") -> LocalMediaKind.EPISODE
            else -> null
        }

    private fun AfinitySidecarTitles.toLocalTitle(mediaKind: LocalMediaKind): LocalLibraryTitle =
        LocalLibraryTitle(
            name = name ?: "Untitled",
            showName = showName,
            year = year,
            seasonNumber = seasonNumber.takeIf { mediaKind == LocalMediaKind.EPISODE },
            episodeNumber = episodeNumber.takeIf { mediaKind == LocalMediaKind.EPISODE },
        )

    private fun parseTitle(relativePath: String, mediaKind: LocalMediaKind): LocalLibraryTitle {
        val baseName = relativePath.substringAfterLast('/').substringBeforeLast('.')
        if (mediaKind == LocalMediaKind.EPISODE) {
            EPISODE_FILE_REGEX.matchEntire(baseName)?.let { match ->
                return LocalLibraryTitle(
                    name = pathPolicy.sanitizePathSegment(match.groupValues[4]),
                    showName = pathPolicy.sanitizePathSegment(match.groupValues[1]),
                    seasonNumber = match.groupValues[2].toIntOrNull(),
                    episodeNumber = match.groupValues[3].toIntOrNull(),
                )
            }
        }
        val movie = MOVIE_FILE_REGEX.matchEntire(baseName)
        return LocalLibraryTitle(
            name = pathPolicy.sanitizePathSegment(movie?.groupValues?.get(1) ?: baseName),
            year = movie?.groupValues?.get(2)?.toIntOrNull(),
        )
    }

    private fun readNfoTitle(
        root: LocalLibraryRootRecord,
        relativePath: String,
        mediaKind: LocalMediaKind,
    ): LocalLibraryTitle? {
        val nfoPath =
            when (mediaKind) {
                LocalMediaKind.MOVIE ->
                    relativePath.substringBeforeLast('/', "") + "/movie.nfo"

                LocalMediaKind.EPISODE ->
                    relativePath.substringBeforeLast('.', relativePath) + ".nfo"
            }.trimStart('/')
        val raw = fileSystem.readText(root, nfoPath) ?: return null
        val title = raw.firstTag("title")
        val year = raw.firstTag("year")?.toIntOrNull()
        if (mediaKind == LocalMediaKind.MOVIE) {
            return LocalLibraryTitle(name = title ?: parseTitle(relativePath, mediaKind).name, year = year)
        }
        return LocalLibraryTitle(
            name = title ?: parseTitle(relativePath, mediaKind).name,
            showName = raw.firstTag("showtitle") ?: parseTitle(relativePath, mediaKind).showName,
            seasonNumber = raw.firstTag("season")?.toIntOrNull() ?: parseTitle(relativePath, mediaKind).seasonNumber,
            episodeNumber = raw.firstTag("episode")?.toIntOrNull() ?: parseTitle(relativePath, mediaKind).episodeNumber,
        )
    }

    private fun String.firstTag(tagName: String): String? =
        Regex("<$tagName>(.*?)</$tagName>", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun AfinitySidecarLocalIdentity.stableRootUuid(): UUID? =
        (stableRootId ?: rootId)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm")
        val EPISODE_FILE_REGEX = Regex("""(.+) - S(\d{1,2})E(\d{1,3}) - (.+)""")
        val MOVIE_FILE_REGEX = Regex("""(.+) \((\d{4})\)""")
    }
}
