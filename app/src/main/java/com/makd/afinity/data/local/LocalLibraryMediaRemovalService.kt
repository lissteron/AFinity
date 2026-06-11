package com.makd.afinity.data.local

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class LocalLibraryMediaRemovalService
@Inject
constructor(
    private val indexRepository: LocalLibraryIndexRepository,
    private val visibilityRepository: LocalMediaVisibilityRepository,
    private val deletionPolicy: LocalLibraryDeletionPolicy,
) {
    suspend fun removeFromLocalLibrary(
        mediaFileId: UUID,
        profileUserId: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            deletionPolicy.removeFromLocalLibrary()
            val mediaFile =
                indexRepository.findByMediaFileId(mediaFileId)
                    ?: return@withContext Result.failure(IllegalArgumentException("Local media not found"))
            visibilityRepository.hideLocalItem(
                localItemId = mediaFile.identity.localItemId,
                profileUserId = profileUserId,
                reason = "USER_REMOVED",
            )
            Result.success(Unit)
        }
}
