package com.makd.afinity.data.repository.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.makd.afinity.data.repository.download.DownloadRepository
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class DownloadQueueActionReceiver : BroadcastReceiver() {
    @Inject lateinit var downloadRepository: DownloadRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE_ACTIVE -> {
                        val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)?.let(UUID::fromString)
                        if (id != null) {
                            downloadRepository.pauseDownload(id)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to handle download queue notification action")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_ACTIVE = "com.makd.afinity.download.action.PAUSE_ACTIVE"
        const val EXTRA_DOWNLOAD_ID = "download_id"
    }
}
