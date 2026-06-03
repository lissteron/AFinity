package com.makd.afinity.data.repository.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.makd.afinity.MainActivity
import com.makd.afinity.R
import com.makd.afinity.data.models.download.DownloadQueueStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadQueueNotificationFactory
@Inject
constructor(@param:ApplicationContext private val context: Context) {
    companion object {
        const val CHANNEL_ID = "download_channel"
        const val NOTIFICATION_ID = 1002
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background download tasks"
            }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPostRequiredNotification(): Boolean {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun buildQueueNotification(
        status: DownloadQueueStatus = DownloadQueueStatus.Empty,
        progress: MediaDownloadTransferRunner.DownloadProgress? = null,
    ): Notification {
        ensureChannel()
        val title =
            when {
                progress != null -> "Downloading ${progress.itemName}"
                status.itemTitle != null -> "Downloading ${status.itemTitle}"
                status.queuedCount > 0 -> "Download queue"
                else -> "Downloads"
            }
        val downloadedBytes = progress?.downloadedBytes
        val totalBytes = progress?.totalBytes
        val text =
            when {
                totalBytes != null && totalBytes > 0L && downloadedBytes != null ->
                    "${downloadedBytes * 100 / totalBytes}%"
                downloadedBytes != null && downloadedBytes > 0L ->
                    "Downloaded ${downloadedBytes / (1024 * 1024)} MB"
                status.queuedCount > 0 -> "${status.queuedCount} queued"
                else -> "Starting..."
            }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(
                if (totalBytes != null && totalBytes > 0L) totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                if (totalBytes != null && totalBytes > 0L && downloadedBytes != null) {
                    downloadedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else 0,
                totalBytes == null || totalBytes <= 0L,
            )
            .apply {
                val activeId = progress?.downloadId ?: status.activeDownloadId
                if (activeId != null) {
                    addAction(
                        R.drawable.ic_player_pause_filled,
                        "Pause",
                        pauseIntent(activeId),
                    )
                }
            }
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pauseIntent(downloadId: UUID): PendingIntent {
        val intent =
            Intent(context, DownloadQueueActionReceiver::class.java)
                .setAction(DownloadQueueActionReceiver.ACTION_PAUSE_ACTIVE)
                .putExtra(DownloadQueueActionReceiver.EXTRA_DOWNLOAD_ID, downloadId.toString())
        return PendingIntent.getBroadcast(
            context,
            downloadId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
