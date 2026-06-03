package com.makd.afinity.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class SubtitleDownloadWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_ITEM_ID = "item_id"
        const val KEY_SOURCE_ID = "source_id"
        const val BUFFER_SIZE = 8192
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            Timber.i(
                "Neutralized legacy SubtitleDownloadWorker for ${inputData.getString(KEY_DOWNLOAD_ID)}"
            )
            Result.success()
        }
}
