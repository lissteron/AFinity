package com.makd.afinity.data.local

import com.makd.afinity.data.models.media.AfinityChapter
import com.makd.afinity.data.models.media.AfinityEpisode
import com.makd.afinity.data.models.media.AfinityItem
import com.makd.afinity.data.models.media.AfinityImages
import com.makd.afinity.data.models.media.AfinityMediaStream
import com.makd.afinity.data.models.media.AfinityMovie
import com.makd.afinity.data.models.media.AfinitySeason
import com.makd.afinity.data.models.media.AfinityShow
import com.makd.afinity.data.models.media.AfinitySource
import com.makd.afinity.data.models.media.AfinitySourceType
import com.makd.afinity.data.models.media.offlinePlaybackEpisode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class LocalLibraryHomeCatalog(
    val movies: List<AfinityMovie>,
    val shows: List<AfinityShow>,
    val continueWatching: List<AfinityItem>,
)

@Singleton
class LocalLibraryMediaRepository
@Inject
constructor(
    private val rootStore: LocalLibraryRootStore,
    private val indexRepository: LocalLibraryIndexRepository,
    private val userStateRepository: LocalMediaUserStateRepository,
    private val visibilityRepository: LocalMediaVisibilityRepository =
        NoopLocalMediaVisibilityRepository,
) {
    fun catalogGenerationFlow(): Flow<String> = indexRepository.catalogGenerationFlow()

    suspend fun getVisibleMovies(
        profileUserId: String? = null,
        visibilityContext: LocalLibraryVisibilityContext =
            LocalLibraryVisibilityContext(
                currentUserId = profileUserId,
                kidModeEnabled = false,
                parentUnlocked = false,
            ),
    ): List<AfinityMovie> = visibleSnapshot(profileUserId, visibilityContext).movies

    suspend fun getVisibleShows(
        profileUserId: String? = null,
        visibilityContext: LocalLibraryVisibilityContext =
            LocalLibraryVisibilityContext(
                currentUserId = profileUserId,
                kidModeEnabled = false,
                parentUnlocked = false,
            ),
    ): List<AfinityShow> = visibleSnapshot(profileUserId, visibilityContext).shows

    suspend fun getHomeCatalog(
        profileUserId: String? = null,
        visibilityContext: LocalLibraryVisibilityContext =
            LocalLibraryVisibilityContext(
                currentUserId = profileUserId,
                kidModeEnabled = false,
                parentUnlocked = false,
            ),
    ): LocalLibraryHomeCatalog {
        val snapshot = visibleSnapshot(profileUserId, visibilityContext)
        return LocalLibraryHomeCatalog(
            movies = snapshot.movies,
            shows = snapshot.shows,
            continueWatching = snapshot.continueWatching,
        )
    }

    suspend fun getContentForContainer(
        containerId: UUID?,
        containerName: String? = null,
        profileUserId: String? = null,
        visibilityContext: LocalLibraryVisibilityContext =
            LocalLibraryVisibilityContext(
                currentUserId = profileUserId,
                kidModeEnabled = false,
                parentUnlocked = false,
            ),
    ): List<AfinityItem> {
        val snapshot = visibleSnapshot(profileUserId, visibilityContext)
        val seasons = snapshot.shows.flatMap { it.seasons }

        val show =
            snapshot.shows.firstOrNull { show ->
                show.id == containerId || show.name.matchesLocalContainerName(containerName)
            }
        if (show != null) {
            val showSeasons = show.seasons.sortedBy { it.indexNumber }
            return if (showSeasons.size == 1) {
                showSeasons.single().episodes.sortedBy { it.indexNumber }
            } else {
                showSeasons
            }
        }

        val season =
            seasons.firstOrNull { season ->
                season.id == containerId || season.name.matchesLocalContainerName(containerName)
            }
        if (season != null) {
            return season.episodes.sortedBy { it.indexNumber }
        }

        snapshot.movies.firstOrNull { it.id == containerId }?.let { return listOf(it) }
        return snapshot.movies + snapshot.shows
    }

    suspend fun findVisibleItemById(
        itemId: UUID,
        itemType: String? = null,
        seriesId: UUID? = null,
        profileUserId: String? = null,
        visibilityContext: LocalLibraryVisibilityContext =
            LocalLibraryVisibilityContext(
                currentUserId = profileUserId,
                kidModeEnabled = false,
                parentUnlocked = false,
            ),
    ): AfinityItem? {
        val snapshot = visibleSnapshot(profileUserId, visibilityContext)
        val episodes = snapshot.shows.flatMap { show -> show.seasons.flatMap { it.episodes } }
        val seasons = snapshot.shows.flatMap { it.seasons }
        return when (itemType?.uppercase()) {
            "MOVIE" -> snapshot.movies.firstOrNull { it.id == itemId }
            "EPISODE" -> episodes.firstOrNull { it.id == itemId }
            "SERIES", "SHOW" -> snapshot.shows.firstOrNull { it.id == itemId }
            "SEASON" ->
                seasons.firstOrNull { season ->
                    season.id == itemId && (seriesId == null || season.seriesId == seriesId)
                }
            else ->
                snapshot.movies.firstOrNull { it.id == itemId }
                    ?: episodes.firstOrNull { it.id == itemId }
                    ?: snapshot.shows.firstOrNull { it.id == itemId }
                    ?: seasons.firstOrNull { it.id == itemId }
        }
    }

    suspend fun resolvePlayableItem(
        itemId: UUID,
        itemType: String? = null,
        seriesId: UUID? = null,
        profileUserId: String? = null,
        visibilityContext: LocalLibraryVisibilityContext =
            LocalLibraryVisibilityContext(
                currentUserId = profileUserId,
                kidModeEnabled = false,
                parentUnlocked = false,
            ),
    ): AfinityItem? =
        when (
            val item =
                findVisibleItemById(
                    itemId = itemId,
                    itemType = itemType,
                    seriesId = seriesId,
                    profileUserId = profileUserId,
                    visibilityContext = visibilityContext,
                )
        ) {
            is AfinityShow -> item.offlinePlaybackEpisode()
            is AfinitySeason -> item.offlinePlaybackEpisode()
            else -> item
        }

    private fun LocalMediaFileRecord.toVisibleMovieOrNull(
        rootsById: Map<UUID, LocalLibraryRootRecord>,
        states: Map<String, LocalMediaUserStateRecord>,
    ): AfinityMovie? =
        takeIf { it.mediaKind == LocalMediaKind.MOVIE }
            ?.toMovie(
                rootsById[rootRegistryId]?.takeIf { root -> root.isVisibleRoot() },
                states[identity.localItemId],
            )

    private fun LocalMediaFileRecord.toVisibleEpisodeOrNull(
        rootsById: Map<UUID, LocalLibraryRootRecord>,
        states: Map<String, LocalMediaUserStateRecord>,
    ): AfinityEpisode? =
        takeIf { it.mediaKind == LocalMediaKind.EPISODE }
            ?.toEpisode(
                rootsById[rootRegistryId]?.takeIf { root -> root.isVisibleRoot() },
                states[identity.localItemId],
            )

    private suspend fun visibleSnapshot(
        profileUserId: String?,
        visibilityContext: LocalLibraryVisibilityContext,
    ): LocalLibraryMediaSnapshot = withContext(Dispatchers.IO) {
        val rootsById = rootStore.getRoots().associateBy { it.registryId }
        val states = profileUserId?.let(userStateRepository::getStates).orEmpty()
        val hidden = profileUserId?.let(visibilityRepository::hiddenLocalItemIds).orEmpty()
        val visibleFiles =
            indexRepository
                .visibleMediaFiles(visibilityContext)
                .filter { it.identity.localItemId !in hidden }
        val episodes =
            visibleFiles.mapNotNull { file -> file.toVisibleEpisodeOrNull(rootsById, states) }
        val movies =
            visibleFiles.mapNotNull { file -> file.toVisibleMovieOrNull(rootsById, states) }
        val shows = episodes.toShows()
        val continueWatching =
            (movies + episodes)
                .filter { item -> item.playbackPositionTicks > 0L && !item.played }
                .sortedByDescending { item -> item.playbackPositionTicks }
        LocalLibraryMediaSnapshot(
            movies = movies,
            shows = shows,
            continueWatching = continueWatching,
        )
    }

    private fun List<AfinityEpisode>.toShows(): List<AfinityShow> =
        this
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

    private data class LocalLibraryMediaSnapshot(
        val movies: List<AfinityMovie>,
        val shows: List<AfinityShow>,
        val continueWatching: List<AfinityItem>,
    )

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
        val seriesId = localSeriesUuid(showName)
        val seasonId =
            identity.jellyfinSeasonId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: UUID.nameUUIDFromBytes("local-season:$seriesId:$seasonNumber".toByteArray())
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
            path = localCatalogPath(),
            size = sizeBytes,
            mediaStreams = emptyList<AfinityMediaStream>(),
            bitrate = null,
            container = container,
            videoCodec = null,
            audioCodec = null,
            width = null,
            height = null,
        )

    private fun LocalMediaFileRecord.localCatalogPath(): String = "local://$mediaFileId"

    private fun LocalLibraryRootRecord.isVisibleRoot(): Boolean = enabled && lastKnownAvailable

    private fun String.matchesLocalContainerName(containerName: String?): Boolean =
        containerName?.takeIf { it.isNotBlank() }?.let { equals(it, ignoreCase = true) } == true

    private fun LocalMediaFileRecord.itemUuid(): UUID =
        identity.jellyfinItemId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: runCatching { UUID.fromString(identity.localItemId) }.getOrNull()
            ?: UUID.nameUUIDFromBytes(identity.localItemId.toByteArray())

    private fun LocalMediaFileRecord.localSeriesUuid(showName: String): UUID =
        identity.jellyfinSeriesId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.nameUUIDFromBytes("local-show:${localSeriesIdentityKey(showName)}".toByteArray())

    private fun LocalMediaFileRecord.localSeriesIdentityKey(showName: String): String {
        val rootIdentity = stableRootId ?: rootRegistryId
        val showFolder =
            relativePath
                .split('/')
                .let { segments ->
                    val showsIndex = segments.indexOf("Shows")
                    segments.getOrNull(showsIndex + 1)
                }
                ?.takeIf { it.isNotBlank() }
                ?: showName
        return buildString {
            identity.serverId?.takeIf { it.isNotBlank() }?.let { append("server:").append(it).append('|') }
            append("root:").append(rootIdentity).append('|')
            append("show:").append(showFolder)
        }
    }
}
