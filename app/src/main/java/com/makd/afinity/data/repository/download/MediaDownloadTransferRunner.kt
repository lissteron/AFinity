package com.makd.afinity.data.repository.download

import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import com.makd.afinity.data.database.entities.DownloadDto
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
import com.makd.afinity.data.models.media.AfinityTrickplayInfo
import com.makd.afinity.data.models.media.toAfinitySegment
import com.makd.afinity.data.repository.DatabaseRepository
import com.makd.afinity.data.repository.PreferencesRepository
import com.makd.afinity.data.storage.DownloadStorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.MediaSegmentsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

@Singleton
class MediaDownloadTransferRunner
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val databaseRepository: DatabaseRepository,
    private val stateStore: DownloadQueueStateStore,
    private val downloadStorageManager: DownloadStorageManager,
    private val preferencesRepository: PreferencesRepository,
    private val sessionRestoreResolver: SessionRestoreResolver,
    private val uidtNetworkSession: UidtNetworkSession,
) {
    companion object {
        const val BUFFER_SIZE = 8192
    }

    suspend fun run(
        claimedDownload: DownloadDto,
        requiredNetwork: Network? = null,
        stopRequest: () -> DownloadQueueStopRequest? = { null },
        progressObserver: suspend (DownloadProgress) -> Unit = {},
    ): TransferResult {
        val activeClaimId =
            claimedDownload.activeClaimId
                ?: run {
                    pauseActiveDownload(claimedDownload.id, "Download queue lease was not persisted")
                    return TransferResult.Paused("Download queue lease was not persisted")
                }
        val activeBackendRunId =
            claimedDownload.activeBackendRunId
                ?: run {
                    pauseActiveDownload(claimedDownload.id, "Download queue lease was not persisted")
                    return TransferResult.Paused("Download queue lease was not persisted")
                }
        var attempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val networkLease =
                if (requiredNetwork != null) {
                    uidtNetworkSession.currentLease()
                        ?: run {
                            val message = "UIDT required network is unavailable"
                            requeueActiveDownload(
                                claimedDownload.id,
                                activeClaimId,
                                activeBackendRunId,
                                message,
                            )
                            return TransferResult.Paused(message)
                        }
                } else {
                    null
                }
            val restored =
                when (
                    val result =
                        sessionRestoreResolver.restore(
                            claimedDownload.serverId,
                            claimedDownload.userId,
                            networkLease,
                        )
                ) {
                    is SessionRestoreResult.Restored -> result.session
                    is SessionRestoreResult.Failed -> {
                        failActiveDownload(
                            claimedDownload.id,
                            activeClaimId,
                            activeBackendRunId,
                            result.message,
                        )
                        return TransferResult.Failed(result.message)
                    }
                    is SessionRestoreResult.Paused -> {
                        if (result.message.isUidtTransientReason()) {
                            requeueActiveDownload(
                                claimedDownload.id,
                                activeClaimId,
                                activeBackendRunId,
                                result.message,
                            )
                        } else {
                            pauseActiveDownload(
                                claimedDownload.id,
                                activeClaimId,
                                activeBackendRunId,
                                result.message,
                            )
                        }
                        return TransferResult.Paused(result.message)
                    }
                }

            try {
                return runAttempt(
                    claimedDownload = claimedDownload,
                    activeClaimId = activeClaimId,
                    activeBackendRunId = activeBackendRunId,
                    session = restored,
                    stopRequest = stopRequest,
                    progressObserver = progressObserver,
                )
            } catch (e: IOException) {
                if (uidtNetworkSession.hasNetworkChangedSince(restored.networkGeneration)) {
                    if (uidtNetworkSession.currentLease() == null) {
                        val message = "UIDT required network is unavailable"
                        requeueActiveDownload(
                            claimedDownload.id,
                            activeClaimId,
                            activeBackendRunId,
                            message,
                        )
                        return TransferResult.Paused(message)
                    }
                    attempts += 1
                    if (attempts <= 4) {
                        Timber.i(e, "UIDT network changed; retrying transfer from persisted byte")
                        continue
                    }
                    val message = "UIDT required network changed repeatedly"
                    requeueActiveDownload(
                        claimedDownload.id,
                        activeClaimId,
                        activeBackendRunId,
                        message,
                    )
                    return TransferResult.Paused(message)
                }
                throw e
            }
        }
    }

    private suspend fun runAttempt(
        claimedDownload: DownloadDto,
        activeClaimId: UUID,
        activeBackendRunId: UUID,
        session: RestoredDownloadSession,
        stopRequest: () -> DownloadQueueStopRequest?,
        progressObserver: suspend (DownloadProgress) -> Unit,
    ): TransferResult {
        var failureStage = "initializing media download"
        val latest =
            databaseRepository.getDownload(claimedDownload.id)
                ?: return TransferResult.Paused("Download row no longer exists")
        if (latest.status != DownloadStatus.DOWNLOADING || latest.activeClaimId != activeClaimId) {
            return TransferResult.Paused("Download no longer owns the active claim")
        }
        if (latest.activeBackendRunId != activeBackendRunId) {
            return TransferResult.Paused("Download no longer owns the active backend lease")
        }

        try {
            failureStage = "reading download preferences"
            val qualityMode =
                DownloadQualityMode.fromPreference(preferencesRepository.getDownloadQuality())
            val itemId = claimedDownload.itemId
            val sourceId = claimedDownload.sourceId
            val apiClient = session.apiClient
            val okHttpClient = session.okHttpClient
            val userId = claimedDownload.userId
            val baseUrl = apiClient.baseUrl ?: session.serverUrl
            val itemsApi = ItemsApi(apiClient)
            failureStage = "fetching item metadata"
            ensureUidtNetworkFresh(session.networkGeneration)
            val baseItemDto =
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
                    ?: throw TerminalTransferException("Item not found")

            failureStage = "converting item metadata"
            val item =
                when (baseItemDto.type) {
                    BaseItemKind.MOVIE -> baseItemDto.toAfinityMovie(baseUrl)
                    BaseItemKind.EPISODE ->
                        baseItemDto.toAfinityEpisode(baseUrl)
                            ?: throw TerminalTransferException("Failed to convert episode")
                    else -> throw TerminalTransferException("Unsupported item type: ${baseItemDto.type}")
                }

            failureStage = "selecting media source"
            val source =
                item.sources.find { it.id == sourceId }
                    ?: throw TerminalTransferException("Source not found")

            failureStage = "building download request"
            val requestPlan = buildDownloadRequestPlan(apiClient, itemId, source, qualityMode)
            failureStage = "creating media target"
            val mediaTarget =
                downloadStorageManager.createMediaFileTarget(
                    folderPath = claimedDownload.folderPath,
                    itemId = itemId,
                    sourceId = sourceId,
                    extension = requestPlan.extension,
                )
            val existingFileSize = if (requestPlan.resumable) mediaTarget.resumeSize else 0L
            val requestBuilder =
                Request.Builder()
                    .url(requestPlan.url)
                    .header("Authorization", "MediaBrowser Token=\"${apiClient.accessToken ?: ""}\"")

            if (requestPlan.resumable && existingFileSize > 0) {
                requestBuilder.header("Range", "bytes=$existingFileSize-")
            }

            Timber.d("Downloading from: ${requestPlan.url.redactSensitiveQueryParams()}")
            Timber.d("Saving to: ${mediaTarget.displayPath}")
            Timber.d("Resuming from byte: $existingFileSize")
            Timber.d("Download request plan: ${requestPlan.description}")

            failureStage = "opening media HTTP response (${requestPlan.description})"
            ensureUidtNetworkFresh(session.networkGeneration)
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 416) {
                        Timber.w("File already fully downloaded (416). Proceeding to completion.")
                    } else {
                        throw TerminalTransferException(
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
                    failureStage = "opening media output stream"
                    mediaTarget.openOutputStream(append = existingFileSize > 0).use { output ->
                        failureStage = "copying media stream"
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            ensureUidtNetworkFresh(session.networkGeneration)
                            val bytes = input.read(buffer)
                            if (bytes == -1) break
                            ensureUidtNetworkFresh(session.networkGeneration)
                            val request = stopRequest()
                            if (request != null) {
                                applyStopRequest(
                                    claimedDownload.id,
                                    activeClaimId,
                                    activeBackendRunId,
                                    request,
                                )
                                return TransferResult.Paused(request.reason)
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
                                if (
                                    !stateStore.updateOwnedProgress(
                                        downloadId = claimedDownload.id,
                                        activeClaimId = activeClaimId,
                                        backendRunId = activeBackendRunId,
                                        progress = progress,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = totalBytes,
                                    )
                                ) {
                                    return TransferResult.Paused(
                                        "Download no longer owns the active claim"
                                    )
                                }
                                progressObserver(
                                    DownloadProgress(
                                        downloadId = claimedDownload.id,
                                        itemName = claimedDownload.itemName,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        progress = progress,
                                    )
                                )
                            }
                        }
                    }
                }
            }

            failureStage = "checking stop request"
            stopRequest()?.let { request ->
                applyStopRequest(claimedDownload.id, activeClaimId, activeBackendRunId, request)
                return TransferResult.Paused(request.reason)
            }

            failureStage = "finalizing media target"
            val completedFile = mediaTarget.finish()
            val completed =
                stateStore.completeOwned(
                    downloadId = claimedDownload.id,
                    activeClaimId = activeClaimId,
                    backendRunId = activeBackendRunId,
                    bytes = completedFile.size,
                    filePath = completedFile.path,
                )
            if (!completed) {
                return TransferResult.Paused("Download no longer owns the active claim")
            }

            val completedDownload =
                databaseRepository.getDownload(claimedDownload.id)
                    ?: return TransferResult.Completed(claimedDownload.id, completedFile.path)
            if (completedDownload.status != DownloadStatus.COMPLETED) {
                return TransferResult.Completed(claimedDownload.id, completedFile.path)
            }

            failureStage = "ensuring downloaded metadata"
            ensureItemInDatabase(
                apiClient,
                okHttpClient,
                claimedDownload.id,
                claimedDownload.serverId,
                baseItemDto,
                userId,
                session.networkGeneration,
            )
            if (!isStillCompleted(claimedDownload.id)) {
                return TransferResult.Completed(claimedDownload.id, completedFile.path)
            }
            failureStage = "downloading sidecar assets"
            runSidecars(
                apiClient = apiClient,
                okHttpClient = okHttpClient,
                download = completedDownload,
                baseItemDto = baseItemDto,
                itemId = itemId,
                source = source,
                baseUrl = baseUrl,
                userId = userId,
                networkGeneration = session.networkGeneration,
            )
            if (!isStillCompleted(claimedDownload.id)) {
                return TransferResult.Completed(claimedDownload.id, completedFile.path)
            }
            failureStage = "creating local media source"
            createLocalSource(
                downloadId = claimedDownload.id,
                itemId = itemId,
                sourceId = sourceId,
                sourceName = source.name,
                path = completedFile.path,
                size = completedFile.size,
                originalStreams = source.mediaStreams,
            )

            Timber.i("Media download completed successfully for: ${claimedDownload.itemName}")
            return TransferResult.Completed(claimedDownload.id, completedFile.path)
        } catch (e: TerminalTransferException) {
            val message = e.toDownloadFailureMessage(failureStage)
            failActiveDownload(
                claimedDownload.id,
                activeClaimId,
                activeBackendRunId,
                message,
            )
            return TransferResult.Failed(message)
        } catch (e: CancellationException) {
            val request = stopRequest()
            if (request != null) {
                applyStopRequest(claimedDownload.id, activeClaimId, activeBackendRunId, request)
            } else {
                pauseActiveDownload(
                    claimedDownload.id,
                    activeClaimId,
                    activeBackendRunId,
                    "Download job stopped",
                )
            }
            throw e
        } catch (e: IOException) {
            if (uidtNetworkSession.hasNetworkChangedSince(session.networkGeneration)) {
                throw e
            }
            val message = e.message ?: "Download interrupted"
            Timber.w(e, "Media download interrupted; pausing active row")
            pauseActiveDownload(claimedDownload.id, activeClaimId, activeBackendRunId, message)
            return TransferResult.Paused(message)
        } catch (e: Exception) {
            val message = e.toDownloadFailureMessage(failureStage)
            Timber.e(e, "Media download failed at $failureStage")
            failActiveDownload(
                claimedDownload.id,
                activeClaimId,
                activeBackendRunId,
                message,
            )
            return TransferResult.Failed(message)
        }
    }

    private suspend fun pauseActiveDownload(downloadId: UUID, message: String) {
        stateStore.pauseActiveDownload(downloadId, message)
    }

    private suspend fun pauseActiveDownload(
        downloadId: UUID,
        activeClaimId: UUID,
        activeBackendRunId: UUID,
        message: String,
    ) {
        stateStore.pauseOwned(downloadId, activeClaimId, activeBackendRunId, message)
    }

    private suspend fun requeueActiveDownload(
        downloadId: UUID,
        activeClaimId: UUID,
        activeBackendRunId: UUID,
        message: String,
    ) {
        stateStore.requeueOwned(downloadId, activeClaimId, activeBackendRunId, message)
    }

    private suspend fun applyStopRequest(
        downloadId: UUID,
        activeClaimId: UUID,
        activeBackendRunId: UUID,
        request: DownloadQueueStopRequest,
    ) {
        when (request.disposition) {
            DownloadQueueStopDisposition.PAUSE ->
                stateStore.pauseOwned(downloadId, activeClaimId, activeBackendRunId, request.reason)
            DownloadQueueStopDisposition.REQUEUE ->
                stateStore.requeueOwned(downloadId, activeClaimId, activeBackendRunId, request.reason)
        }
    }

    private suspend fun failActiveDownload(
        downloadId: UUID,
        activeClaimId: UUID,
        activeBackendRunId: UUID,
        message: String,
    ) {
        stateStore.failOwned(downloadId, activeClaimId, activeBackendRunId, message)
    }

    private suspend fun isStillCompleted(downloadId: UUID): Boolean =
        databaseRepository.getDownload(downloadId)?.status == DownloadStatus.COMPLETED

    private fun ensureUidtNetworkFresh(networkGeneration: Long?) {
        if (uidtNetworkSession.hasNetworkChangedSince(networkGeneration)) {
            throw UidtNetworkChangedException()
        }
    }

    private fun copyNetworkStream(
        input: InputStream,
        output: OutputStream,
        networkGeneration: Long?,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            ensureUidtNetworkFresh(networkGeneration)
            val bytes = input.read(buffer)
            if (bytes == -1) break
            ensureUidtNetworkFresh(networkGeneration)
            output.write(buffer, 0, bytes)
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
                    if (url.queryParameter(key) != null) redacted.setQueryParameter(key, "[REDACTED]")
                }
                redacted.build().toString()
            }
            .getOrElse {
                replace(Regex("(?i)(api_key|apiKey|X-Emby-Token)=[^&]+"), "$1=[REDACTED]")
            }

    private fun Throwable.toDownloadFailureMessage(stage: String): String {
        val className = this::class.java.name
        val detail = message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
        return "$stage: $className: $detail"
    }

    private fun String.isUidtTransientReason(): Boolean = startsWith("UIDT required network")

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
            val windowManager = context.getSystemService(WindowManager::class.java)
            val bounds = windowManager.maximumWindowMetrics.bounds
            return normalizeLandscapeBounds(
                width = bounds.width().coerceAtLeast(1),
                height = bounds.height().coerceAtLeast(1),
            )
        }

        val metrics = context.resources.displayMetrics
        return normalizeLandscapeBounds(
            width = metrics.widthPixels.coerceAtLeast(1),
            height = metrics.heightPixels.coerceAtLeast(1),
        )
    }

    private fun normalizeLandscapeBounds(width: Int, height: Int): DisplaySizePx =
        DisplaySizePx(width = maxOf(width, height), height = minOf(width, height))

    private suspend fun ensureItemInDatabase(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        downloadId: UUID,
        serverId: String,
        baseItemDto: BaseItemDto,
        userId: UUID,
        networkGeneration: Long?,
    ) {
        try {
            ensureUidtNetworkFresh(networkGeneration)
            val baseUrl = apiClient.baseUrl ?: ""
            val itemsApi = ItemsApi(apiClient)
            when (baseItemDto.type) {
                BaseItemKind.MOVIE -> {
                    val movie = baseItemDto.toAfinityMovie(baseUrl)
                    databaseRepository.insertMovieIfDownloadCompleted(downloadId, movie, serverId)
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
                                        runCatching {
                                            ensureUidtNetworkFresh(networkGeneration)
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
                                        }.getOrNull()
                                    }
                                }

                        val seasonDeferred =
                            if (databaseRepository.getSeason(seasonId, userId) == null) {
                                async {
                                    runCatching {
                                        ensureUidtNetworkFresh(networkGeneration)
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
                                    }.getOrNull()
                                }
                            } else null

                        seriesId?.let { showId ->
                            val fetchedShow = seriesDeferred?.await()?.toAfinityShow(baseUrl)
                            if (fetchedShow != null) {
                                if (
                                    !databaseRepository.insertShowIfDownloadCompleted(
                                        downloadId,
                                        fetchedShow,
                                        serverId,
                                    )
                                ) {
                                    return@coroutineScope
                                }
                            }
                            if (fetchedShow != null || databaseRepository.getShow(showId, userId) != null) {
                                downloadShowImages(
                                    apiClient,
                                    okHttpClient,
                                    downloadId,
                                    serverId,
                                    showId,
                                    userId,
                                    networkGeneration,
                                )
                            }
                        }
                        val fetchedSeason = seasonDeferred?.await()?.toAfinitySeason(baseUrl)
                        if (fetchedSeason != null) {
                            if (
                                !databaseRepository.insertSeasonIfDownloadCompleted(
                                    downloadId,
                                    fetchedSeason,
                                    serverId,
                                )
                            ) {
                                return@coroutineScope
                            }
                        }
                        if (fetchedSeason != null || databaseRepository.getSeason(seasonId, userId) != null) {
                            downloadSeasonImages(
                                apiClient,
                                okHttpClient,
                                downloadId,
                                serverId,
                                seasonId,
                                userId,
                                networkGeneration,
                            )
                        }
                    }
                    databaseRepository.insertEpisodeIfDownloadCompleted(downloadId, episode, serverId)
                }
                else -> Timber.w("Unsupported item type: ${baseItemDto.type}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to ensure item is in database")
        }
    }

    private suspend fun runSidecars(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        download: DownloadDto,
        baseItemDto: BaseItemDto,
        itemId: UUID,
        source: AfinitySource,
        baseUrl: String,
        userId: UUID,
        networkGeneration: Long?,
    ) {
        if (!isStillCompleted(download.id)) return
        downloadImages(
            apiClient,
            okHttpClient,
            download,
            itemId,
            download.itemType,
            userId,
            networkGeneration,
        )
        if (!isStillCompleted(download.id)) return
        downloadSegments(apiClient, download.id, itemId, networkGeneration)
        if (!isStillCompleted(download.id)) return
        downloadSubtitles(apiClient, okHttpClient, download, itemId, source, baseUrl, networkGeneration)
        if (!isStillCompleted(download.id)) return
        downloadTrickplay(apiClient, okHttpClient, download, itemId, baseItemDto, baseUrl, networkGeneration)
    }

    private suspend fun downloadImages(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        download: DownloadDto,
        itemId: UUID,
        itemType: String,
        userId: UUID,
        networkGeneration: Long?,
    ) {
        try {
            if (!isStillCompleted(download.id)) return
            val item =
                when (itemType.uppercase()) {
                    "MOVIE" -> databaseRepository.getMovie(itemId, userId)
                    "EPISODE" -> databaseRepository.getEpisode(itemId, userId)
                    else -> null
                } ?: return
            if (!isStillCompleted(download.id)) return
            val itemDir = downloadStorageManager.getItemDownloadDirectory(download, itemId)
            val imagesDir = File(itemDir, "images").also { it.mkdirs() }
            val images = item.images
            val sharedSeriesImages =
                (item as? AfinityEpisode)?.seriesId?.let { seriesId ->
                    databaseRepository.getShow(seriesId, userId)?.images
                }
            val downloadedImages = mutableMapOf<String, Uri?>()

            suspend fun saveImage(uri: Uri?, key: String) {
                if (!uri.isDownloadableRemoteUri()) return
                if (!isStillCompleted(download.id)) return
                val localPath =
                    downloadImage(
                        apiClient,
                        okHttpClient,
                        uri.toString(),
                        imagesDir,
                        key,
                        networkGeneration,
                    )
                if (!isStillCompleted(download.id)) {
                    localPath?.deleteLocalFileUri()
                    return
                }
                if (localPath != null) downloadedImages[key] = localPath
            }

            saveImage(images.primary, "primary")
            saveImage(images.backdrop, "backdrop")
            saveImage(images.thumb, "thumb")
            saveImage(images.logo, "logo")

            val updatedImages =
                AfinityImages(
                    primary = downloadedImages["primary"] ?: images.primary,
                    backdrop = downloadedImages["backdrop"] ?: images.backdrop,
                    thumb = downloadedImages["thumb"] ?: images.thumb,
                    logo = downloadedImages["logo"] ?: images.logo,
                    showPrimary = sharedSeriesImages?.primary ?: images.showPrimary,
                    showBackdrop = sharedSeriesImages?.backdrop ?: images.showBackdrop,
                    showThumb = sharedSeriesImages?.thumb ?: images.showThumb,
                    showLogo = sharedSeriesImages?.logo ?: images.showLogo,
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
                    if (!isStillCompleted(download.id)) return
                    databaseRepository.insertMovieIfDownloadCompleted(
                        download.id,
                        item.copy(images = updatedImages),
                        download.serverId,
                    )
                    downloadPersonImages(
                        apiClient,
                        okHttpClient,
                        download,
                        download.serverId,
                        itemId,
                        userId,
                        networkGeneration,
                    )
                }
                is AfinityEpisode -> {
                    if (!isStillCompleted(download.id)) return
                    val updated =
                        databaseRepository.insertEpisodeIfDownloadCompleted(
                            download.id,
                            item.copy(images = updatedImages),
                            download.serverId,
                        )
                    if (updated) deleteRedundantEpisodeSeriesImages(imagesDir)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to download images")
        }
    }

    private suspend fun downloadShowImages(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        downloadId: UUID,
        serverId: String,
        showId: UUID,
        userId: UUID,
        networkGeneration: Long?,
    ) {
        try {
            val show = databaseRepository.getShow(showId, userId) ?: return
            val showDir = File(downloadStorageManager.getSelectedDownloadsRoot(), "$serverId/shows/$showId").also { it.mkdirs() }
            val imagesDir = File(showDir, "images").also { it.mkdirs() }
            val images = show.images
            val downloadedImages = mutableMapOf<String, Uri?>()
            suspend fun saveImage(uri: Uri?, key: String) {
                if (!uri.isDownloadableRemoteUri()) return
                if (!isStillCompleted(downloadId)) return
                val localPath =
                    downloadImage(
                        apiClient,
                        okHttpClient,
                        uri.toString(),
                        imagesDir,
                        key,
                        networkGeneration,
                    )
                if (!isStillCompleted(downloadId)) {
                    localPath?.deleteLocalFileUri()
                    return
                }
                if (localPath != null) downloadedImages[key] = localPath
            }
            saveImage(images.primary, "primary")
            saveImage(images.backdrop, "backdrop")
            saveImage(images.logo, "logo")
            val updatedImages =
                AfinityImages(
                    primary = downloadedImages["primary"] ?: images.primary,
                    backdrop = downloadedImages["backdrop"] ?: images.backdrop,
                    thumb = images.thumb,
                    logo = downloadedImages["logo"] ?: images.logo,
                    primaryImageBlurHash = images.primaryImageBlurHash,
                    backdropImageBlurHash = images.backdropImageBlurHash,
                    thumbImageBlurHash = images.thumbImageBlurHash,
                    logoImageBlurHash = images.logoImageBlurHash,
                )
            databaseRepository.insertShowIfDownloadCompleted(
                downloadId,
                show.copy(images = updatedImages),
                serverId,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to download show images")
        }
    }

    private suspend fun downloadSeasonImages(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        downloadId: UUID,
        serverId: String,
        seasonId: UUID,
        userId: UUID,
        networkGeneration: Long?,
    ) {
        try {
            val season = databaseRepository.getSeason(seasonId, userId) ?: return
            val seasonDir =
                File(
                        downloadStorageManager.getSelectedDownloadsRoot(),
                        "$serverId/shows/${season.seriesId}/seasons/${season.indexNumber}",
                    )
                    .also { it.mkdirs() }
            val imagesDir = File(seasonDir, "images").also { it.mkdirs() }
            val images = season.images
            val downloadedImages = mutableMapOf<String, Uri?>()
            suspend fun saveImage(uri: Uri?, key: String) {
                if (!uri.isDownloadableRemoteUri()) return
                if (!isStillCompleted(downloadId)) return
                val localPath =
                    downloadImage(
                        apiClient,
                        okHttpClient,
                        uri.toString(),
                        imagesDir,
                        key,
                        networkGeneration,
                    )
                if (!isStillCompleted(downloadId)) {
                    localPath?.deleteLocalFileUri()
                    return
                }
                if (localPath != null) downloadedImages[key] = localPath
            }
            saveImage(images.primary, "primary")
            saveImage(images.backdrop, "backdrop")
            val updatedImages =
                AfinityImages(
                    primary = downloadedImages["primary"] ?: images.primary,
                    backdrop = downloadedImages["backdrop"] ?: images.backdrop,
                    thumb = images.thumb,
                    logo = images.logo,
                    primaryImageBlurHash = images.primaryImageBlurHash,
                    backdropImageBlurHash = images.backdropImageBlurHash,
                    thumbImageBlurHash = images.thumbImageBlurHash,
                    logoImageBlurHash = images.logoImageBlurHash,
                )
            databaseRepository.insertSeasonIfDownloadCompleted(
                downloadId,
                season.copy(images = updatedImages),
                serverId,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to download season images")
        }
    }

    private suspend fun downloadPersonImages(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        download: DownloadDto,
        serverId: String,
        itemId: UUID,
        userId: UUID,
        networkGeneration: Long?,
    ) {
        try {
            val movie = databaseRepository.getMovie(itemId, userId) ?: return
            if (movie.people.isEmpty()) return
            if (!isStillCompleted(download.id)) return
            val movieDir = downloadStorageManager.getItemDownloadDirectory(download, itemId)
            val peopleImagesDir = File(movieDir, "people").also { it.mkdirs() }
            val updatedPeople =
                movie.people.map { person ->
                    person.image.uri?.let { uri ->
                        if (!uri.isDownloadableRemoteUri()) return@map person
                        if (!isStillCompleted(download.id)) return@map person
                        val localPath =
                            downloadImage(
                                apiClient,
                                okHttpClient,
                                uri.toString(),
                                peopleImagesDir,
                                person.id.toString(),
                                networkGeneration,
                            )
                        if (!isStillCompleted(download.id)) {
                            localPath?.deleteLocalFileUri()
                            return@map person
                        }
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
            if (!isStillCompleted(download.id)) return
            databaseRepository.insertMovieIfDownloadCompleted(
                download.id,
                movie.copy(people = updatedPeople),
                serverId,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to download person images")
        }
    }

    private suspend fun downloadImage(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        imageUrl: String,
        outputDir: File,
        baseName: String,
        networkGeneration: Long?,
    ): Uri? {
        var resultUri: Uri? = null
        var outputFile: File? = null
        try {
            if (
                !imageUrl.startsWith("http://", ignoreCase = true) &&
                    !imageUrl.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }
            val request =
                Request.Builder()
                    .url(imageUrl)
                    .header("X-Emby-Token", apiClient.accessToken ?: "")
                    .build()
            ensureUidtNetworkFresh(networkGeneration)
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
                    val targetFile = File(outputDir, "$baseName.$extension")
                    outputFile = targetFile
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            copyNetworkStream(input, output, networkGeneration)
                        }
                    }
                    if (targetFile.exists() && targetFile.length() > 0) {
                        resultUri = Uri.fromFile(targetFile)
                    }
                }
            }
        } catch (e: Exception) {
            outputFile?.delete()
            Timber.w("Failed to download image $baseName: ${e.message}")
        }
        return resultUri
    }

    private fun Uri?.isDownloadableRemoteUri(): Boolean {
        val scheme = this?.scheme ?: return false
        return scheme == "http" || scheme == "https"
    }

    private fun deleteRedundantEpisodeSeriesImages(imagesDir: File) {
        val redundantPrefixes = listOf("showPrimary.", "showBackdrop.", "showThumb.", "showLogo.")
        imagesDir
            .listFiles { _, name -> redundantPrefixes.any { prefix -> name.startsWith(prefix) } }
            ?.forEach { file ->
                if (file.delete()) {
                    Timber.d("Deleted redundant episode series image: ${file.name}")
                }
            }
    }

    private fun Uri.deleteLocalFileUri() {
        if (scheme == "file") {
            path?.let { File(it).delete() }
        }
    }

    private suspend fun downloadSegments(
        apiClient: ApiClient,
        downloadId: UUID,
        itemId: UUID,
        networkGeneration: Long?,
    ) {
        try {
            if (databaseRepository.getSegmentsForItem(itemId).isNotEmpty()) return
            ensureUidtNetworkFresh(networkGeneration)
            val response = MediaSegmentsApi(apiClient).getItemSegments(itemId)
            ensureUidtNetworkFresh(networkGeneration)
            val segments = response.content?.items?.map { it.toAfinitySegment() } ?: emptyList()
            segments.forEach { segment ->
                if (
                    !databaseRepository.insertSegmentIfDownloadCompleted(
                        downloadId,
                        segment,
                        itemId,
                    )
                ) {
                    return
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to download segments")
        }
    }

    private suspend fun downloadSubtitles(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        download: DownloadDto,
        itemId: UUID,
        source: AfinitySource,
        baseUrl: String,
        networkGeneration: Long?,
    ) {
        val subtitleStreams =
            source.mediaStreams.filter { stream ->
                stream.type == MediaStreamType.SUBTITLE && stream.isExternal == true
            }
        subtitleStreams.forEach { stream ->
            runCatching {
                    if (!isStillCompleted(download.id)) return@runCatching
                    val language = stream.language ?: "unknown"
                    val codec = stream.codec ?: "srt"
                    val extension =
                        when (codec.lowercase()) {
                            "subrip",
                            "srt" -> "srt"
                            "ass" -> "ass"
                            "ssa" -> "ssa"
                            "vtt",
                            "webvtt" -> "vtt"
                            else -> "srt"
                        }
                    val outputTarget =
                        createSidecarTargetIfStillCompleted(
                            download = download,
                            itemId = itemId,
                            directoryName = "subtitles",
                            fileName = "${language}_${stream.index}.$extension",
                            mimeType = "text/plain",
                        ) ?: return@runCatching
                    if (!outputTarget.existsAndNonEmpty) {
                        if (!isStillCompleted(download.id)) return@runCatching
                        try {
                            val subtitleUrl =
                                "$baseUrl/Videos/$itemId/${source.id}/Subtitles/${stream.index}/Stream.$extension?api_key=${apiClient.accessToken}"
                            val request =
                                Request.Builder()
                                    .url(subtitleUrl)
                                    .header("Authorization", "MediaBrowser Token=\"${apiClient.accessToken ?: ""}\"")
                                    .build()
                            ensureUidtNetworkFresh(networkGeneration)
                            okHttpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) return@use
                                response.body?.byteStream()?.use { input ->
                                    outputTarget.openOutputStream().use { output ->
                                        copyNetworkStream(input, output, networkGeneration)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            outputTarget.deleteIfExists()
                            throw e
                        }
                        if (!isStillCompleted(download.id)) {
                            outputTarget.deleteIfExists()
                            return@runCatching
                        }
                    }
                    if (outputTarget.existsAndNonEmpty) {
                        if (!isStillCompleted(download.id)) return@runCatching
                        databaseRepository.insertMediaStreamIfDownloadCompleted(
                            download.id,
                            stream.copy(path = outputTarget.uriString, isExternal = true),
                            "${source.id}_local",
                        )
                    }
                }
                .onFailure { Timber.w(it, "Failed to download subtitle: ${stream.language}") }
        }
    }

    private suspend fun downloadTrickplay(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        download: DownloadDto,
        itemId: UUID,
        baseItemDto: BaseItemDto,
        baseUrl: String,
        networkGeneration: Long?,
    ) {
        val item =
            when (baseItemDto.type) {
                BaseItemKind.MOVIE -> baseItemDto.toAfinityMovie(baseUrl)
                BaseItemKind.EPISODE -> baseItemDto.toAfinityEpisode(baseUrl)
                else -> null
            } ?: return
        val trickplayInfo = item.trickplayInfo ?: return
        coroutineScope {
            trickplayInfo.map { (resolution, info) ->
                async {
                    runCatching {
                            downloadTrickplayTiles(
                                apiClient = apiClient,
                                okHttpClient = okHttpClient,
                                download = download,
                                itemId = itemId,
                                resolution = resolution,
                                info = info,
                                baseUrl = baseUrl,
                                networkGeneration = networkGeneration,
                            )
                        }
                        .onFailure {
                            Timber.w(it, "Failed to download trickplay for resolution: $resolution")
                        }
                }
            }.awaitAll()
        }
        trickplayInfo.forEach { (_, info) ->
            runCatching {
                    if (!isStillCompleted(download.id)) return@runCatching
                    databaseRepository.insertTrickplayInfoIfDownloadCompleted(
                        download.id,
                        info,
                        "${download.sourceId}_local",
                    )
                }
                .onFailure { Timber.w(it, "Failed to save trickplay info to database") }
        }
    }

    private suspend fun downloadTrickplayTiles(
        apiClient: ApiClient,
        okHttpClient: OkHttpClient,
        download: DownloadDto,
        itemId: UUID,
        resolution: String,
        info: AfinityTrickplayInfo,
        baseUrl: String,
        networkGeneration: Long?,
    ) {
        val width = info.width
        val thumbnailsPerTile = info.tileWidth * info.tileHeight
        val totalTiles =
            kotlin.math.ceil(info.thumbnailCount.toDouble() / thumbnailsPerTile).toInt()
        val semaphore = Semaphore(4)
        coroutineScope {
            (0 until totalTiles).map { tileIndex ->
                async {
                    semaphore.withPermit {
                        val outputTarget =
                            createSidecarTargetIfStillCompleted(
                                download = download,
                                itemId = itemId,
                                directoryName = "trickplay/$resolution",
                                fileName = "$tileIndex.jpg",
                                mimeType = "image/jpeg",
                            ) ?: return@withPermit
                        if (outputTarget.existsAndNonEmpty) return@withPermit
                        try {
                            val tileUrl =
                                "$baseUrl/Videos/$itemId/Trickplay/$width/$tileIndex.jpg?api_key=${apiClient.accessToken}"
                            val request =
                                Request.Builder()
                                    .url(tileUrl)
                                    .header("Authorization", "MediaBrowser Token=\"${apiClient.accessToken ?: ""}\"")
                                    .build()
                            ensureUidtNetworkFresh(networkGeneration)
                            okHttpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) return@use
                                response.body?.byteStream()?.use { input ->
                                    outputTarget.openOutputStream().use { output ->
                                        copyNetworkStream(input, output, networkGeneration)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            outputTarget.deleteIfExists()
                            throw e
                        }
                        if (!isStillCompleted(download.id)) {
                            outputTarget.deleteIfExists()
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun createSidecarTargetIfStillCompleted(
        download: DownloadDto,
        itemId: UUID,
        directoryName: String,
        fileName: String,
        mimeType: String,
    ): DownloadStorageManager.SidecarFileTarget? {
        if (!isStillCompleted(download.id)) return null
        val target =
            downloadStorageManager.createSidecarFileTarget(
                download = download,
                itemId = itemId,
                directoryName = directoryName,
                fileName = fileName,
                mimeType = mimeType,
            )
        if (!isStillCompleted(download.id)) {
            target.deleteIfExists()
            return null
        }
        return target
    }

    private suspend fun createLocalSource(
        downloadId: UUID,
        itemId: UUID,
        sourceId: String,
        sourceName: String,
        path: String,
        size: Long,
        originalStreams: List<AfinityMediaStream>,
    ) {
        try {
            if (!isStillCompleted(downloadId)) return
            val localSourceId = "${sourceId}_local"
            val localSource =
                AfinitySource(
                    id = localSourceId,
                    name = "$sourceName (Downloaded)",
                    type = AfinitySourceType.LOCAL,
                    path = path,
                    size = size,
                    mediaStreams = emptyList(),
                    downloadId = null,
                )
            if (
                !databaseRepository.insertSourceIfDownloadCompleted(
                    downloadId = downloadId,
                    source = localSource,
                    itemId = itemId,
                )
            ) {
                return
            }
            originalStreams.forEach { stream ->
                if (!isStillCompleted(downloadId)) return
                if (!stream.isExternal) {
                    runCatching {
                            databaseRepository.insertMediaStreamIfDownloadCompleted(
                                downloadId = downloadId,
                                stream = stream.copy(path = path),
                                sourceId = localSourceId,
                            )
                        }
                        .onFailure { Timber.w("Failed to copy stream ${stream.type} to local source") }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create LOCAL source entry")
        }
    }

    data class DownloadProgress(
        val downloadId: UUID,
        val itemName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progress: Float,
    )

    sealed class TransferResult {
        data class Completed(val downloadId: UUID, val filePath: String) : TransferResult()

        data class Paused(val reason: String) : TransferResult()

        data class Failed(val reason: String) : TransferResult()
    }

    private class TerminalTransferException(message: String) : Exception(message)

    private class UidtNetworkChangedException : IOException("UIDT required network changed")

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
}
