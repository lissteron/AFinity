package com.makd.afinity.data.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.makd.afinity.R
import com.makd.afinity.data.database.entities.AfinitySourceDto
import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.manager.OfflineModeManager
import com.makd.afinity.data.manager.SessionManager
import com.makd.afinity.data.models.download.DownloadQualityMode
import com.makd.afinity.data.models.download.DownloadStatus
import com.makd.afinity.data.models.extensions.toAfinityEpisode
import com.makd.afinity.data.models.extensions.toAfinityMovie
import com.makd.afinity.data.models.extensions.toAfinitySeason
import com.makd.afinity.data.models.extensions.toAfinityShow
import com.makd.afinity.data.models.media.AfinityEpisode
import com.makd.afinity.data.models.media.AfinityImages
import com.makd.afinity.data.models.media.AfinityMediaStream
import com.makd.afinity.data.models.media.AfinityMovie
import com.makd.afinity.data.models.media.AfinityPersonImage
import com.makd.afinity.data.models.media.AfinitySource
import com.makd.afinity.data.models.media.AfinitySourceType
import com.makd.afinity.data.repository.DatabaseRepository
import com.makd.afinity.data.repository.PreferencesRepository
import com.makd.afinity.data.repository.download.JellyfinDownloadRepository
import com.makd.afinity.data.repository.segments.SegmentsRepository
import com.makd.afinity.di.DownloadClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@HiltWorker
class MediaDownloadWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sessionManager: SessionManager,
    private val databaseRepository: DatabaseRepository,
    private val downloadRepository: JellyfinDownloadRepository,
    private val preferencesRepository: PreferencesRepository,
    private val segmentsRepository: SegmentsRepository,
    private val offlineModeManager: OfflineModeManager,
    @DownloadClient private val okHttpClient: OkHttpClient,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_ITEM_ID = "item_id"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_ITEM_NAME = "item_name"
        const val KEY_ITEM_TYPE = "item_type"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_DOWNLOAD_QUALITY_MODE = "download_quality_mode"
        const val PROGRESS_KEY = "progress"
        const val BUFFER_SIZE = 8192
        private val downloadMutex = Mutex()
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val downloadIdString =
                inputData.getString(KEY_DOWNLOAD_ID)
                    ?: return@withContext Result.failure(
                        workDataOf("error" to "Missing download ID")
                    )

            val downloadId =
                try {
                    UUID.fromString(downloadIdString)
                } catch (e: IllegalArgumentException) {
                    return@withContext Result.failure(workDataOf("error" to "Invalid download ID"))
                }

            val itemIdString =
                inputData.getString(KEY_ITEM_ID)
                    ?: return@withContext Result.failure(workDataOf("error" to "Missing item ID"))

            val itemId =
                try {
                    UUID.fromString(itemIdString)
                } catch (e: IllegalArgumentException) {
                    return@withContext Result.failure(workDataOf("error" to "Invalid item ID"))
                }

            val sourceId =
                inputData.getString(KEY_SOURCE_ID)
                    ?: return@withContext Result.failure(workDataOf("error" to "Missing source ID"))

            val itemName = inputData.getString(KEY_ITEM_NAME) ?: "Unknown"
            val itemType = inputData.getString(KEY_ITEM_TYPE) ?: "Unknown"
            val qualityMode =
                DownloadQualityMode.fromPreference(
                    inputData.getString(KEY_DOWNLOAD_QUALITY_MODE)
                        ?: preferencesRepository.getDownloadQuality()
                )

            if (offlineModeManager.isCurrentlyOffline()) {
                Timber.d("MediaDownloadWorker: offline mode active, pausing $downloadId")
                databaseRepository.getDownload(downloadId)?.let { download ->
                    databaseRepository.insertDownload(
                        download.copy(
                            status = DownloadStatus.PAUSED,
                            error = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
                return@withContext Result.failure(workDataOf("error" to "Offline mode is enabled"))
            }

            try {
                setForeground(createQueuedForegroundInfo(downloadId.hashCode(), itemName))
            } catch (e: Exception) {
                Timber.e(e, "Failed to promote to foreground service")
            }

            downloadMutex.withLock {
                try {
                    setForeground(createForegroundInfo(downloadId.hashCode(), itemName, 0, 0))
                } catch (e: Exception) {
                    Timber.e(e, "Failed to update foreground service to active")
                }

                try {
                    Timber.d("Starting media download for item: $itemName ($itemType)")

                    val download: DownloadDto =
                        databaseRepository.getDownload(downloadId)
                            ?: return@withContext Result.failure(
                                workDataOf("error" to "Download not found")
                            )

                    val apiClient =
                        sessionManager.getOrRestoreApiClient(download.serverId)
                            ?: return@withContext Result.failure(
                                workDataOf(
                                    "error" to
                                        "Could not restore session for server ${download.serverId}"
                                )
                            )

                    databaseRepository.insertDownload(
                        download.copy(
                            status = DownloadStatus.DOWNLOADING,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )

                    val userId = download.userId

                    val baseUrl = apiClient.baseUrl ?: ""

                    val itemsApi = ItemsApi(apiClient)
                    val baseItemDto =
                        try {
                            itemsApi
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
                                            ItemFields.TRICKPLAY,
                                        ),
                                    enableImages = true,
                                    enableUserData = true,
                                )
                                .content
                                ?.items
                                ?.firstOrNull()
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to fetch item details")
                            null
                        }
                            ?: return@withContext Result.failure(
                                workDataOf("error" to "Item not found")
                            )

                    val item =
                        when (baseItemDto.type) {
                            BaseItemKind.MOVIE -> baseItemDto.toAfinityMovie(baseUrl)
                            BaseItemKind.EPISODE ->
                                baseItemDto.toAfinityEpisode(baseUrl)
                                    ?: return@withContext Result.failure(
                                        workDataOf("error" to "Failed to convert episode")
                                    )

                            else ->
                                return@withContext Result.failure(
                                    workDataOf(
                                        "error" to "Unsupported item type: ${baseItemDto.type}"
                                    )
                                )
                        }

                    val source =
                        item.sources.find { it.id == sourceId }
                            ?: return@withContext Result.failure(
                                workDataOf("error" to "Source not found")
                            )

                    val requestPlan = buildDownloadRequestPlan(apiClient, itemId, source, qualityMode)
                    val mediaTarget =
                        downloadRepository.createMediaFileTarget(
                            download = download,
                            itemId = itemId,
                            sourceId = sourceId,
                            extension = requestPlan.extension,
                        )

                    val existingFileSize = if (requestPlan.resumable) mediaTarget.resumeSize else 0L

                    Timber.d("Downloading from: ${requestPlan.url.redactSensitiveQueryParams()}")
                    Timber.d("Saving to: ${mediaTarget.displayPath}")
                    Timber.d("Resuming from byte: $existingFileSize")
                    Timber.d("Download quality mode: ${qualityMode.preferenceValue}")
                    Timber.d("Download request plan: ${requestPlan.description}")

                    val requestBuilder =
                        Request.Builder()
                            .url(requestPlan.url)
                            .header("Authorization", "MediaBrowser Token=\"${apiClient.accessToken ?: ""}\"")

                    if (requestPlan.resumable && existingFileSize > 0) {
                        requestBuilder.header("Range", "bytes=$existingFileSize-")
                    }

                    val request = requestBuilder.build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            if (response.code == 416) {
                                Timber.w(
                                    "File already fully downloaded (416). Proceeding to completion."
                                )
                            } else {
                                throw Exception(
                                    "Download failed: ${response.code} ${response.message}"
                                )
                            }
                        }

                        val remainingBytes = response.body?.contentLength() ?: -1L
                        val totalBytes =
                            if (remainingBytes != -1L) existingFileSize + remainingBytes else -1L

                        var downloadedBytes = existingFileSize
                        var lastUpdateTime = 0L

                        response.body?.byteStream()?.use { input ->
                            mediaTarget.openOutputStream(append = existingFileSize > 0).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytes: Int

                                while (input.read(buffer).also { bytes = it } != -1) {
                                    if (isStopped) {
                                        Timber.d("Download paused/stopped by user")
                                        try {
                                            output.close()
                                        } catch (_: Exception) {}

                                        return@withContext Result.failure(
                                            workDataOf("error" to "Paused")
                                        )
                                    }

                                    output.write(buffer, 0, bytes)
                                    downloadedBytes += bytes

                                    val currentTime = System.currentTimeMillis()
                                    if (
                                        currentTime - lastUpdateTime > 500 ||
                                            (totalBytes > 0 && downloadedBytes == totalBytes)
                                    ) {
                                        lastUpdateTime = currentTime
                                        val progress =
                                            if (totalBytes > 0) {
                                                downloadedBytes.toFloat() / totalBytes.toFloat()
                                            } else {
                                                0f
                                            }
                                        updateProgress(
                                            downloadId,
                                            progress,
                                            downloadedBytes,
                                            totalBytes,
                                        )

                                        setProgressAsync(
                                            workDataOf(
                                                PROGRESS_KEY to progress,
                                                "downloadedBytes" to downloadedBytes,
                                                "totalBytes" to totalBytes,
                                            )
                                        )

                                        setForeground(
                                            createForegroundInfo(
                                                downloadId.hashCode(),
                                                itemName,
                                                downloadedBytes,
                                                totalBytes,
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val completedFile = mediaTarget.finish()
                    Timber.d("Download completed: ${completedFile.path}")

                    val updatedDownload =
                        download.copy(
                            status = DownloadStatus.COMPLETED,
                            progress = 1.0f,
                            bytesDownloaded = completedFile.size,
                            totalBytes = completedFile.size,
                            filePath = completedFile.path,
                            updatedAt = System.currentTimeMillis(),
                        )
                    databaseRepository.insertDownload(updatedDownload)

                    ensureItemInDatabase(apiClient, download.serverId, baseItemDto, userId)
                    downloadImages(apiClient, download.serverId, itemId, itemType, userId)
                    downloadSegments(itemId)
                    createLocalSource(
                        itemId,
                        sourceId,
                        source.name,
                        completedFile.path,
                        completedFile.size,
                        source.mediaStreams,
                    )

                    Timber.i("Media download completed successfully for: $itemName")

                    return@withContext Result.success(
                        workDataOf(
                            KEY_DOWNLOAD_ID to downloadIdString,
                            KEY_ITEM_ID to itemIdString,
                            KEY_SOURCE_ID to sourceId,
                            KEY_FILE_PATH to completedFile.path,
                        )
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Media download failed")
                    try {
                        val download = databaseRepository.getDownload(downloadId)
                        if (download != null) {
                            databaseRepository.insertDownload(
                                download.copy(
                                    status = DownloadStatus.FAILED,
                                    error = e.message ?: "Unknown error",
                                    updatedAt = System.currentTimeMillis(),
                                )
                            )
                        }
                    } catch (dbEx: Exception) {
                        Timber.e(dbEx, "Failed to update download status to FAILED")
                    }
                    return@withContext Result.failure(
                        workDataOf("error" to (e.message ?: "Unknown error"))
                    )
                }
            }
        }

    private suspend fun updateProgress(
        downloadId: UUID,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        try {
            val download = databaseRepository.getDownload(downloadId)
            if (download != null) {
                databaseRepository.insertDownload(
                    download.copy(
                        progress = progress,
                        bytesDownloaded = downloadedBytes,
                        totalBytes = totalBytes,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to update download progress")
        }
    }

    private fun buildDownloadRequestPlan(
        apiClient: ApiClient,
        itemId: UUID,
        source: AfinitySource,
        qualityMode: DownloadQualityMode,
    ): DownloadRequestPlan {
        val sourceExtension = source.container?.lowercase()?.takeIf { it.isNotBlank() } ?: "mkv"
        if (!qualityMode.optimizeVideo) {
            return DownloadRequestPlan(
                url = apiClient.libraryApi.getDownloadUrl(itemId = itemId),
                extension = sourceExtension,
                resumable = true,
                description = "original",
            )
        }

        val decision = decideVideoOptimization(source, qualityMode)
        if (decision.useOriginal) {
            return DownloadRequestPlan(
                url = apiClient.libraryApi.getDownloadUrl(itemId = itemId),
                extension = sourceExtension,
                resumable = true,
                description = decision.reason,
            )
        }

        val extension = qualityMode.outputExtension ?: "mp4"
        return DownloadRequestPlan(
            url = buildOptimizedDownloadUrl(apiClient, itemId, source, extension, decision),
            extension = extension,
            resumable = false,
            description = decision.reason,
        )
    }

    private fun decideVideoOptimization(
        source: AfinitySource,
        qualityMode: DownloadQualityMode,
    ): VideoOptimizationDecision {
        val displaySize = getDeviceDisplayBoundsPx()
        val maxWidth = qualityMode.maxWidth ?: displaySize.width
        val maxHeight = qualityMode.maxHeight ?: displaySize.height
        val videoStream = source.mediaStreams.firstOrNull { it.type == MediaStreamType.VIDEO }
        val sourceWidth = source.width ?: videoStream?.width
        val sourceHeight = source.height ?: videoStream?.height
        val sourceCodec = source.videoCodec ?: videoStream?.codec
        val isHevc = sourceCodec.isHevcCodec()
        val fitsDisplay = fitsWithinBounds(sourceWidth, sourceHeight, maxWidth, maxHeight)
        val targetSize = targetVideoSize(sourceWidth, sourceHeight, maxWidth, maxHeight)
        val hasHdrOrDolbyVision = videoStream.hasHdrOrDolbyVision()
        val adaptiveBitrate =
            adaptiveHevcBitrate(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                qualityMode = qualityMode,
            )
        val targetVideoBitrate = adaptiveBitrate.capAtSourceBitrate(source.bitrate)
        val sourceBitrate = source.bitrate?.takeIf { it > 0 }

        if (hasHdrOrDolbyVision) {
            return VideoOptimizationDecision(
                useOriginal = true,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                videoBitrate = null,
                cpuCoreLimit = qualityMode.cpuCoreLimit,
                qualityCrf = qualityMode.qualityCrf,
                qualityPreset = qualityMode.qualityPreset,
                reason = "original: HDR/Dolby Vision protected",
            )
        }

        if (isHevc && fitsDisplay && !qualityMode.reencodeSuitableHevc) {
            return VideoOptimizationDecision(
                useOriginal = true,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                videoBitrate = null,
                cpuCoreLimit = qualityMode.cpuCoreLimit,
                qualityCrf = qualityMode.qualityCrf,
                qualityPreset = qualityMode.qualityPreset,
                reason = "original: suitable HEVC source",
            )
        }

        if (isHevc && fitsDisplay && sourceBitrate != null && sourceBitrate <= targetVideoBitrate) {
            return VideoOptimizationDecision(
                useOriginal = true,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                videoBitrate = null,
                cpuCoreLimit = qualityMode.cpuCoreLimit,
                qualityCrf = qualityMode.qualityCrf,
                qualityPreset = qualityMode.qualityPreset,
                reason = "original: HEVC source is already below target bitrate",
            )
        }

        return VideoOptimizationDecision(
            useOriginal = false,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            width = targetSize?.width,
            height = targetSize?.height,
            videoBitrate = targetVideoBitrate,
            cpuCoreLimit = qualityMode.cpuCoreLimit,
            qualityCrf = qualityMode.qualityCrf,
            qualityPreset = qualityMode.qualityPreset,
            reason =
                "transcode: codec=$sourceCodec, size=${sourceWidth}x$sourceHeight, " +
                    "targetSize=${targetSize?.width}x${targetSize?.height}, " +
                    "targetBitrate=$targetVideoBitrate, crf=${qualityMode.qualityCrf}, " +
                    "preset=${qualityMode.qualityPreset}",
        )
    }

    private fun buildOptimizedDownloadUrl(
        apiClient: ApiClient,
        itemId: UUID,
        source: AfinitySource,
        extension: String,
        decision: VideoOptimizationDecision,
    ): String {
        val baseUrl = (apiClient.baseUrl ?: "").trimEnd('/')

        Timber.d(
            "Optimized download source=${source.id}, displayCap=${decision.maxWidth}x${decision.maxHeight}, " +
                "targetSize=${decision.width}x${decision.height}, " +
                "targetVideoBitrate=${decision.videoBitrate}, qualityCrf=${decision.qualityCrf}, " +
                "qualityPreset=${decision.qualityPreset}"
        )

        val builder =
            "$baseUrl/Videos/$itemId/stream.$extension"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("mediaSourceId", source.id)
                .addQueryParameter("static", "false")
                .addQueryParameter("container", extension)
                .addQueryParameter("videoCodec", "hevc")
                .addQueryParameter("audioCodec", "aac")
                .addQueryParameter("allowVideoStreamCopy", "false")
                .addQueryParameter("allowAudioStreamCopy", "true")
                .addQueryParameter("enableAutoStreamCopy", "false")
                .addQueryParameter("subtitleMethod", "External")
                .addQueryParameter("context", "Streaming")

        builder.addQueryParameter("maxWidth", decision.maxWidth.toString())
        builder.addQueryParameter("maxHeight", decision.maxHeight.toString())
        decision.width?.let { builder.addQueryParameter("width", it.toString()) }
        decision.height?.let { builder.addQueryParameter("height", it.toString()) }
        decision.videoBitrate?.let { builder.addQueryParameter("videoBitRate", it.toString()) }
        decision.cpuCoreLimit?.let { builder.addQueryParameter("cpuCoreLimit", it.toString()) }
        decision.qualityCrf?.let { builder.addQueryParameter("qualityCrf", it.toString()) }
        decision.qualityPreset?.let { builder.addQueryParameter("qualityPreset", it) }

        return builder.build().toString()
    }

    private fun String.redactSensitiveQueryParams(): String =
        runCatching {
                val url = toHttpUrl()
                val redacted = url.newBuilder()
                listOf("api_key", "apiKey", "X-Emby-Token").forEach { key ->
                    if (url.queryParameter(key) != null) {
                        redacted.setQueryParameter(key, "[REDACTED]")
                    }
                }
                redacted.build().toString()
            }
            .getOrElse {
                replace(Regex("(?i)(api_key|apiKey|X-Emby-Token)=[^&]+"), "$1=[REDACTED]")
            }

    private fun adaptiveHevcBitrate(
        sourceWidth: Int?,
        sourceHeight: Int?,
        maxWidth: Int,
        maxHeight: Int,
        qualityMode: DownloadQualityMode,
    ): Int {
        val sourceLong = listOfNotNull(sourceWidth, sourceHeight).maxOrNull()
        val sourceShort = listOfNotNull(sourceWidth, sourceHeight).minOrNull()
        val targetLong = minOf(sourceLong ?: maxOf(maxWidth, maxHeight), maxOf(maxWidth, maxHeight))
        val targetShort = minOf(sourceShort ?: minOf(maxWidth, maxHeight), minOf(maxWidth, maxHeight))
        val targetPixels = targetLong * targetShort

        return when (qualityMode.qualityBias) {
            DownloadQualityMode.QualityBias.ORIGINAL -> Int.MAX_VALUE
            DownloadQualityMode.QualityBias.QUALITY ->
                when {
                    targetPixels <= 1280 * 720 -> 4_000_000
                    targetPixels <= 1920 * 1080 -> 8_000_000
                    else -> 12_000_000
                }
            DownloadQualityMode.QualityBias.STORAGE_SAVER ->
                when {
                    targetPixels <= 1280 * 720 -> 2_500_000
                    targetPixels <= 1920 * 1080 -> 5_000_000
                    else -> 8_000_000
                }
        }
    }

    private fun AfinityMediaStream?.hasHdrOrDolbyVision(): Boolean {
        if (this == null) return false
        val rangeType = videoRangeType?.name?.lowercase().orEmpty()
        return !videoDoViTitle.isNullOrBlank() ||
            rangeType.contains("hdr") ||
            rangeType.contains("hlg") ||
            rangeType.contains("dovi") ||
            rangeType.contains("dolby")
    }

    private fun String?.isHevcCodec(): Boolean {
        val codec = this?.lowercase()?.replace("-", "") ?: return false
        return codec == "hevc" || codec == "h265" || codec == "x265"
    }

    private fun fitsWithinBounds(
        sourceWidth: Int?,
        sourceHeight: Int?,
        maxWidth: Int,
        maxHeight: Int,
    ): Boolean {
        val width = sourceWidth ?: return false
        val height = sourceHeight ?: return false
        if (width <= 0 || height <= 0) return false
        val sourceLong = maxOf(width, height)
        val sourceShort = minOf(width, height)
        val targetLong = maxOf(maxWidth, maxHeight)
        val targetShort = minOf(maxWidth, maxHeight)
        return sourceLong <= targetLong && sourceShort <= targetShort
    }

    private fun targetVideoSize(
        sourceWidth: Int?,
        sourceHeight: Int?,
        maxWidth: Int,
        maxHeight: Int,
    ): DisplaySizePx? {
        val width = sourceWidth?.takeIf { it > 0 } ?: return null
        val height = sourceHeight?.takeIf { it > 0 } ?: return null
        val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
        val targetWidth = (width * scale).toInt().coerceAtLeast(2).roundDownToEven()
        val targetHeight = (height * scale).toInt().coerceAtLeast(2).roundDownToEven()
        return DisplaySizePx(width = targetWidth, height = targetHeight)
    }

    private fun Int.roundDownToEven(): Int = if (this % 2 == 0) this else this - 1

    private fun Int.capAtSourceBitrate(sourceBitrate: Long?): Int {
        val sourceCap = sourceBitrate?.takeIf { it > 0 } ?: return this
        return minOf(toLong(), sourceCap).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun getDeviceDisplayBoundsPx(): DisplaySizePx {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = applicationContext.getSystemService(WindowManager::class.java)
            val bounds = windowManager.maximumWindowMetrics.bounds
            return normalizeLandscapeBounds(
                width = bounds.width().coerceAtLeast(1),
                height = bounds.height().coerceAtLeast(1),
            )
        }

        val metrics = applicationContext.resources.displayMetrics
        return normalizeLandscapeBounds(
            width = metrics.widthPixels.coerceAtLeast(1),
            height = metrics.heightPixels.coerceAtLeast(1),
        )
    }

    private fun normalizeLandscapeBounds(width: Int, height: Int): DisplaySizePx =
        DisplaySizePx(width = maxOf(width, height), height = minOf(width, height))

    private data class DownloadRequestPlan(
        val url: String,
        val extension: String,
        val resumable: Boolean,
        val description: String,
    )

    private data class VideoOptimizationDecision(
        val useOriginal: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val width: Int? = null,
        val height: Int? = null,
        val videoBitrate: Int?,
        val cpuCoreLimit: Int?,
        val qualityCrf: Int?,
        val qualityPreset: String?,
        val reason: String,
    )

    private data class DisplaySizePx(val width: Int, val height: Int)

    private suspend fun ensureItemInDatabase(
        apiClient: ApiClient,
        serverId: String,
        baseItemDto: BaseItemDto,
        userId: UUID,
    ) {
        try {
            Timber.d("Ensuring item ${baseItemDto.id} is saved to database")
            val baseUrl = apiClient.baseUrl ?: ""
            val itemsApi = ItemsApi(apiClient)

            when (baseItemDto.type) {
                BaseItemKind.MOVIE -> {
                    val movie = baseItemDto.toAfinityMovie(baseUrl)
                    databaseRepository.insertMovie(movie, serverId)
                }

                BaseItemKind.EPISODE -> {
                    val episode = baseItemDto.toAfinityEpisode(baseUrl) ?: return
                    val seriesId = episode.seriesId
                    val seasonId = episode.seasonId

                    coroutineScope {
                        val seriesDeferred =
                            seriesId
                                ?.takeIf { databaseRepository.getShow(it, userId) == null }
                                ?.let { id ->
                                    async {
                                        try {
                                            itemsApi
                                                .getItems(
                                                    userId = userId,
                                                    ids = listOf(id),
                                                    fields =
                                                        listOf(
                                                            ItemFields.OVERVIEW,
                                                            ItemFields.GENRES,
                                                            ItemFields.PEOPLE,
                                                        ),
                                                    enableImages = true,
                                                    enableUserData = true,
                                                )
                                                .content
                                                ?.items
                                                ?.firstOrNull()
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                }

                        val seasonDeferred =
                            if (databaseRepository.getSeason(seasonId, userId) == null) {
                                async {
                                    try {
                                        itemsApi
                                            .getItems(
                                                userId = userId,
                                                ids = listOf(seasonId),
                                                fields = listOf(ItemFields.OVERVIEW),
                                                enableImages = true,
                                                enableUserData = true,
                                            )
                                            .content
                                            ?.items
                                            ?.firstOrNull()
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            } else null

                        seriesId?.let {
                            seriesDeferred?.await()?.toAfinityShow(baseUrl)?.let { show ->
                                databaseRepository.insertShow(show, serverId)
                                downloadShowImages(apiClient, serverId, it, userId)
                            }
                        }

                        seasonDeferred?.await()?.toAfinitySeason(baseUrl)?.let { season ->
                            databaseRepository.insertSeason(season, serverId)
                            downloadSeasonImages(apiClient, serverId, seasonId, userId)
                        }
                    }

                    databaseRepository.insertEpisode(episode, serverId)
                }

                else -> Timber.w("Unsupported item type: ${baseItemDto.type}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to ensure item is in database")
        }
    }

    private suspend fun downloadImages(
        apiClient: ApiClient,
        serverId: String,
        itemId: UUID,
        itemType: String,
        userId: UUID,
    ) {
        try {
            val item =
                when (itemType.uppercase()) {
                    "MOVIE" -> databaseRepository.getMovie(itemId, userId)
                    "EPISODE" -> databaseRepository.getEpisode(itemId, userId)
                    else -> null
                } ?: return

            val itemDir = downloadRepository.getItemDownloadDirectory(itemId)
            val imagesDir = File(itemDir, "images").also { it.mkdirs() }
            val images = item.images
            val downloadedImages = mutableMapOf<String, Uri?>()

            suspend fun saveImage(uri: Uri?, key: String) {
                if (uri == null) return
                val localPath = downloadImage(apiClient, uri.toString(), imagesDir, key)
                if (localPath != null) {
                    downloadedImages[key] = localPath
                }
            }

            saveImage(images.primary, "primary")
            saveImage(images.backdrop, "backdrop")
            saveImage(images.thumb, "thumb")
            saveImage(images.logo, "logo")

            if (itemType.uppercase() == "EPISODE") {
                saveImage(images.showPrimary, "showPrimary")
                saveImage(images.showBackdrop, "showBackdrop")
                saveImage(images.showLogo, "showLogo")
            }

            val updatedImages =
                AfinityImages(
                    primary = downloadedImages["primary"] ?: images.primary,
                    backdrop = downloadedImages["backdrop"] ?: images.backdrop,
                    thumb = downloadedImages["thumb"] ?: images.thumb,
                    logo = downloadedImages["logo"] ?: images.logo,
                    showPrimary = downloadedImages["showPrimary"] ?: images.showPrimary,
                    showBackdrop = downloadedImages["showBackdrop"] ?: images.showBackdrop,
                    showLogo = downloadedImages["showLogo"] ?: images.showLogo,
                    primaryImageBlurHash = images.primaryImageBlurHash,
                    backdropImageBlurHash = images.backdropImageBlurHash,
                    thumbImageBlurHash = images.thumbImageBlurHash,
                    logoImageBlurHash = images.logoImageBlurHash,
                    showPrimaryImageBlurHash = images.showPrimaryImageBlurHash,
                    showBackdropImageBlurHash = images.showBackdropImageBlurHash,
                    showLogoImageBlurHash = images.showLogoImageBlurHash,
                )

            when (item) {
                is AfinityMovie -> {
                    databaseRepository.insertMovie(item.copy(images = updatedImages), serverId)
                    downloadPersonImages(apiClient, serverId, itemId, userId)
                }

                is AfinityEpisode -> {
                    databaseRepository.insertEpisode(item.copy(images = updatedImages), serverId)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to download images")
        }
    }

    private suspend fun downloadShowImages(
        apiClient: ApiClient,
        serverId: String,
        showId: UUID,
        userId: UUID,
    ) {
        try {
            val show = databaseRepository.getShow(showId, userId) ?: return
            val showDir = downloadRepository.getShowDirectory(serverId, showId)
            val imagesDir = File(showDir, "images").also { it.mkdirs() }
            val images = show.images
            val downloadedImages = mutableMapOf<String, Uri?>()

            suspend fun saveImage(uri: Uri?, key: String) {
                if (uri == null) return
                val localPath = downloadImage(apiClient, uri.toString(), imagesDir, key)
                if (localPath != null) downloadedImages[key] = localPath
            }

            saveImage(images.primary, "primary")
            saveImage(images.backdrop, "backdrop")
            saveImage(images.logo, "logo")

            val updatedImages =
                AfinityImages(
                    primary = downloadedImages["primary"] ?: images.primary,
                    backdrop = downloadedImages["backdrop"] ?: images.backdrop,
                    thumb = downloadedImages["thumb"] ?: images.thumb,
                    logo = downloadedImages["logo"] ?: images.logo,
                    primaryImageBlurHash = images.primaryImageBlurHash,
                    backdropImageBlurHash = images.backdropImageBlurHash,
                    thumbImageBlurHash = images.thumbImageBlurHash,
                    logoImageBlurHash = images.logoImageBlurHash,
                )
            databaseRepository.insertShow(show.copy(images = updatedImages), serverId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to download show images")
        }
    }

    private suspend fun downloadSeasonImages(
        apiClient: ApiClient,
        serverId: String,
        seasonId: UUID,
        userId: UUID,
    ) {
        try {
            val season = databaseRepository.getSeason(seasonId, userId) ?: return
            val seasonDir =
                downloadRepository.getSeasonDirectory(serverId, season.seriesId, season.indexNumber)
            val imagesDir = File(seasonDir, "images").also { it.mkdirs() }
            val images = season.images
            val downloadedImages = mutableMapOf<String, Uri?>()

            suspend fun saveImage(uri: Uri?, key: String) {
                if (uri == null) return
                val localPath = downloadImage(apiClient, uri.toString(), imagesDir, key)
                if (localPath != null) downloadedImages[key] = localPath
            }

            saveImage(images.primary, "primary")
            saveImage(images.backdrop, "backdrop")

            val updatedImages =
                AfinityImages(
                    primary = downloadedImages["primary"] ?: images.primary,
                    backdrop = downloadedImages["backdrop"] ?: images.backdrop,
                    thumb = downloadedImages["thumb"] ?: images.thumb,
                    logo = downloadedImages["logo"] ?: images.logo,
                    primaryImageBlurHash = images.primaryImageBlurHash,
                    backdropImageBlurHash = images.backdropImageBlurHash,
                    thumbImageBlurHash = images.thumbImageBlurHash,
                    logoImageBlurHash = images.logoImageBlurHash,
                )
            databaseRepository.insertSeason(season.copy(images = updatedImages), serverId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to download season images")
        }
    }

    private suspend fun downloadPersonImages(
        apiClient: ApiClient,
        serverId: String,
        itemId: UUID,
        userId: UUID,
    ) {
        try {
            val movie = databaseRepository.getMovie(itemId, userId) ?: return
            if (movie.people.isEmpty()) return

            val movieDir = downloadRepository.getItemDownloadDirectory(itemId)
            val peopleImagesDir = File(movieDir, "people").also { it.mkdirs() }

            val updatedPeople =
                movie.people.map { person ->
                    person.image.uri?.let { uri ->
                        val localPath =
                            downloadImage(
                                apiClient,
                                uri.toString(),
                                peopleImagesDir,
                                person.id.toString(),
                            )
                        if (localPath != null) {
                            person.copy(
                                image =
                                    AfinityPersonImage(
                                        uri = localPath,
                                        blurHash = person.image.blurHash,
                                    )
                            )
                        } else person
                    } ?: person
                }
            databaseRepository.insertMovie(movie.copy(people = updatedPeople), serverId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to download person images")
        }
    }

    private suspend fun downloadImage(
        apiClient: ApiClient,
        imageUrl: String,
        outputDir: File,
        baseName: String,
    ): Uri? {
        var resultUri: Uri? = null
        try {
            val request =
                Request.Builder()
                    .url(imageUrl)
                    .header("X-Emby-Token", apiClient.accessToken ?: "")
                    .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val contentType = response.header("Content-Type") ?: "image/jpeg"
                    val extension =
                        when {
                            contentType.contains("png") -> "png"
                            contentType.contains("webp") -> "webp"
                            contentType.contains("gif") -> "gif"
                            else -> "jpg"
                        }
                    val outputFile = File(outputDir, "$baseName.$extension")

                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(outputFile).use { output -> input.copyTo(output) }
                    }

                    if (outputFile.exists() && outputFile.length() > 0) {
                        resultUri = Uri.fromFile(outputFile)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w("Failed to download image $baseName: ${e.message}")
        }
        return resultUri
    }

    private suspend fun downloadSegments(itemId: UUID) {
        try {
            segmentsRepository.getSegments(itemId)
        } catch (e: Exception) {
            Timber.w(e, "Failed to download segments")
        }
    }

    private suspend fun createLocalSource(
        itemId: UUID,
        sourceId: String,
        sourceName: String,
        path: String,
        size: Long,
        originalStreams: List<AfinityMediaStream>,
    ) {
        try {
            val localSourceId = "${sourceId}_local"
            val localSource =
                AfinitySourceDto(
                    id = localSourceId,
                    itemId = itemId,
                    name = "$sourceName (Downloaded)",
                    type = AfinitySourceType.LOCAL,
                    path = path,
                    downloadId = null,
                )
            databaseRepository.insertSource(
                AfinitySource(
                    id = localSource.id,
                    name = localSource.name,
                    type = localSource.type,
                    path = localSource.path,
                    size = size,
                    mediaStreams = emptyList(),
                    downloadId = null,
                ),
                itemId,
            )
            originalStreams.forEach { stream ->
                if (!stream.isExternal) {
                    try {
                        databaseRepository.insertMediaStream(
                            stream = stream.copy(path = path),
                            sourceId = localSourceId,
                        )
                    } catch (e: Exception) {
                        Timber.w("Failed to copy stream ${stream.type} to local source")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create LOCAL source entry")
        }
    }

    private fun createQueuedForegroundInfo(notificationId: Int, itemName: String): ForegroundInfo {
        val channelId = "download_channel"
        val context: Context = applicationContext
        val channel =
            NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background download tasks"
            }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
        val notification =
            NotificationCompat.Builder(context, channelId)
                .setContentTitle(itemName)
                .setContentText("Queued")
                .setSmallIcon(R.drawable.ic_download)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun createForegroundInfo(
        notificationId: Int,
        itemName: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ): ForegroundInfo {
        val context: Context = applicationContext
        val channelId = "download_channel"
        val title = "Downloading $itemName"
        val channel =
            NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background download tasks"
            }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)

        val progressText =
            if (totalBytes > 0) {
                "${(downloadedBytes * 100 / totalBytes)}%"
            } else if (downloadedBytes > 0) {
                "Downloaded ${downloadedBytes / (1024 * 1024)} MB"
            } else {
                "Starting..."
            }

        val notification =
            NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(progressText)
                .setSmallIcon(R.drawable.ic_download)
                .setOngoing(true)
                .setProgress(
                    if (totalBytes > 0) totalBytes.toInt() else 0,
                    if (totalBytes > 0) downloadedBytes.toInt() else 0,
                    totalBytes <= 0,
                )
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
