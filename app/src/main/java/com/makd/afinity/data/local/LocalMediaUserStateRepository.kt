package com.makd.afinity.data.local

import com.makd.afinity.data.database.dao.LocalLibraryDao
import com.makd.afinity.data.database.entities.LocalMediaUserStateEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface LocalMediaUserStateRepository {
    fun getState(localItemId: String, profileUserId: String): LocalMediaUserStateRecord?

    fun getStates(profileUserId: String): Map<String, LocalMediaUserStateRecord>

    fun savePlaybackProgress(
        mediaFileId: UUID,
        profileUserId: String,
        positionTicks: Long,
        played: Boolean,
    ): Boolean
}

@Singleton
class RoomLocalMediaUserStateRepository
@Inject
constructor(
    private val dao: LocalLibraryDao,
    private val indexRepository: LocalLibraryIndexRepository,
) : LocalMediaUserStateRepository {
    override fun getState(
        localItemId: String,
        profileUserId: String,
    ): LocalMediaUserStateRecord? = dao.getUserState(localItemId, profileUserId)?.toRecord()

    override fun getStates(profileUserId: String): Map<String, LocalMediaUserStateRecord> =
        dao.getUserStates(profileUserId).associate { it.localItemId to it.toRecord() }

    override fun savePlaybackProgress(
        mediaFileId: UUID,
        profileUserId: String,
        positionTicks: Long,
        played: Boolean,
    ): Boolean {
        val mediaFile = indexRepository.findByMediaFileId(mediaFileId) ?: return false
        val existing = dao.getUserState(mediaFile.identity.localItemId, profileUserId)
        dao.upsertUserState(
            LocalMediaUserStateEntity(
                localItemId = mediaFile.identity.localItemId,
                profileUserId = profileUserId,
                serverId = mediaFile.identity.serverId,
                jellyfinUserId = profileUserId,
                jellyfinItemId = mediaFile.identity.jellyfinItemId,
                playbackPositionTicks = if (played) 0L else positionTicks.coerceAtLeast(0L),
                played = played,
                favorite = existing?.favorite ?: false,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    private fun LocalMediaUserStateEntity.toRecord(): LocalMediaUserStateRecord =
        LocalMediaUserStateRecord(
            localItemId = localItemId,
            profileUserId = profileUserId,
            serverId = serverId,
            jellyfinUserId = jellyfinUserId,
            jellyfinItemId = jellyfinItemId,
            playbackPositionTicks = playbackPositionTicks,
            played = played,
            favorite = favorite,
            updatedAt = updatedAt,
        )
}
