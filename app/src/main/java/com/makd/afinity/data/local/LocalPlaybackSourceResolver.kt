package com.makd.afinity.data.local

class LocalPlaybackSourceResolver(
    private val roots: () -> List<LocalLibraryRootRecord>,
    private val fileSystem: LocalLibraryFileSystem,
    private val indexRepository: LocalLibraryIndexRepository,
    private val visibilityPolicy: LocalLibraryVisibilityPolicy = LocalLibraryVisibilityPolicy(),
) {
    fun resolve(request: LocalPlaybackResolutionRequest): LocalPlaybackResolution {
        val mediaFile =
            request.mediaFileId?.let(indexRepository::findByMediaFileId)
                ?: request.localItemId?.let(indexRepository::findByLocalItemId)
                ?: return LocalPlaybackResolution.Unavailable("local_media_not_found")
        if (
            !mediaFile.visibleByDefault ||
                !visibilityPolicy.isVisible(mediaFile.ownerUserId, request.visibilityContext)
        ) {
            return LocalPlaybackResolution.Unavailable("local_media_not_visible")
        }
        val root =
            roots().firstOrNull { it.registryId == mediaFile.rootRegistryId }
                ?: return LocalPlaybackResolution.Unavailable("local_root_not_configured")
        if (!root.enabled) return LocalPlaybackResolution.Unavailable("local_root_disabled")
        if (!root.lastKnownAvailable) return LocalPlaybackResolution.Unavailable("local_root_unavailable")
        if (!fileSystem.isReadable(root, mediaFile.relativePath)) {
            return LocalPlaybackResolution.Unavailable("local_media_unreadable")
        }
        return LocalPlaybackResolution.Resolved(
            mediaFile = mediaFile,
            playerUri = fileSystem.playerUri(root, mediaFile.relativePath),
            subtitles = discoverSubtitles(root, mediaFile.relativePath),
        )
    }

    private fun discoverSubtitles(root: LocalLibraryRootRecord, relativePath: String): List<String> {
        val directory = relativePath.substringBeforeLast('/', "")
        val baseName = relativePath.substringAfterLast('/').substringBeforeLast('.')
        return fileSystem
            .list(root, directory)
            .filter { !it.isDirectory && it.name.startsWith(baseName) && it.name.substringAfterLast('.', "").lowercase() in SUBTITLE_EXTENSIONS }
            .map { fileSystem.playerUri(root, it.relativePath) }
    }

    private companion object {
        val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")
    }
}
