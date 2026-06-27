package com.makd.afinity.data.repository.download

import android.net.Uri
import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.models.download.DownloadStatus
import com.makd.afinity.data.models.extensions.toAfinityEpisode
import com.makd.afinity.data.models.extensions.toAfinityMovie
import com.makd.afinity.data.models.extensions.toAfinitySeason
import com.makd.afinity.data.models.extensions.toAfinityShow
import com.makd.afinity.data.models.media.AfinityEpisode
import com.makd.afinity.data.models.media.AfinityImages
import com.makd.afinity.data.models.media.AfinityMovie
import com.makd.afinity.data.models.media.AfinitySeason
import com.makd.afinity.data.models.media.AfinityShow
import com.makd.afinity.data.repository.DatabaseRepository
import com.makd.afinity.data.storage.DownloadStorageManager
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.TvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber

data class DownloadedArtworkRefreshProgress(
    val completed: Int,
    val total: Int,
    val currentItemName: String?,
)

data class DownloadedArtworkRefreshSummary(
    val refreshed: Int,
    val failed: Int,
    val skipped: Int,
)

@Singleton
class DownloadedArtworkRefresher
@Inject
constructor(
    private val databaseRepository: DatabaseRepository,
    private val downloadStorageManager: DownloadStorageManager,
    private val sessionRestoreResolver: SessionRestoreResolver,
) {
    suspend fun refreshCompletedDownloads(
        downloads: List<DownloadDto>,
        progress: suspend (DownloadedArtworkRefreshProgress) -> Unit = {},
    ): DownloadedArtworkRefreshSummary {
        val targets =
            downloads.filter { download ->
                download.status == DownloadStatus.COMPLETED &&
                    download.itemType.uppercase() in setOf("MOVIE", "EPISODE")
            }
        val sessions = mutableMapOf<Pair<String, UUID>, RestoredDownloadSession>()
        val refreshedShows = mutableSetOf<Pair<String, UUID>>()
        val refreshedSeasons = mutableSetOf<Pair<String, UUID>>()
        var refreshed = 0
        var failed = 0
        var skipped = downloads.size - targets.size

        progress(
            DownloadedArtworkRefreshProgress(
                completed = 0,
                total = targets.size,
                currentItemName = null,
            )
        )
        targets.forEachIndexed { index, download ->
            currentCoroutineContext().ensureActive()
            progress(
                DownloadedArtworkRefreshProgress(
                    completed = index,
                    total = targets.size,
                    currentItemName = download.itemName,
                )
            )
            try {
                val sessionKey = download.serverId to download.userId
                val session =
                    sessions.getOrPut(sessionKey) {
                        when (
                            val result =
                                sessionRestoreResolver.restore(
                                    serverId = download.serverId,
                                    userId = download.userId,
                                )
                        ) {
                            is SessionRestoreResult.Restored -> result.session
                            is SessionRestoreResult.Failed -> error(result.message)
                            is SessionRestoreResult.Paused -> error(result.message)
                        }
                    }
                if (refreshDownload(session, download, refreshedShows, refreshedSeasons)) {
                    refreshed += 1
                } else {
                    skipped += 1
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += 1
                Timber.w(e, "Failed to refresh downloaded artwork for ${download.itemName}")
            }
            progress(
                DownloadedArtworkRefreshProgress(
                    completed = index + 1,
                    total = targets.size,
                    currentItemName = null,
                )
            )
        }

        return DownloadedArtworkRefreshSummary(
            refreshed = refreshed,
            failed = failed,
            skipped = skipped,
        )
    }

    private suspend fun refreshDownload(
        session: RestoredDownloadSession,
        download: DownloadDto,
        refreshedShows: MutableSet<Pair<String, UUID>>,
        refreshedSeasons: MutableSet<Pair<String, UUID>>,
    ): Boolean {
        if (!isStillCompleted(download.id)) return false
        val item = fetchItem(session.apiClient, download.userId, download.itemId) ?: return false
        val baseUrl = session.apiClient.baseUrl ?: session.serverUrl

        return when (item.type) {
            BaseItemKind.MOVIE -> {
                val movie = item.toAfinityMovie(baseUrl)
                val existingImages =
                    databaseRepository.getMovie(movie.id, download.userId)?.images
                if (
                    !databaseRepository.insertMovieIfDownloadCompleted(
                        download.id,
                        movie,
                        download.serverId,
                    )
                ) {
                    return false
                }
                refreshMovieImages(
                    apiClient = session.apiClient,
                    okHttpClient = session.okHttpClient,
                    download = download,
                    movie = movie,
                    existingImages = existingImages,
                )
                true
            }
            BaseItemKind.EPISODE -> {
                val episode =
                    item.toAfinityEpisodeForDownload(
                        apiClient = session.apiClient,
                        userId = download.userId,
                        baseUrl = baseUrl,
                        download = download,
                    ) ?: return false
                val existingImages =
                    databaseRepository.getEpisode(episode.id, download.userId)?.images
                refreshRelatedEpisodeArtwork(
                    session = session,
                    download = download,
                    episode = episode,
                    baseUrl = baseUrl,
                    refreshedShows = refreshedShows,
                    refreshedSeasons = refreshedSeasons,
                )
                if (
                    !databaseRepository.insertEpisodeIfDownloadCompleted(
                        download.id,
                        episode,
                        download.serverId,
                    )
                ) {
                    return false
                }
                refreshEpisodeImages(
                    apiClient = session.apiClient,
                    okHttpClient = session.okHttpClient,
                    download = download,
                    episode = episode,
                    existingImages = existingImages,
                )
                true
            }
            else -> false
        }
    }

    private suspend fun refreshRelatedEpisodeArtwork(
        session: RestoredDownloadSession,
        download: DownloadDto,
        episode: AfinityEpisode,
        baseUrl: String,
        refreshedShows: MutableSet<Pair<String, UUID>>,
        refreshedSeasons: MutableSet<Pair<String, UUID>>,
    ) {
        val showKey = download.serverId to episode.seriesId
        if (
            showKey !in refreshedShows &&
                refreshShowArtwork(session, download, episode.seriesId, baseUrl)
        ) {
            refreshedShows += showKey
        }
        val seasonKey = download.serverId to episode.seasonId
        if (
            seasonKey !in refreshedSeasons &&
                refreshSeasonArtwork(session, download, episode.seasonId, baseUrl)
        ) {
            refreshedSeasons += seasonKey
        }
    }

    private suspend fun refreshShowArtwork(
        session: RestoredDownloadSession,
        download: DownloadDto,
        showId: UUID,
        baseUrl: String,
    ): Boolean {
        val existingImages = databaseRepository.getShow(showId, download.userId)?.images
        val show = fetchItem(session.apiClient, download.userId, showId)?.toAfinityShow(baseUrl)
            ?: databaseRepository.getShow(showId, download.userId)
            ?: return false
        if (
            !databaseRepository.insertShowIfDownloadCompleted(
                download.id,
                show,
                download.serverId,
            )
        ) {
            return false
        }
        refreshShowImages(
            apiClient = session.apiClient,
            okHttpClient = session.okHttpClient,
            download = download,
            show = show,
            existingImages = existingImages,
        )
        return true
    }

    private suspend fun refreshSeasonArtwork(
        session: RestoredDownloadSession,
        download: DownloadDto,
        seasonId: UUID,
        baseUrl: String,
    ): Boolean {
        val existingImages = databaseRepository.getSeason(seasonId, download.userId)?.images
        val season =
            fetchItem(session.apiClient, download.userId, seasonId)?.toAfinitySeason(baseUrl)
                ?: databaseRepository.getSeason(seasonId, download.userId)
                ?: return false
        if (
            !databaseRepository.insertSeasonIfDownloadCompleted(
                download.id,
                season,
                download.serverId,
            )
        ) {
            return false
        }
        refreshSeasonImages(
            apiClient = session.apiClient,
            okHttpClient = session.okHttpClient,
            download = download,
            season = season,
            existingImages = existingImages,
        )
        return true
    }

    private suspend fun fetchItem(
        apiClient: ApiClient,
        userId: UUID,
        itemId: UUID,
    ): BaseItemDto? =
        ItemsApi(apiClient)
            .getItems(
                userId = userId,
                ids = listOf(itemId),
                fields =
                    listOf(
                        ItemFields.MEDIA_SOURCES,
                        ItemFields.MEDIA_STREAMS,
                        ItemFields.OVERVIEW,
                        ItemFields.GENRES,
                        ItemFields.PEOPLE,
                        ItemFields.TAGLINES,
                        ItemFields.CHAPTERS,
                    ),
                enableImages = true,
                enableUserData = true,
            )
            .content
            .items
            .firstOrNull()

    private suspend fun BaseItemDto.toAfinityEpisodeForDownload(
        apiClient: ApiClient,
        userId: UUID,
        baseUrl: String,
        download: DownloadDto,
    ): AfinityEpisode? {
        val fallbackSeriesId = seriesId ?: download.seriesId?.toUuidOrNull()
        val fallbackSeasonId =
            seasonId
                ?: download.seasonId?.toUuidOrNull()
                ?: fallbackSeriesId?.let { resolveSingleSeasonId(apiClient, userId, it) }
        return toAfinityEpisode(
            baseUrl = baseUrl,
            fallbackSeriesId = fallbackSeriesId,
            fallbackSeasonId = fallbackSeasonId,
        )
    }

    private suspend fun resolveSingleSeasonId(
        apiClient: ApiClient,
        userId: UUID,
        seriesId: UUID,
    ): UUID? =
        runCatching {
                TvShowsApi(apiClient)
                    .getSeasons(
                        seriesId = seriesId,
                        userId = userId,
                        enableImages = false,
                        enableUserData = false,
                    )
                    .content
                    .items
                    .singleOrNull()
                    ?.id
            }
            .onFailure { error ->
                Timber.w(error, "Failed to resolve fallback season for artwork refresh series $seriesId")
            }
            .getOrNull()

    private fun String.toUuidOrNull(): UUID? =
        runCatching { UUID.fromString(this) }
            .onFailure { Timber.w("Ignoring invalid persisted UUID: $this") }
            .getOrNull()

    private suspend fun refreshMovieImages(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        download: DownloadDto,
        movie: AfinityMovie,
        existingImages: AfinityImages?,
    ) {
        val imagesDir = itemImagesDir(download, movie.id)
        val downloaded = downloadImageSet(apiClient, okHttpClient, movie.images, imagesDir)
        val updatedImages = mergeDownloadedImages(movie.images, existingImages, downloaded)
        databaseRepository.insertMovieIfDownloadCompleted(
            download.id,
            movie.copy(images = updatedImages),
            download.serverId,
        )
    }

    private suspend fun refreshEpisodeImages(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        download: DownloadDto,
        episode: AfinityEpisode,
        existingImages: AfinityImages?,
    ) {
        val sharedSeriesImages =
            databaseRepository.getShow(episode.seriesId, download.userId)?.images
        val imagesDir = itemImagesDir(download, episode.id)
        val downloaded = downloadImageSet(apiClient, okHttpClient, episode.images, imagesDir)
        val updatedImages =
            mergeDownloadedImages(episode.images, existingImages, downloaded)
                .copy(
                    showPrimary = sharedSeriesImages?.primary ?: episode.images.showPrimary,
                    showBackdrop = sharedSeriesImages?.backdrop ?: episode.images.showBackdrop,
                    showThumb = sharedSeriesImages?.thumb ?: episode.images.showThumb,
                    showLogo = sharedSeriesImages?.logo ?: episode.images.showLogo,
                    showPrimaryImageBlurHash = episode.images.showPrimaryImageBlurHash,
                    showBackdropImageBlurHash = episode.images.showBackdropImageBlurHash,
                    showThumbImageBlurHash = episode.images.showThumbImageBlurHash,
                    showLogoImageBlurHash = episode.images.showLogoImageBlurHash,
                )
        databaseRepository.insertEpisodeIfDownloadCompleted(
            download.id,
            episode.copy(images = updatedImages),
            download.serverId,
        )
        deleteRedundantEpisodeSeriesImages(imagesDir)
    }

    private suspend fun refreshShowImages(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        download: DownloadDto,
        show: AfinityShow,
        existingImages: AfinityImages?,
    ) {
        val imagesDir = showImagesDir(download, show.id)
        val downloaded =
            downloadImageSet(
                apiClient = apiClient,
                okHttpClient = okHttpClient,
                images = show.images.copy(thumb = null),
                imagesDir = imagesDir,
            )
        databaseRepository.insertShowIfDownloadCompleted(
            download.id,
            show.copy(
                images = mergeDownloadedImages(show.images, existingImages, downloaded)
            ),
            download.serverId,
        )
    }

    private suspend fun refreshSeasonImages(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        download: DownloadDto,
        season: AfinitySeason,
        existingImages: AfinityImages?,
    ) {
        val imagesDir = seasonImagesDir(download, season)
        val downloaded =
            downloadImageSet(
                apiClient = apiClient,
                okHttpClient = okHttpClient,
                images = season.images.copy(thumb = null, logo = null),
                imagesDir = imagesDir,
            )
        databaseRepository.insertSeasonIfDownloadCompleted(
            download.id,
            season.copy(
                images = mergeDownloadedImages(season.images, existingImages, downloaded)
            ),
            download.serverId,
        )
    }

    private suspend fun itemImagesDir(download: DownloadDto, itemId: UUID): File {
        return downloadStorageManager.getItemImagesDirectory(download, itemId)
    }

    private suspend fun showImagesDir(download: DownloadDto, showId: UUID): File {
        return downloadStorageManager.getShowImagesDirectory(download, showId)
    }

    private suspend fun seasonImagesDir(download: DownloadDto, season: AfinitySeason): File {
        return downloadStorageManager.getSeasonImagesDirectory(download, season.seriesId, season.indexNumber)
    }

    private suspend fun downloadImageSet(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        images: AfinityImages,
        imagesDir: File,
    ): Map<String, Uri> {
        val downloaded = mutableMapOf<String, Uri>()
        suspend fun save(uri: Uri?, key: String) {
            if (!uri.isDownloadableRemoteUri()) return
            downloadImage(apiClient, okHttpClient, uri.toString(), imagesDir, key)?.let {
                downloaded[key] = it
            }
        }

        save(images.primary, "primary")
        save(images.backdrop, "backdrop")
        save(images.thumb, "thumb")
        save(images.logo, "logo")
        return downloaded
    }

    private suspend fun downloadImage(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        imageUrl: String,
        outputDir: File,
        baseName: String,
    ): Uri? {
        val request =
            Request.Builder()
                .url(imageUrl)
                .header("X-Emby-Token", apiClient.accessToken ?: "")
                .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body
            val extension = imageExtension(response.header("Content-Type"))
            val tempFile = File(outputDir, "$baseName.$extension.refresh")
            val targetFile = File(outputDir, "$baseName.$extension")
            try {
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (!tempFile.exists() || tempFile.length() <= 0L) return null
                deleteExistingImageVariants(outputDir, baseName)
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                return Uri.fromFile(targetFile)
            } catch (e: CancellationException) {
                tempFile.delete()
                throw e
            } catch (e: Exception) {
                tempFile.delete()
                Timber.w(e, "Failed to refresh downloaded image $baseName")
                return null
            }
        }
    }

    private fun mergeDownloadedImages(
        remote: AfinityImages,
        existing: AfinityImages?,
        downloaded: Map<String, Uri>,
    ): AfinityImages =
        AfinityImages(
            primary = downloaded["primary"] ?: existing?.primary ?: remote.primary,
            backdrop = downloaded["backdrop"] ?: existing?.backdrop ?: remote.backdrop,
            thumb = downloaded["thumb"] ?: existing?.thumb ?: remote.thumb,
            logo = downloaded["logo"] ?: existing?.logo ?: remote.logo,
            showPrimary = existing?.showPrimary ?: remote.showPrimary,
            showBackdrop = existing?.showBackdrop ?: remote.showBackdrop,
            showThumb = existing?.showThumb ?: remote.showThumb,
            showLogo = existing?.showLogo ?: remote.showLogo,
            primaryImageBlurHash = remote.primaryImageBlurHash,
            backdropImageBlurHash = remote.backdropImageBlurHash,
            thumbImageBlurHash = remote.thumbImageBlurHash,
            logoImageBlurHash = remote.logoImageBlurHash,
            showPrimaryImageBlurHash = remote.showPrimaryImageBlurHash,
            showBackdropImageBlurHash = remote.showBackdropImageBlurHash,
            showThumbImageBlurHash = remote.showThumbImageBlurHash,
            showLogoImageBlurHash = remote.showLogoImageBlurHash,
        )

    private suspend fun isStillCompleted(downloadId: UUID): Boolean =
        databaseRepository.getDownload(downloadId)?.status == DownloadStatus.COMPLETED

    private fun Uri?.isDownloadableRemoteUri(): Boolean {
        val scheme = this?.scheme ?: return false
        return scheme == "http" || scheme == "https"
    }

    private fun imageExtension(contentType: String?): String =
        when {
            contentType?.contains("png", ignoreCase = true) == true -> "png"
            contentType?.contains("webp", ignoreCase = true) == true -> "webp"
            contentType?.contains("gif", ignoreCase = true) == true -> "gif"
            else -> "jpg"
        }

    private fun deleteExistingImageVariants(outputDir: File, baseName: String) {
        outputDir
            .listFiles { _, name ->
                name.startsWith("$baseName.") && !name.endsWith(".refresh")
            }
            ?.forEach { file -> file.delete() }
    }

    private fun deleteRedundantEpisodeSeriesImages(imagesDir: File) {
        val redundantPrefixes = listOf("showPrimary.", "showBackdrop.", "showThumb.", "showLogo.")
        imagesDir
            .listFiles { _, name -> redundantPrefixes.any { prefix -> name.startsWith(prefix) } }
            ?.forEach { file -> file.delete() }
    }

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}
