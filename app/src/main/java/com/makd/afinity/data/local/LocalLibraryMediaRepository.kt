package com.makd.afinity.data.local

import com.makd.afinity.data.models.media.AfinityChapter
import com.makd.afinity.data.models.media.AfinityEpisode
import com.makd.afinity.data.models.media.AfinityImages
import com.makd.afinity.data.models.media.AfinityMediaStream
import com.makd.afinity.data.models.media.AfinityMovie
import com.makd.afinity.data.models.media.AfinitySeason
import com.makd.afinity.data.models.media.AfinityShow
import com.makd.afinity.data.models.media.AfinitySource
import com.makd.afinity.data.models.media.AfinitySourceType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalLibraryMediaRepository
@Inject
constructor(
    private val rootStore: LocalLibraryRootStore,
    private val indexRepository: LocalLibraryIndexRepository,
    private val fileSystem: LocalLibraryFileSystem,
    private val userStateRepository: LocalMediaUserStateRepository,
    private val visibilityRepository: LocalMediaVisibilityRepository =
        NoopLocalMediaVisibilityRepository,
) {
    suspend fun getVisibleMovies(
        profileUserId: String? = null,
        visibilityContext: LocalLibraryVisibilityContext =
            LocalLibraryVisibilityContext(
                currentUserId = profileUserId,
                kidModeEnabled = false,
                parentUnlocked = false,
            ),
    ): List<AfinityMovie> {
        val rootsById = rootStore.getRoots().associateBy { it.registryId }
        val states = profileUserId?.let(userStateRepository::getStates).orEmpty()
        val hidden = profileUserId?.let(visibilityRepository::hiddenLocalItemIds).orEmpty()
        return indexRepository
            .visibleMediaFiles(visibilityContext)
            .filter { it.identity.localItemId !in hidden }
            .filter { it.mediaKind == LocalMediaKind.MOVIE }
            .mapNotNull { file ->
                file.toMovie(
                    rootsById[file.rootRegistryId]?.takeIf { it.isVisibleRoot() },
                    states[file.identity.localItemId],
                )
            }
    }

    suspend fun getVisibleShows(
        profileUserId: String? = null,
        visibilityContext: LocalLibraryVisibilityContext =
            LocalLibraryVisibilityContext(
                currentUserId = profileUserId,
                kidModeEnabled = false,
                parentUnlocked = false,
            ),
    ): List<AfinityShow> {
        val rootsById = rootStore.getRoots().associateBy { it.registryId }
        val states = profileUserId?.let(userStateRepository::getStates).orEmpty()
        val hidden = profileUserId?.let(visibilityRepository::hiddenLocalItemIds).orEmpty()
        val episodes =
            indexRepository
                .visibleMediaFiles(visibilityContext)
                .filter { it.identity.localItemId !in hidden }
                .filter { it.mediaKind == LocalMediaKind.EPISODE }
                .mapNotNull { file ->
                    file.toEpisode(
                        rootsById[file.rootRegistryId]?.takeIf { it.isVisibleRoot() },
                        states[file.identity.localItemId],
                    )
                }
        return episodes
            .groupBy { it.seriesId }
            .map { (seriesId, seriesEpisodes) ->
                val seriesName = seriesEpisodes.firstOrNull()?.seriesName ?: "Local Show"
                val seasons =
                    seriesEpisodes
                        .groupBy { it.seasonId }
                        .map { (_, seasonEpisodes) ->
                            val first = seasonEpisodes.first()
                            AfinitySeason(
                                id = first.seasonId,
                                name = "Season ${first.parentIndexNumber}",
                                seriesId = seriesId,
                                seriesName = seriesName,
                                originalTitle = null,
                                overview = "",
                                sources = emptyList(),
                                indexNumber = first.parentIndexNumber,
                                episodes = seasonEpisodes.sortedBy { it.indexNumber },
                                episodeCount = seasonEpisodes.size,
                                productionYear = null,
                                premiereDate = null,
                                people = emptyList(),
                                played = seasonEpisodes.all { it.played },
                                favorite = false,
                                liked = false,
                                canPlay = true,
                                canDownload = false,
                                unplayedItemCount = seasonEpisodes.count { !it.played },
                                images = AfinityImages(),
                                providerIds = null,
                                externalUrls = null,
                            )
                        }
                        .sortedBy { it.indexNumber }
                AfinityShow(
                    id = seriesId,
                    name = seriesName,
                    originalTitle = null,
                    overview = "",
                    sources = emptyList(),
                    seasons = seasons,
                    played = seasons.all { it.played },
                    favorite = false,
                    liked = false,
                    canPlay = true,
                    canDownload = false,
                    unplayedItemCount = seasons.sumOf { it.episodes.count { episode -> !episode.played } },
                    genres = emptyList(),
                    people = emptyList(),
                    runtimeTicks = seasons.sumOf { season -> season.episodes.sumOf { it.runtimeTicks } },
                    communityRating = null,
                    officialRating = null,
                    status = "Local",
                    productionYear = null,
                    premiereDate = null,
                    dateCreated = null,
                    dateLastContentAdded = null,
                    endDate = null,
                    trailer = null,
                    tagline = null,
                    seasonCount = seasons.size,
                    episodeCount = seriesEpisodes.size,
                    images = AfinityImages(),
                    providerIds = null,
                    externalUrls = null,
                )
            }
            .sortedBy { it.name }
    }

    private fun LocalMediaFileRecord.toMovie(
        root: LocalLibraryRootRecord?,
        userState: LocalMediaUserStateRecord?,
    ): AfinityMovie? {
        root ?: return null
        return AfinityMovie(
            id = itemUuid(),
            name = title.name,
            originalTitle = null,
            overview = "",
            sources = listOf(toLocalSource(root)),
            played = userState?.played ?: false,
            favorite = userState?.favorite ?: false,
            liked = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = runtimeTicks ?: 0L,
            playbackPositionTicks = userState?.playbackPositionTicks ?: 0L,
            premiereDate = null,
            dateCreated = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            criticRating = null,
            status = "Local",
            productionYear = title.year,
            endDate = null,
            trailer = null,
            tagline = null,
            images = AfinityImages(),
            chapters = emptyList(),
            trickplayInfo = null,
            providerIds = identity.providerIds.takeIf { it.isNotEmpty() },
            externalUrls = null,
        )
    }

    private fun LocalMediaFileRecord.toEpisode(
        root: LocalLibraryRootRecord?,
        userState: LocalMediaUserStateRecord?,
    ): AfinityEpisode? {
        root ?: return null
        val showName = title.showName ?: return null
        val seasonNumber = title.seasonNumber ?: 0
        val episodeNumber = title.episodeNumber ?: 0
        val seriesId = UUID.nameUUIDFromBytes("local-show:$showName".toByteArray())
        val seasonId = UUID.nameUUIDFromBytes("local-show:$showName:season:$seasonNumber".toByteArray())
        return AfinityEpisode(
            id = itemUuid(),
            name = title.name,
            originalTitle = null,
            overview = "",
            indexNumber = episodeNumber,
            indexNumberEnd = null,
            parentIndexNumber = seasonNumber,
            sources = listOf(toLocalSource(root)),
            played = userState?.played ?: false,
            favorite = userState?.favorite ?: false,
            liked = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = runtimeTicks ?: 0L,
            playbackPositionTicks = userState?.playbackPositionTicks ?: 0L,
            premiereDate = null,
            seriesName = showName,
            seriesId = seriesId,
            seriesLogo = null,
            seriesLogoBlurHash = null,
            seasonId = seasonId,
            communityRating = null,
            people = emptyList(),
            images = AfinityImages(),
            chapters = emptyList(),
            trickplayInfo = null,
            providerIds = identity.providerIds.takeIf { it.isNotEmpty() },
            externalUrls = null,
        )
    }

    private fun LocalMediaFileRecord.toLocalSource(root: LocalLibraryRootRecord): AfinitySource =
        AfinitySource(
            id = mediaFileId.toString(),
            name = "Local file",
            type = AfinitySourceType.LOCAL,
            path = fileSystem.playerUri(root, relativePath),
            size = sizeBytes,
            mediaStreams = emptyList<AfinityMediaStream>(),
            bitrate = null,
            container = container,
            videoCodec = null,
            audioCodec = null,
            width = null,
            height = null,
        )

    private fun LocalLibraryRootRecord.isVisibleRoot(): Boolean = enabled && lastKnownAvailable

    private fun LocalMediaFileRecord.itemUuid(): UUID =
        identity.jellyfinItemId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: runCatching { UUID.fromString(identity.localItemId) }.getOrNull()
            ?: UUID.nameUUIDFromBytes(identity.localItemId.toByteArray())
}
