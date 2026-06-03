package com.makd.afinity.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.makd.afinity.data.repository.download.DownloadQueueScheduleTrigger
import com.makd.afinity.data.repository.download.DownloadQueueScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class MediaDownloadWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadQueueScheduler: DownloadQueueScheduler,
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
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val downloadIdString = inputData.getString(KEY_DOWNLOAD_ID)
            if (downloadIdString == null) {
                Timber.i("Neutralized legacy MediaDownloadWorker with missing download id")
                return@withContext Result.success()
            }

            val downloadId =
                try {
                    UUID.fromString(downloadIdString)
                } catch (e: IllegalArgumentException) {
                    Timber.i(e, "Neutralized legacy MediaDownloadWorker with invalid download id")
                    return@withContext Result.success()
                }

            neutralizeLegacyWorker(downloadId)
            Result.success()
    }

    private suspend fun neutralizeLegacyWorker(downloadId: UUID) {
        downloadQueueScheduler.scheduleQueue(DownloadQueueScheduleTrigger.LEGACY_WORKER)
        Timber.i("Neutralized legacy MediaDownloadWorker for $downloadId")
    }
}
