package com.makd.afinity.data.local

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalPlaybackSourceRepository
@Inject
constructor(
    private val rootStore: LocalLibraryRootStore,
    private val fileSystem: LocalLibraryFileSystem,
    private val indexRepository: LocalLibraryIndexRepository,
    private val visibilityRepository: LocalMediaVisibilityRepository,
) {
    suspend fun resolve(request: LocalPlaybackResolutionRequest): LocalPlaybackResolution {
        val roots = rootStore.getRoots()
        val resolution =
            LocalPlaybackSourceResolver(
                roots = { roots },
                fileSystem = fileSystem,
                indexRepository = indexRepository,
            )
                .resolve(request)
        if (resolution is LocalPlaybackResolution.Resolved) {
            val profileUserId = request.visibilityContext.currentUserId
            if (
                profileUserId != null &&
                    resolution.mediaFile.identity.localItemId in
                        visibilityRepository.hiddenLocalItemIds(profileUserId)
            ) {
                return LocalPlaybackResolution.Unavailable("local_media_not_visible")
            }
        }
        return resolution
    }
}
