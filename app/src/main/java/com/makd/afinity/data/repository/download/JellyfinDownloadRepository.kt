package com.makd.afinity.data.repository.download

import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.database.entities.toDownloadInfo
import com.makd.afinity.data.manager.OfflineModeManager
import com.makd.afinity.data.manager.SessionManager
import com.makd.afinity.data.models.download.DownloadInfo
import com.makd.afinity.data.models.download.DownloadQualityMode
import com.makd.afinity.data.models.download.DownloadStatus
import com.makd.afinity.data.models.extensions.toAfinityEpisode
import com.makd.afinity.data.models.extensions.toAfinityMovie
import com.makd.afinity.data.models.media.AfinityEpisode
import com.makd.afinity.data.models.media.AfinityItem
import com.makd.afinity.data.models.media.AfinityMovie
import com.makd.afinity.data.models.media.AfinitySourceType
import com.makd.afinity.data.repository.DatabaseRepository
import com.makd.afinity.data.repository.PreferencesRepository
import com.makd.afinity.data.repository.media.MediaRepository
import com.makd.afinity.data.storage.DownloadStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JellyfinDownloadRepository
@Inject
constructor(
    private val sessionManager: SessionManager,
    private val mediaRepository: MediaRepository,
    private val databaseRepository: DatabaseRepository,
    private val preferencesRepository: PreferencesRepository,
    private val downloadQueueScheduler: DownloadQueueScheduler,
    private val stateStore: DownloadQueueStateStore,
    private val downloadStorageManager: DownloadStorageManager,
    private val offlineModeManager: OfflineModeManager,
    private val downloadedArtworkRefresher: DownloadedArtworkRefresher,
) : DownloadRepository {

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_ITEM_ID = "item_id"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_ITEM_NAME = "item_name"
        const val KEY_ITEM_TYPE = "item_type"
        const val KEY_DOWNLOAD_QUALITY_MODE = "download_quality_mode"
    }

    override suspend fun startDownload(itemId: UUID, sourceId: String): Result<UUID> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                if (offlineModeManager.isHardOffline()) {
                    return@withContext Result.failure(Exception("Offline mode is enabled"))
                }

                val currentSession =
                    sessionManager.currentSession.value
                        ?: return@withContext Result.failure(Exception("No active session"))

                val userId = currentSession.userId
                val serverId = currentSession.serverId
                val baseUrl = currentSession.serverUrl

                val existingDownload =
                    databaseRepository.getDownloadByItemIdScoped(itemId, serverId, userId)
                if (existingDownload != null) {
                    if (existingDownload.status == DownloadStatus.COMPLETED) {
                        return@withContext Result.failure(Exception("Item already downloaded"))
                    }
                    if (
                        existingDownload.status == DownloadStatus.DOWNLOADING ||
                            existingDownload.status == DownloadStatus.QUEUED
                    ) {
                        return@withContext Result.failure(
                            Exception("Item is already being downloaded")
                        )
                    }
                }

                val baseItemDto =
                    mediaRepository.getItem(
                        itemId = itemId,
                        fields =
                            listOf(
                                ItemFields.MEDIA_SOURCES,
                                ItemFields.MEDIA_STREAMS,
                                ItemFields.OVERVIEW,
                            ),
                    ) ?: return@withContext Result.failure(Exception("Item not found"))

                val item =
                    when (baseItemDto.type) {
                        BaseItemKind.MOVIE -> baseItemDto.toAfinityMovie(baseUrl)
                        BaseItemKind.EPISODE ->
                            baseItemDto.toAfinityEpisode(baseUrl)
                                ?: return@withContext Result.failure(
                                    Exception("Failed to convert episode")
                                )

                        else ->
                            return@withContext Result.failure(
                                Exception("Unsupported item type: ${baseItemDto.type}")
                            )
                    }

                val source =
                    if (sourceId.isEmpty()) {
                        item.sources.firstOrNull { it.type == AfinitySourceType.REMOTE }
                            ?: return@withContext Result.failure(
                                Exception("No remote source available")
                            )
                    } else {
                        item.sources.find { it.id == sourceId }
                            ?: return@withContext Result.failure(Exception("Source not found"))
                    }

                val qualityMode =
                    DownloadQualityMode.fromPreference(preferencesRepository.getDownloadQuality())

                val download =
                    buildQueuedDownload(
                        existingDownload = existingDownload,
                        item = item,
                        sourceId = source.id,
                        serverId = serverId,
                        userId = userId,
                        qualityMode = qualityMode,
                    )

                databaseRepository.insertDownload(download)

                scheduleQueue(DownloadQueueScheduleTrigger.USER_ACTION)

                Result.success(download.id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start download")
                Result.failure(e)
            }
        }

    private suspend fun scheduleQueue(trigger: DownloadQueueScheduleTrigger) {
        if (offlineModeManager.isHardOffline()) {
            Timber.d("Skipping download queue schedule in offline mode")
            return
        }
        when (val result = downloadQueueScheduler.scheduleQueue(trigger)) {
            is DownloadQueueScheduler.ScheduleResult.Failed ->
                Timber.w("Download queue scheduling failed: ${result.reason}")
            is DownloadQueueScheduler.ScheduleResult.Deferred ->
                Timber.i("Download queue scheduling deferred: ${result.reason}")
            else -> Unit
        }
    }

    private fun buildQueuedDownload(
        existingDownload: DownloadDto?,
        item: AfinityItem,
        sourceId: String,
        serverId: String,
        userId: UUID,
        qualityMode: DownloadQualityMode,
    ): DownloadDto {
        val source = item.sources.first { it.id == sourceId }
        val now = System.currentTimeMillis()
        val itemType =
            when (item) {
                is AfinityMovie -> "Movie"
                is AfinityEpisode -> "Episode"
                else -> "Unknown"
            }
        val folderPath =
            when (item) {
                is AfinityMovie -> "$serverId/movies/${item.id}"
                is AfinityEpisode ->
                    "$serverId/shows/${item.seriesId}/seasons/${item.parentIndexNumber}/${item.id}"
                else -> "$serverId/${item.id}"
            }
        val sourceName =
            if (qualityMode.requiresTranscode) {
                "${source.name} - ${qualityMode.displayName}"
            } else {
                source.name
            }
        return DownloadDto(
            id = existingDownload?.id ?: UUID.randomUUID(),
            itemId = item.id,
            itemName = item.name,
            itemType = itemType,
            sourceId = source.id,
            sourceName = sourceName,
            status = DownloadStatus.QUEUED,
            progress = 0f,
            bytesDownloaded = 0L,
            totalBytes = if (qualityMode.requiresTranscode) 0L else source.size,
            filePath = null,
            error = null,
            createdAt = existingDownload?.createdAt ?: now,
            updatedAt = now,
            serverId = serverId,
            userId = userId,
            imageUrl = item.images.primary?.toString(),
            seriesImageUrl = (item as? AfinityEpisode)?.images?.showPrimary?.toString(),
            seriesName = (item as? AfinityEpisode)?.seriesName,
            seasonNumber = (item as? AfinityEpisode)?.parentIndexNumber,
            episodeNumber = (item as? AfinityEpisode)?.indexNumber,
            releaseYear = (item as? AfinityMovie)?.productionYear?.toString(),
            runtimeTicks = item.runtimeTicks,
            folderPath = folderPath,
            seriesId = (item as? AfinityEpisode)?.seriesId?.toString(),
        )
    }

    override suspend fun pauseDownload(downloadId: UUID): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val download = databaseRepository.getDownload(downloadId)
                if (download != null) {
                    when (download.status) {
                        DownloadStatus.DOWNLOADING -> {
                            downloadQueueScheduler.cancelQueue()
                            stateStore.pauseActiveDownload(downloadId, "Paused by user")
                        }
                        DownloadStatus.QUEUED -> databaseRepository.pauseQueuedDownload(downloadId, null)
                        DownloadStatus.PAUSED -> Unit
                        DownloadStatus.COMPLETED,
                        DownloadStatus.FAILED,
                        DownloadStatus.CANCELLED -> {
                            Timber.d("Ignoring pause for terminal/non-active download $downloadId")
                        }
                    }
                }
                scheduleQueue(DownloadQueueScheduleTrigger.USER_ACTION)

                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to pause download")
                Result.failure(e)
            }
        }

    override suspend fun resumeDownload(downloadId: UUID): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                if (offlineModeManager.isHardOffline()) {
                    return@withContext Result.failure(Exception("Offline mode is enabled"))
                }

                val download =
                    databaseRepository.getDownload(downloadId)
                        ?: return@withContext Result.failure(Exception("Download not found"))

                if (
                    download.status != DownloadStatus.PAUSED &&
                        download.status != DownloadStatus.FAILED
                ) {
                    return@withContext Result.failure(Exception("Download is not paused or failed"))
                }

                val updatedDownload =
                    download.copy(
                        status = DownloadStatus.QUEUED,
                        error = null,
                        activeClaimId = null,
                        activeBackendRunId = null,
                        activeBackendKind = null,
                        claimStartedAt = null,
                        claimHeartbeatAt = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                databaseRepository.insertDownload(updatedDownload)

                scheduleQueue(DownloadQueueScheduleTrigger.USER_ACTION)

                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to resume download")
                Result.failure(e)
            }
        }

    override suspend fun cancelDownload(downloadId: UUID): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val download = databaseRepository.getDownload(downloadId)
                if (download != null) {
                    if (download.status == DownloadStatus.DOWNLOADING) {
                        downloadQueueScheduler.cancelQueue()
                    }
                    databaseRepository.deleteDownload(downloadId)

                    download.filePath?.takeIf(downloadStorageManager::isContentUri)?.let {
                        downloadStorageManager.deleteDocumentUri(it)
                    }

                    val itemDir =
                        downloadStorageManager.getItemDownloadDirectory(download, download.itemId)
                    val mediaDir = File(itemDir, "media")
                    if (mediaDir.exists()) {
                        mediaDir
                            .listFiles { _, name -> name.startsWith(download.sourceId) }
                            ?.forEach { file ->
                                Timber.d("Deleting download file: ${file.name}")
                                file.delete()
                            }
                    }
                    if (
                        itemDir.exists() &&
                            (itemDir.listFiles()?.isEmpty() == true ||
                                itemDir.listFiles()?.all {
                                    it.name == "media" && it.listFiles()?.isEmpty() == true
                                } == true)
                    ) {
                        itemDir.deleteRecursively()
                    }
                }
                scheduleQueue(DownloadQueueScheduleTrigger.USER_ACTION)

                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to cancel download")
                Result.failure(e)
            }
        }

    override suspend fun deleteDownload(downloadId: UUID): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val download =
                    databaseRepository.getDownload(downloadId)
                        ?: return@withContext Result.failure(Exception("Download not found"))

                if (download.status == DownloadStatus.DOWNLOADING) {
                    downloadQueueScheduler.cancelQueue()
                }

                val itemFolder =
                    downloadStorageManager.getItemDownloadDirectory(download, download.itemId)

                databaseRepository.deleteDownload(downloadId)

                download.filePath?.takeIf(downloadStorageManager::isContentUri)?.let {
                    downloadStorageManager.deleteDocumentUri(it)
                }

                if (itemFolder.exists()) {
                    itemFolder.deleteRecursively()
                }

                val sources = databaseRepository.getSources(download.itemId)
                sources
                    .filter { it.type == AfinitySourceType.LOCAL }
                    .forEach { databaseRepository.deleteSource(it.id) }
                scheduleQueue(DownloadQueueScheduleTrigger.USER_ACTION)

                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete download")
                Result.failure(e)
            }
        }

    override suspend fun getDownload(downloadId: UUID): DownloadInfo? =
        withContext(Dispatchers.IO) { databaseRepository.getDownload(downloadId)?.toDownloadInfo() }

    override suspend fun getDownloadByItemId(itemId: UUID): DownloadInfo? =
        withContext(Dispatchers.IO) {
            val session = sessionManager.currentSession.value ?: return@withContext null
            databaseRepository
                .getDownloadByItemIdScoped(itemId, session.serverId, session.userId)
                ?.toDownloadInfo()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getAllDownloadsFlow(): Flow<List<DownloadInfo>> {
        return sessionManager.currentSession
            .filterNotNull()
            .flatMapLatest { session ->
                databaseRepository.getAllDownloadsFlowScoped(session.serverId, session.userId)
            }
            .map { downloads -> downloads.map { it.toDownloadInfo() } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getDownloadsByStatusFlow(
        statuses: List<DownloadStatus>
    ): Flow<List<DownloadInfo>> {
        return sessionManager.currentSession
            .filterNotNull()
            .flatMapLatest { session ->
                databaseRepository.getDownloadsByStatusFlowScoped(
                    statuses,
                    session.serverId,
                    session.userId,
                )
            }
            .map { downloads -> downloads.map { it.toDownloadInfo() } }
    }

    override fun getActiveDownloadsFlow(): Flow<List<DownloadInfo>> {
        return getDownloadsByStatusFlow(
            listOf(
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
                DownloadStatus.FAILED,
            )
        )
    }

    override fun getCompletedDownloadsFlow(): Flow<List<DownloadInfo>> {
        return getDownloadsByStatusFlow(listOf(DownloadStatus.COMPLETED))
    }

    override suspend fun refreshCompletedArtwork(
        progress: suspend (DownloadedArtworkRefreshProgress) -> Unit
    ): Result<DownloadedArtworkRefreshSummary> =
        withContext(Dispatchers.IO) {
            try {
                if (offlineModeManager.isCurrentlyOffline()) {
                    return@withContext Result.failure(Exception("Offline mode is enabled"))
                }
                val session =
                    sessionManager.currentSession.value
                        ?: return@withContext Result.failure(Exception("No active session"))
                val downloads =
                    databaseRepository
                        .getDownloadsByStatusFlowScoped(
                            listOf(DownloadStatus.COMPLETED),
                            session.serverId,
                            session.userId,
                        )
                        .first()
                Result.success(
                    downloadedArtworkRefresher.refreshCompletedDownloads(downloads, progress)
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to refresh completed download artwork")
                Result.failure(e)
            }
        }

    override suspend fun isItemDownloaded(itemId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            val session = sessionManager.currentSession.value ?: return@withContext false
            val download =
                databaseRepository.getDownloadByItemIdScoped(
                    itemId,
                    session.serverId,
                    session.userId,
                )
            download?.status == DownloadStatus.COMPLETED
        }

    override suspend fun isItemDownloading(itemId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            val session = sessionManager.currentSession.value ?: return@withContext false
            val download =
                databaseRepository.getDownloadByItemIdScoped(
                    itemId,
                    session.serverId,
                    session.userId,
                )
            download?.status == DownloadStatus.DOWNLOADING ||
                download?.status == DownloadStatus.QUEUED
        }

    override suspend fun getTotalStorageUsed(): Long =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val session = sessionManager.currentSession.value ?: return@withContext 0L
                databaseRepository.getTotalBytesForServer(session.serverId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to calculate storage used")
                0L
            }
        }

    override suspend fun getTotalStorageUsedAllServers(): Long =
        withContext(Dispatchers.IO) {
            return@withContext try {
                databaseRepository.getTotalBytesAllServers()
            } catch (e: Exception) {
                Timber.e(e, "Failed to calculate total storage used")
                0L
            }
        }

    suspend fun getDownloadDirectory(): File = downloadStorageManager.getSelectedDownloadsRoot()

    suspend fun getItemDownloadDirectory(itemId: UUID): File {
        val download = databaseRepository.getDownloadByItemId(itemId)
        return downloadStorageManager.getItemDownloadDirectory(download, itemId)
    }

    suspend fun createMediaFileTarget(
        download: DownloadDto,
        itemId: UUID,
        sourceId: String,
        extension: String,
    ): DownloadStorageManager.MediaFileTarget =
        downloadStorageManager.createMediaFileTarget(
            folderPath = download.folderPath,
            itemId = itemId,
            sourceId = sourceId,
            extension = extension,
        )

    suspend fun createSidecarFileTarget(
        download: DownloadDto?,
        itemId: UUID,
        directoryName: String,
        fileName: String,
        mimeType: String,
    ): DownloadStorageManager.SidecarFileTarget =
        downloadStorageManager.createSidecarFileTarget(
            download = download,
            itemId = itemId,
            directoryName = directoryName,
            fileName = fileName,
            mimeType = mimeType,
        )

    suspend fun getShowDirectory(serverId: String, showId: UUID): File =
        File(downloadStorageManager.getSelectedDownloadsRoot(), "$serverId/shows/$showId").also {
            it.mkdirs()
        }

    suspend fun getSeasonDirectory(serverId: String, showId: UUID, seasonNumber: Int): File =
        File(
                downloadStorageManager.getSelectedDownloadsRoot(),
                "$serverId/shows/$showId/seasons/$seasonNumber",
            )
            .also { it.mkdirs() }

    override suspend fun startSeasonDownload(seasonId: UUID, seriesId: UUID?): Result<Int> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val session =
                    sessionManager.currentSession.value
                        ?: return@withContext Result.failure(Exception("No active session"))
                val qualityMode =
                    DownloadQualityMode.fromPreference(preferencesRepository.getDownloadQuality())
                val episodes =
                    mediaRepository.getEpisodes(
                        seasonId = seasonId,
                        seriesId = seriesId,
                        fields =
                            listOf(
                                ItemFields.MEDIA_SOURCES,
                                ItemFields.MEDIA_STREAMS,
                                ItemFields.OVERVIEW,
                            ),
                    )
                val downloads =
                    buildEpisodeQueueRows(
                        episodes = episodes,
                        serverId = session.serverId,
                        userId = session.userId,
                        qualityMode = qualityMode,
                    )
                if (downloads.isNotEmpty()) {
                    databaseRepository.insertDownloads(downloads)
                    scheduleQueue(DownloadQueueScheduleTrigger.USER_ACTION)
                }
                val started = downloads.size
                Timber.i("Season download queued $started/${episodes.size} episodes")
                Result.success(started)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start season download")
                Result.failure(e)
            }
        }

    override suspend fun startSeriesDownload(showId: UUID): Result<Int> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val session =
                    sessionManager.currentSession.value
                        ?: return@withContext Result.failure(Exception("No active session"))
                val qualityMode =
                    DownloadQualityMode.fromPreference(preferencesRepository.getDownloadQuality())
                val seasons = mediaRepository.getSeasons(showId)
                val allDownloads = mutableListOf<DownloadDto>()
                for (season in seasons) {
                    val episodes =
                        mediaRepository.getEpisodes(
                            seasonId = season.id,
                            seriesId = showId,
                            fields =
                                listOf(
                                    ItemFields.MEDIA_SOURCES,
                                    ItemFields.MEDIA_STREAMS,
                                    ItemFields.OVERVIEW,
                                ),
                        )
                    allDownloads +=
                        buildEpisodeQueueRows(
                            episodes = episodes,
                            serverId = session.serverId,
                            userId = session.userId,
                            qualityMode = qualityMode,
                        )
                }
                if (allDownloads.isNotEmpty()) {
                    databaseRepository.insertDownloads(allDownloads)
                    scheduleQueue(DownloadQueueScheduleTrigger.USER_ACTION)
                }
                val totalStarted = allDownloads.size
                Timber.i(
                    "Series download queued $totalStarted episodes across ${seasons.size} seasons"
                )
                Result.success(totalStarted)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start series download")
                Result.failure(e)
            }
        }

    private suspend fun buildEpisodeQueueRows(
        episodes: List<AfinityEpisode>,
        serverId: String,
        userId: UUID,
        qualityMode: DownloadQualityMode,
    ): List<DownloadDto> {
        return episodes.mapNotNull { episode ->
            val existing = databaseRepository.getDownloadByItemIdScoped(episode.id, serverId, userId)
            if (existing?.status == DownloadStatus.COMPLETED) {
                Timber.w("Skipping episode ${episode.name}: already downloaded")
                return@mapNotNull null
            }
            if (
                existing?.status == DownloadStatus.DOWNLOADING ||
                    existing?.status == DownloadStatus.QUEUED
            ) {
                Timber.w("Skipping episode ${episode.name}: already queued/downloading")
                return@mapNotNull null
            }
            val source =
                episode.sources.firstOrNull { it.type == AfinitySourceType.REMOTE }
                    ?: run {
                        Timber.w("Skipping episode ${episode.name}: no remote source")
                        return@mapNotNull null
                    }
            buildQueuedDownload(
                existingDownload = existing,
                item = episode,
                sourceId = source.id,
                serverId = serverId,
                userId = userId,
                qualityMode = qualityMode,
            )
        }
    }

    override suspend fun cancelAllSeriesDownloads(showId: UUID): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val downloads = getAllDownloadsFlow().first()
                val toCancel = downloads.filter {
                    it.seriesId == showId.toString() && it.status != DownloadStatus.COMPLETED
                }
                for (download in toCancel) {
                    cancelDownload(download.id)
                }
                Timber.i("Cancelled ${toCancel.size} downloads for series $showId")
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to cancel series downloads")
                Result.failure(e)
            }
        }

    override suspend fun cancelAllSeasonDownloads(
        seriesId: UUID,
        seasonNumber: Int,
        episodeIds: Set<UUID>,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val downloads = getAllDownloadsFlow().first()
                val toCancel = downloads.filter {
                    val matchesSeason =
                        if (episodeIds.isNotEmpty()) {
                            it.itemId in episodeIds
                        } else {
                            it.seriesId == seriesId.toString() && it.seasonNumber == seasonNumber
                        }
                    matchesSeason && it.status != DownloadStatus.COMPLETED
                }
                for (download in toCancel) {
                    cancelDownload(download.id)
                }
                Timber.i(
                    "Cancelled ${toCancel.size} downloads for series $seriesId season $seasonNumber"
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to cancel season downloads")
                Result.failure(e)
            }
        }
}
