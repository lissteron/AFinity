package com.makd.afinity.data.local

import com.makd.afinity.data.database.dao.LocalLibraryDao
import com.makd.afinity.data.database.entities.LocalMediaVisibilityEntity
import javax.inject.Inject
import javax.inject.Singleton

interface LocalMediaVisibilityRepository {
    fun hiddenLocalItemIds(profileUserId: String): Set<String>

    fun hideLocalItem(
        localItemId: String,
        profileUserId: String,
        reason: String,
    )
}

object NoopLocalMediaVisibilityRepository : LocalMediaVisibilityRepository {
    override fun hiddenLocalItemIds(profileUserId: String): Set<String> = emptySet()

    override fun hideLocalItem(
        localItemId: String,
        profileUserId: String,
        reason: String,
    ) = Unit
}

@Singleton
class RoomLocalMediaVisibilityRepository
@Inject
constructor(private val dao: LocalLibraryDao) : LocalMediaVisibilityRepository {
    override fun hiddenLocalItemIds(profileUserId: String): Set<String> =
        dao.getVisibilities(profileUserId)
            .filter { !it.visible }
            .mapTo(mutableSetOf()) { it.localItemId }

    override fun hideLocalItem(
        localItemId: String,
        profileUserId: String,
        reason: String,
    ) {
        dao.upsertVisibility(
            LocalMediaVisibilityEntity(
                localItemId = localItemId,
                profileUserId = profileUserId,
                visible = false,
                reason = reason,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}
