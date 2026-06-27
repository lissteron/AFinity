package com.makd.afinity.data.local

import android.net.Uri
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
import java.io.File
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

    suspend fun hasIndexedMediaMissingArtwork(): Boolean =
        withContext(Dispatchers.IO) {
            indexRepository.allMediaFiles().any { file -> file.hasMissingRequiredArtwork() }
        }

    suspend fun hasIndexedMedia(): Boolean =
        withContext(Dispatchers.IO) { indexRepository.allMediaFiles().isNotEmpty() }

    suspend fun visibleCatalogSummary(
        visibilityContext: LocalLibraryVisibilityContext =
            LocalLibraryVisibilityContext(currentUserId = null, kidModeEnabled = false, parentUnlocked = false),
    ): LocalLibraryCatalogSummary =
        withContext(Dispatchers.IO) {
            val rootsById = rootStore.getRoots().associateBy { it.registryId }
            val files =
                indexRepository
                    .visibleMediaFiles(visibilityContext)
                    .filter { file -> rootsById[file.rootRegistryId]?.isVisibleRoot() == true }
            LocalLibraryCatalogSummary(
                fileCount = files.size,
                totalSizeBytes = files.sumOf { it.sizeBytes },
            )
        }

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

    private fun List<LocalMediaFileRecord>.toVisibleEpisodeEntries(
        rootsById: Map<UUID, LocalLibraryRootRecord>,
        states: Map<String, LocalMediaUserStateRecord>,
    ): List<LocalEpisodeCatalogEntry> {
        val candidates =
            mapNotNull { file ->
                if (file.mediaKind != LocalMediaKind.EPISODE) return@mapNotNull null
                val root =
                    rootsById[file.rootRegistryId]?.takeIf { root -> root.isVisibleRoot() }
                        ?: return@mapNotNull null
                val showName = file.title.showName ?: return@mapNotNull null
                val seasonNumber = file.title.seasonNumber ?: 0
                LocalEpisodeCandidate(
                    file = file,
                    root = root,
                    state = states[file.identity.localItemId],
                    seasonNumber = seasonNumber,
                    localSeriesKey = file.localSeriesIdentityKey(showName),
                )
            }

        return candidates
            .groupBy { it.localSeriesKey }
            .values
            .flatMap { seriesCandidates ->
                val localSeriesKey = seriesCandidates.first().localSeriesKey
                val seriesId =
                    seriesCandidates.canonicalUuidOrLocal(
                        selector = { it.file.identity.jellyfinSeriesId },
                        fallbackKey = "local-show:$localSeriesKey",
                    )
                val seasonIdsByNumber =
                    seriesCandidates
                        .groupBy { it.seasonNumber }
                        .mapValues { (seasonNumber, seasonCandidates) ->
                            seasonCandidates.canonicalUuidOrLocal(
                                selector = { it.file.identity.jellyfinSeasonId },
                                fallbackKey = "local-season:$localSeriesKey:$seasonNumber",
                            )
                        }

                seriesCandidates.mapNotNull { candidate ->
                    candidate.file.toEpisode(
                        root = candidate.root,
                        userState = candidate.state,
                        seriesId = seriesId,
                        seasonId = seasonIdsByNumber.getValue(candidate.seasonNumber),
                    )?.let { episode ->
                        LocalEpisodeCatalogEntry(episode = episode, artwork = candidate.file.artwork)
                    }
                }
            }
    }

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
        val episodeEntries = visibleFiles.toVisibleEpisodeEntries(rootsById, states)
        val episodes = episodeEntries.map { it.episode }
        val movies =
            visibleFiles.mapNotNull { file -> file.toVisibleMovieOrNull(rootsById, states) }
        val shows = episodeEntries.toShows()
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

    private fun List<LocalEpisodeCatalogEntry>.toShows(): List<AfinityShow> =
        this
            .groupBy { it.episode.seriesId }
            .map { (seriesId, seriesEntries) ->
                val seriesName = seriesEntries.firstOrNull()?.episode?.seriesName ?: "Local Show"
                val seasons =
                    seriesEntries
                        .groupBy { it.episode.seasonId }
                        .map { (_, seasonEntries) ->
                            val seasonEpisodes = seasonEntries.map { it.episode }
                            val first = seasonEpisodes.first()
                            val seasonImages = seasonEntries.toSeasonImages()
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
                                images = seasonImages,
                                providerIds = null,
                                externalUrls = null,
                            )
                        }
                        .sortedBy { it.indexNumber }
                val showImages = seriesEntries.map { it.episode }.toShowImages()
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
                    episodeCount = seriesEntries.size,
                    images = showImages,
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

    private data class LocalEpisodeCandidate(
        val file: LocalMediaFileRecord,
        val root: LocalLibraryRootRecord,
        val state: LocalMediaUserStateRecord?,
        val seasonNumber: Int,
        val localSeriesKey: String,
    )

    private data class LocalEpisodeCatalogEntry(
        val episode: AfinityEpisode,
        val artwork: LocalMediaArtwork,
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
            images = artwork.toAfinityImages(inheritParentArtwork = true),
            chapters = emptyList(),
            trickplayInfo = null,
            providerIds = identity.providerIds.takeIf { it.isNotEmpty() },
            externalUrls = null,
        )
    }

    private fun LocalMediaFileRecord.toEpisode(
        root: LocalLibraryRootRecord?,
        userState: LocalMediaUserStateRecord?,
        seriesId: UUID,
        seasonId: UUID,
    ): AfinityEpisode? {
        root ?: return null
        val showName = title.showName ?: return null
        val seasonNumber = title.seasonNumber ?: 0
        val episodeNumber = title.episodeNumber ?: 0
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
            images = artwork.toAfinityImages(),
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

    private fun <T> Collection<T>.canonicalUuidOrLocal(
        selector: (T) -> String?,
        fallbackKey: String,
    ): UUID {
        val remoteIds =
            mapNotNull { item -> selector(item)?.toUuidOrNull() }
                .distinct()
        return remoteIds.singleOrNull() ?: UUID.nameUUIDFromBytes(fallbackKey.toByteArray())
    }

    private fun String.toUuidOrNull(): UUID? =
        runCatching { UUID.fromString(this) }.getOrNull()

    private fun LocalMediaArtwork.toAfinityImages(
        inheritParentArtwork: Boolean = false,
    ): AfinityImages =
        AfinityImages(
            primary =
                (primaryUri ?: parentArtwork(inheritParentArtwork, seasonPrimaryUri, showPrimaryUri))
                    ?.toAndroidAssetUri(),
            backdrop =
                (backdropUri ?: parentArtwork(inheritParentArtwork, seasonBackdropUri, showBackdropUri))
                    ?.toAndroidAssetUri(),
            thumb =
                (thumbUri ?: parentArtwork(inheritParentArtwork, seasonThumbUri, showThumbUri))
                    ?.toAndroidAssetUri(),
            logo =
                (logoUri ?: parentArtwork(inheritParentArtwork, seasonLogoUri, showLogoUri))
                    ?.toAndroidAssetUri(),
            showPrimary = showPrimaryUri?.toAndroidAssetUri(),
            showBackdrop = showBackdropUri?.toAndroidAssetUri(),
            showThumb = showThumbUri?.toAndroidAssetUri(),
            showLogo = showLogoUri?.toAndroidAssetUri(),
        )

    private fun parentArtwork(
        inheritParentArtwork: Boolean,
        seasonUri: String?,
        showUri: String?,
    ): String? =
        if (inheritParentArtwork) seasonUri ?: showUri else null

    private fun List<LocalEpisodeCatalogEntry>.toSeasonImages(): AfinityImages {
        val images = map { it.episode.images }
        return AfinityImages(
            primary = firstArtworkUri { it.seasonPrimaryUri },
            backdrop = firstArtworkUri { it.seasonBackdropUri },
            thumb = firstArtworkUri { it.seasonThumbUri },
            logo = firstArtworkUri { it.seasonLogoUri },
            showPrimary = images.firstUri { it.showPrimary },
            showBackdrop = images.firstUri { it.showBackdrop },
            showThumb = images.firstUri { it.showThumb },
            showLogo = images.firstUri { it.showLogo },
        )
    }

    private fun List<AfinityEpisode>.toShowImages(): AfinityImages {
        val images = map { it.images }
        return AfinityImages(
            primary = images.firstUri { it.showPrimary },
            backdrop = images.firstUri { it.showBackdrop },
            thumb = images.firstUri { it.showThumb },
            logo = images.firstUri { it.showLogo },
        )
    }

    private fun List<AfinityImages>.firstUri(selector: (AfinityImages) -> Uri?): Uri? =
        firstNotNullOfOrNull(selector)

    private fun List<LocalEpisodeCatalogEntry>.firstArtworkUri(
        selector: (LocalMediaArtwork) -> String?
    ): Uri? =
        firstNotNullOfOrNull { entry -> selector(entry.artwork)?.toAndroidAssetUri() }

    private fun LocalMediaArtwork.isEmpty(): Boolean =
        primaryUri == null &&
            backdropUri == null &&
            thumbUri == null &&
            logoUri == null &&
            seasonPrimaryUri == null &&
            seasonBackdropUri == null &&
            seasonThumbUri == null &&
            seasonLogoUri == null &&
            showPrimaryUri == null &&
            showBackdropUri == null &&
            showThumbUri == null &&
            showLogoUri == null

    private fun LocalMediaFileRecord.hasMissingRequiredArtwork(): Boolean =
        when (mediaKind) {
            LocalMediaKind.MOVIE -> artwork.itemDisplayArtworkMissing() || artwork.hasLegacyJavaFileUri()
            LocalMediaKind.EPISODE ->
                artwork.hasLegacyJavaFileUri() ||
                    !hasScopedEpisodeItemDisplayArtwork() ||
                    artwork.seasonDisplayArtworkMissing() ||
                    artwork.showDisplayArtworkMissing()
        }

    private fun LocalMediaArtwork.itemDisplayArtworkMissing(): Boolean =
        primaryUri == null && thumbUri == null && backdropUri == null

    private fun LocalMediaFileRecord.hasScopedEpisodeItemDisplayArtwork(): Boolean =
        listOfNotNull(artwork.primaryUri, artwork.thumbUri, artwork.backdropUri)
            .any { uri -> uri.matchesEpisodeItemArtworkPath(relativePath) }

    private fun String.matchesEpisodeItemArtworkPath(relativeMediaPath: String): Boolean {
        val mediaDir = LocalLibraryArtworkPaths.mediaDirectory(relativeMediaPath)
        val baseName = LocalLibraryArtworkPaths.mediaBaseName(relativeMediaPath)
        if (mediaDir.isBlank() || baseName.isBlank()) return false
        val nestedDirs =
            LocalLibraryArtworkPaths.itemImagesDirectory(relativeMediaPath, LocalMediaKind.EPISODE)
                .pathVariants()
                .map { "$it/" }
        val prefixedParents =
            listOf(mediaDir, listOf(mediaDir, "images").joinRelativePath())
                .flatMap { it.pathVariants() }
                .map { "$it/" }
        val baseNames = baseName.pathSegmentVariants()
        return nestedDirs.any { contains(it) } ||
            prefixedParents.any { parent ->
                val afterParent = substringAfter(parent, missingDelimiterValue = "")
                afterParent.isNotEmpty() && baseNames.any { base -> afterParent.startsWith("$base-") }
            }
    }

    private fun LocalMediaArtwork.seasonDisplayArtworkMissing(): Boolean =
        seasonPrimaryUri == null && seasonThumbUri == null && seasonBackdropUri == null

    private fun LocalMediaArtwork.showDisplayArtworkMissing(): Boolean =
        showPrimaryUri == null && showThumbUri == null && showBackdropUri == null

    private fun LocalMediaArtwork.hasLegacyJavaFileUri(): Boolean =
        listOfNotNull(
                primaryUri,
                backdropUri,
                thumbUri,
                logoUri,
                seasonPrimaryUri,
                seasonBackdropUri,
                seasonThumbUri,
                seasonLogoUri,
                showPrimaryUri,
                showBackdropUri,
                showThumbUri,
                showLogoUri,
            )
            .any { it.isLegacyJavaFileUri() }

    private fun String.isLegacyJavaFileUri(): Boolean =
        startsWith("file:/") && !startsWith("file:///")

    private fun String.toAndroidAssetUri(): Uri {
        val parsed = Uri.parse(this)
        val path = parsed.path
        return if (parsed.scheme == "file" && path != null) Uri.fromFile(File(path)) else parsed
    }

    private fun String.pathVariants(): Set<String> =
        pathSegmentVariants() + split('/').joinToString("/") { segment ->
            segment.pathSegmentVariants().last()
        }

    private fun String.pathSegmentVariants(): Set<String> =
        linkedSetOf(this, replace(" ", "%20"), strictPercentEncode())

    private fun String.strictPercentEncode(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    private fun List<String>.joinRelativePath(): String = filter { it.isNotBlank() }.joinToString("/")
}
