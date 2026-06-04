package com.makd.afinity.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.makd.afinity.R
import com.makd.afinity.data.manager.OfflineModeManager
import com.makd.afinity.data.manager.SessionManager
import com.makd.afinity.data.models.download.DownloadQueueStatus
import com.makd.afinity.data.models.download.DownloadQueueStatusLabelKind
import com.makd.afinity.data.models.download.DownloadQueueStatusPresentation
import com.makd.afinity.data.repository.KidModeRepository
import com.makd.afinity.data.repository.download.DownloadQueueNotificationFactory
import com.makd.afinity.data.repository.download.DownloadQueueScheduleTrigger
import com.makd.afinity.data.repository.download.DownloadQueueScheduler
import com.makd.afinity.data.repository.download.DownloadQueueStatusRepository
import com.makd.afinity.util.isLocalAddress
import com.makd.afinity.util.isTailscaleAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ConnectionType {
    LOCAL,
    TAILSCALE,
    REMOTE,
    OFFLINE,
}

@HiltViewModel
class AfinityTopAppBarViewModel
@Inject
constructor(
    offlineModeManager: OfflineModeManager,
    sessionManager: SessionManager,
    kidModeRepository: KidModeRepository,
    downloadQueueStatusRepository: DownloadQueueStatusRepository,
    private val downloadQueueScheduler: DownloadQueueScheduler,
) : ViewModel() {
    val canUseSearch =
        combine(kidModeRepository.policy, offlineModeManager.isOffline) { policy, offline ->
            policy.canUseSearch && !offline
        }

    val connectionType =
        combine(
            offlineModeManager.isOffline,
            sessionManager.currentSession.map { it?.serverUrl },
        ) { offline, serverUrl ->
            when {
                offline -> ConnectionType.OFFLINE
                serverUrl != null && isLocalAddress(serverUrl) -> ConnectionType.LOCAL
                serverUrl != null && isTailscaleAddress(serverUrl) -> ConnectionType.TAILSCALE
                else -> ConnectionType.REMOTE
            }
        }

    val downloadQueueStatus = downloadQueueStatusRepository.status

    fun retryDownloadQueueSchedule() {
        viewModelScope.launch(Dispatchers.IO) {
            downloadQueueScheduler.scheduleQueue(DownloadQueueScheduleTrigger.USER_ACTION)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfinityTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onSearchClick: (() -> Unit)? = null,
    onProfileClick: (() -> Unit)? = null,
    userName: String? = null,
    userProfileImageUrl: String? = null,
    backgroundOpacity: Float = 0f,
    actions: @Composable (RowScope.() -> Unit) = {},
    viewModel: AfinityTopAppBarViewModel = hiltViewModel(),
) {
    val connectionType by
        viewModel.connectionType.collectAsStateWithLifecycle(initialValue = ConnectionType.REMOTE)
    val canUseSearch by viewModel.canUseSearch.collectAsStateWithLifecycle(initialValue = true)
    val downloadQueueStatus by
        viewModel.downloadQueueStatus.collectAsStateWithLifecycle(
            initialValue = DownloadQueueStatus.Empty
        )
    val context = LocalContext.current
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.retryDownloadQueueSchedule()
            }
        }

    TopAppBar(
        title = title,
        actions = {
            if (onSearchClick != null && canUseSearch) {
                Button(
                    onClick = onSearchClick,
                    modifier = Modifier.height(42.dp).widthIn(min = 120.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color.Black.copy(alpha = 0.3f)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_search),
                            contentDescription = stringResource(R.string.cd_search_icon),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.top_bar_search_hint),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (downloadQueueStatus.hasVisibleActivity) {
                val queueLabelKind = DownloadQueueStatusPresentation.labelKind(downloadQueueStatus)
                val queueChipClick: (() -> Unit)? =
                    when (queueLabelKind) {
                        DownloadQueueStatusLabelKind.ENABLE_NOTIFICATIONS ->
                            fun() {
                                if (
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                } else {
                                    context.openDownloadNotificationSettings()
                                }
                            }
                        DownloadQueueStatusLabelKind.SCHEDULER_BLOCKED,
                        DownloadQueueStatusLabelKind.QUEUED -> viewModel::retryDownloadQueueSchedule
                        DownloadQueueStatusLabelKind.ACTIVE -> null
                    }
                DownloadQueueStatusChip(
                    status = downloadQueueStatus,
                    onClick = queueChipClick,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (onProfileClick != null) {
                Box(
                    modifier =
                        Modifier.graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                ) {
                    IconButton(onClick = onProfileClick, modifier = Modifier.size(42.dp)) {
                        Box(
                            modifier =
                                Modifier.fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                                    .clip(CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (userProfileImageUrl != null) {
                                AsyncImage(
                                    imageUrl = userProfileImageUrl,
                                    contentDescription = stringResource(R.string.cd_profile_icon),
                                    targetWidth = 48.dp,
                                    targetHeight = 48.dp,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else if (!userName.isNullOrBlank()) {
                                Text(
                                    text = userName.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_user_circle),
                                    contentDescription = stringResource(R.string.cd_profile_icon),
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                    }

                    val indicatorColor =
                        when (connectionType) {
                            ConnectionType.LOCAL -> Color(0xFF4CAF50)
                            ConnectionType.TAILSCALE -> Color(0xFF2196F3)
                            ConnectionType.REMOTE -> Color(0xFFFF9800)
                            ConnectionType.OFFLINE -> Color(0xFFF44336)
                        }
                    val indicatorIcon =
                        when (connectionType) {
                            ConnectionType.LOCAL -> R.drawable.ic_wifi
                            ConnectionType.TAILSCALE -> R.drawable.ic_security
                            ConnectionType.REMOTE -> R.drawable.ic_link
                            ConnectionType.OFFLINE -> R.drawable.ic_cloud_off
                        }
                    val indicatorContentDescription =
                        when (connectionType) {
                            ConnectionType.LOCAL -> stringResource(R.string.cd_local_connection)
                            ConnectionType.TAILSCALE ->
                                stringResource(R.string.cd_tailscale_connection)
                            ConnectionType.REMOTE -> stringResource(R.string.cd_remote_connection)
                            ConnectionType.OFFLINE -> stringResource(R.string.cd_offline_mode)
                        }

                    Box(
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .size(18.dp)
                                .drawBehind {
                                    drawCircle(color = Color.Black, blendMode = BlendMode.Clear)
                                }
                                .padding(1.5.dp)
                                .background(color = indicatorColor, shape = CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = indicatorIcon),
                            contentDescription = indicatorContentDescription,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
            actions()
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = backgroundOpacity)
            ),
        modifier = modifier,
    )
}

@Composable
private fun DownloadQueueStatusChip(status: DownloadQueueStatus, onClick: (() -> Unit)? = null) {
    val label =
        when (DownloadQueueStatusPresentation.labelKind(status)) {
            DownloadQueueStatusLabelKind.ENABLE_NOTIFICATIONS ->
                stringResource(R.string.download_queue_enable_notifications)
            DownloadQueueStatusLabelKind.SCHEDULER_BLOCKED ->
                stringResource(R.string.download_queue_blocked)
            DownloadQueueStatusLabelKind.ACTIVE ->
                stringResource(
                    R.string.download_queue_active_fmt,
                    (status.progress * 100f).toInt().coerceIn(0, 100),
                    status.queuedCount,
                )
            DownloadQueueStatusLabelKind.QUEUED ->
                stringResource(R.string.download_queue_queued_fmt, status.queuedCount)
        }

    Row(
        modifier =
            Modifier.height(42.dp)
                .widthIn(max = 190.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_download_arrow),
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Context.openDownloadNotificationSettings() {
    val channelIntent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, DownloadQueueNotificationFactory.CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val appIntent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val detailsIntent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    for (intent in listOf(channelIntent, appIntent, detailsIntent)) {
        try {
            startActivity(intent)
            return
        } catch (_: Exception) {
            continue
        }
    }
}
