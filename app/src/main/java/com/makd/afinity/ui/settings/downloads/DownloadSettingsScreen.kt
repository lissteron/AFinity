package com.makd.afinity.ui.settings.downloads

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makd.afinity.R
import com.makd.afinity.data.manager.OfflineModeManager
import com.makd.afinity.data.models.audiobookshelf.AbsDownloadInfo
import com.makd.afinity.data.models.audiobookshelf.AbsDownloadStatus
import com.makd.afinity.data.models.download.DownloadInfo
import com.makd.afinity.data.models.download.DownloadQualityMode
import com.makd.afinity.data.models.download.DownloadStorageLocation
import com.makd.afinity.data.models.download.DownloadStatus
import com.makd.afinity.navigation.LocalPlayerOffset
import com.makd.afinity.ui.components.AsyncImage
import com.makd.afinity.ui.downloads.ArtworkRefreshUiState
import com.makd.afinity.ui.downloads.DownloadsViewModel
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSettingsScreen(
    onBackClick: () -> Unit,
    onNavigateToAbsItem: (libraryItemId: String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
    offlineModeManager: OfflineModeManager,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val capabilityPolicy by viewModel.capabilityPolicy.collectAsStateWithLifecycle()
    val isOffline by offlineModeManager.hardOffline.collectAsStateWithLifecycle(initialValue = false)
    val snackbarHostState = remember { SnackbarHostState() }
    val playerOffset = LocalPlayerOffset.current
    val folderPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) viewModel.setCustomDownloadStorageLocation(uri)
        }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.pref_downloads_and_storage),
                        style =
                            MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_left),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val customPadding =
            PaddingValues(
                top = innerPadding.calculateTopPadding(),
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = max(innerPadding.calculateBottomPadding(), playerOffset),
            )
        val absBooks =
            remember(uiState.absCompletedDownloads) {
                uiState.absCompletedDownloads.filter { it.episodeId == null }
            }
        val absPodcastGroups =
            remember(uiState.absCompletedDownloads) {
                uiState.absCompletedDownloads
                    .filter { it.episodeId != null }
                    .groupBy { it.libraryItemId }
            }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    top = customPadding.calculateTopPadding(),
                    start = customPadding.calculateStartPadding(layoutDirection),
                    end = customPadding.calculateEndPadding(layoutDirection),
                    bottom = customPadding.calculateBottomPadding() + 32.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                StatusHub(
                    totalStorageUsed = uiState.totalStorageUsed,
                    totalStorageUsedAllServers = uiState.totalStorageUsedAllServers,
                    downloadCount = uiState.activeDownloads.size + uiState.completedDownloads.size,
                    isOffline = isOffline,
                    wifiOnly = uiState.downloadOverWifiOnly,
                    deviceStats = uiState.deviceStorageStats,
                    onWifiOnlyChanged = viewModel::setDownloadOverWifiOnly,
                    canModify = capabilityPolicy.canManageDownloads,
                    formatSize = viewModel::formatStorageSize,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(
                    title = stringResource(R.string.section_storage_cache),
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            item {
                DownloadStorageLocationCard(
                    locations = uiState.storageLocations,
                    hasActiveDownloads = uiState.activeDownloads.isNotEmpty(),
                    onLocationSelected = viewModel::setDownloadStorageLocation,
                    onChooseFolder = { folderPickerLauncher.launch(null) },
                    canModify = capabilityPolicy.canManageDownloads,
                    formatSize = viewModel::formatStorageSize,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item {
                DownloadQualityModeCard(
                    selectedMode = uiState.downloadQualityMode,
                    onModeSelected = viewModel::setDownloadQualityMode,
                    canModify = capabilityPolicy.canManageDownloads,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item {
                ImageCacheSettingsCard(
                    isCacheEnabled = uiState.isImageCacheEnabled,
                    cacheSizeMb = uiState.imageCacheSizeMb.toFloat(),
                    cacheStorageUsed = uiState.imageCacheStorageUsed,
                    downloadedImageStorageUsed = uiState.downloadedImageStorageUsed,
                    completedVideoCount =
                        uiState.completedDownloads.count { download ->
                            download.itemType.uppercase() in setOf("MOVIE", "EPISODE")
                        },
                    artworkRefresh = uiState.artworkRefresh,
                    onCacheEnabledChange = viewModel::setImageCacheEnabled,
                    onCacheSizeChange = { viewModel.setImageCacheSizeMb(it.toInt()) },
                    onRefreshDownloadedArtwork = viewModel::refreshDownloadedArtwork,
                    onCancelArtworkRefresh = viewModel::cancelArtworkRefresh,
                    canModify = capabilityPolicy.canManageDownloads,
                    formatSize = viewModel::formatStorageSize,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            val allActiveCount = uiState.activeDownloads.size + uiState.absActiveDownloads.size
            if (allActiveCount > 0) {
                item {
                    SectionHeader(
                        title =
                            stringResource(R.string.active_downloads_header_fmt, allActiveCount),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }

                items(uiState.activeDownloads.reversed(), key = { "jf_${it.id}" }) { download ->
                    ActiveDownloadCard(
                        download = download,
                        onPause = viewModel::pauseDownload,
                        onResume = viewModel::resumeDownload,
                        onCancel = viewModel::cancelDownload,
                        formatSize = viewModel::formatStorageSize,
                        showControls = capabilityPolicy.canManageDownloads,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                items(uiState.absActiveDownloads.reversed(), key = { "abs_${it.id}" }) { download ->
                    AbsActiveDownloadCard(
                        download = download,
                        onCancel = viewModel::cancelAbsDownload,
                        formatSize = viewModel::formatStorageSize,
                        showControls = capabilityPolicy.canManageDownloads,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            val absUniqueItemCount = absBooks.size + absPodcastGroups.size
            val allCompletedCount = uiState.completedDownloads.size + absUniqueItemCount
            if (allCompletedCount > 0) {
                item {
                    SectionHeader(
                        title =
                            stringResource(
                                R.string.completed_downloads_header_fmt,
                                allCompletedCount,
                            ),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }

                if (uiState.completedDownloads.isNotEmpty()) {
                    if (absUniqueItemCount > 0) {
                        item {
                            SectionHeader(
                                title = stringResource(R.string.section_videos),
                                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                            )
                        }
                    }
                    items(uiState.completedDownloads, key = { "jf_${it.id}" }) { download ->
                        CompletedDownloadRow(
                            download = download,
                            onDelete = viewModel::deleteDownload,
                            formatSize = viewModel::formatStorageSize,
                            showDelete = capabilityPolicy.canDeleteDownloads,
                        )
                    }
                }

                if (absBooks.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.section_download_audiobooks),
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                    items(absBooks, key = { "abs_book_${it.id}" }) { download ->
                        AbsCompletedDownloadRow(
                            download = download,
                            onClick = { onNavigateToAbsItem(download.libraryItemId) },
                            onDelete = viewModel::deleteAbsDownload,
                            formatSize = viewModel::formatStorageSize,
                            showDelete = capabilityPolicy.canDeleteDownloads,
                        )
                    }
                }

                if (absPodcastGroups.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.section_download_podcasts),
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                    absPodcastGroups.forEach { (libraryItemId, episodes) ->
                        item(key = "abs_podcast_$libraryItemId") {
                            AbsPodcastGroupRow(
                                libraryItemId = libraryItemId,
                                episodes = episodes,
                                onClick = { onNavigateToAbsItem(libraryItemId) },
                                onDelete = { viewModel.deleteAbsPodcast(libraryItemId) },
                                formatSize = viewModel::formatStorageSize,
                                showDelete = capabilityPolicy.canDeleteDownloads,
                            )
                        }
                    }
                }
            }

            if (allActiveCount == 0 && allCompletedCount == 0) {
                item { EmptyDownloadsState() }
            }
        }
    }
}

@Composable
fun StatusHub(
    totalStorageUsed: Long,
    totalStorageUsedAllServers: Long,
    downloadCount: Int,
    isOffline: Boolean,
    wifiOnly: Boolean,
    deviceStats: DownloadsViewModel.DeviceStorageStats?,
    onWifiOnlyChanged: (Boolean) -> Unit,
    canModify: Boolean = true,
    formatSize: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        strokeWidth = 6.dp,
                    )

                    val progress = deviceStats?.usagePercentage ?: 0f

                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxSize(),
                        color =
                            if (progress > 0.9f) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        strokeWidth = 6.dp,
                        strokeCap = StrokeCap.Round,
                    )

                    Icon(
                        painter = painterResource(id = R.drawable.ic_database),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.storage_this_server),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatSize(totalStorageUsed),
                        style =
                            MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp,
                                letterSpacing = (-1).sp,
                            ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    if (totalStorageUsedAllServers > totalStorageUsed) {
                        Text(
                            text =
                                stringResource(
                                    R.string.storage_all_servers_fmt,
                                    formatSize(totalStorageUsedAllServers),
                                ),
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val freeSpaceText = deviceStats?.freeBytes?.let { formatSize(it) } ?: "..."
                    Text(
                        text = stringResource(R.string.storage_free_on_device_fmt, freeSpaceText),
                        style =
                            MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color =
                            if ((deviceStats?.usagePercentage ?: 0f) > 0.9f)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(100),
                    color =
                        if (isOffline) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    id =
                                        if (isOffline) R.drawable.ic_wifi_off
                                        else R.drawable.ic_wifi
                                ),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint =
                                if (isOffline) MaterialTheme.colorScheme.onTertiaryContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text =
                                if (isOffline) stringResource(R.string.network_status_offline)
                                else stringResource(R.string.network_status_online),
                            style =
                                MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                            color =
                                if (isOffline) MaterialTheme.colorScheme.onTertiaryContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.pref_download_wifi_only_title),
                        style =
                            MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Medium
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = onWifiOnlyChanged,
                        enabled = canModify,
                        modifier = Modifier.scale(0.8f),
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor =
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadStorageLocationCard(
    locations: List<DownloadStorageLocation>,
    hasActiveDownloads: Boolean,
    onLocationSelected: (String) -> Unit,
    onChooseFolder: () -> Unit,
    canModify: Boolean = true,
    formatSize: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    if (locations.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = {
                    Text(
                        text = stringResource(R.string.download_location_title),
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                    )
                },
                supportingContent = {
                    Text(
                        text =
                            if (hasActiveDownloads) {
                                stringResource(R.string.download_location_active_warning)
                            } else {
                                stringResource(R.string.download_location_summary)
                            },
                        color =
                            if (hasActiveDownloads) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_folder),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    TextButton(enabled = canModify && !hasActiveDownloads, onClick = onChooseFolder) {
                        Text(stringResource(R.string.download_location_choose_folder))
                    }
                },
            )

            locations.forEach { location ->
                ListItem(
                    modifier =
                        Modifier.clickable(
                            enabled = canModify && !hasActiveDownloads && !location.isSelected,
                            onClick = { onLocationSelected(location.id) },
                        ),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        RadioButton(
                            selected = location.isSelected,
                            enabled = canModify && !hasActiveDownloads,
                            onClick = { onLocationSelected(location.id) },
                        )
                    },
                    headlineContent = {
                        Text(
                            text = location.name,
                            style =
                                MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                        )
                    },
                    supportingContent = {
                        Column {
                            Text(
                                text = location.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = location.path,
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = locationStorageLabel(location, formatSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun locationStorageLabel(
    location: DownloadStorageLocation,
    formatSize: (Long) -> String,
): String =
    if (location.isCustom) {
        stringResource(R.string.download_location_custom_permission)
    } else {
        stringResource(R.string.download_location_free_fmt, formatSize(location.freeBytes))
}

@Composable
fun DownloadQualityModeCard(
    selectedMode: DownloadQualityMode,
    onModeSelected: (DownloadQualityMode) -> Unit,
    canModify: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = {
                    Text(
                        text = stringResource(R.string.download_quality_mode_title),
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                    )
                },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.download_quality_mode_summary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_video_settings),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )

            DownloadQualityMode.entries.forEach { mode ->
                ListItem(
                    modifier =
                        Modifier.clickable(
                            enabled = canModify && selectedMode != mode,
                            onClick = { onModeSelected(mode) },
                        ),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        RadioButton(
                            selected = selectedMode == mode,
                            enabled = canModify,
                            onClick = { onModeSelected(mode) },
                        )
                    },
                    headlineContent = {
                        Text(
                            text =
                                stringResource(
                                    when (mode) {
                                        DownloadQualityMode.ORIGINAL ->
                                            R.string.download_quality_original_title
                                        DownloadQualityMode.HEVC_QUALITY ->
                                            R.string.download_quality_hevc_quality_title
                                        DownloadQualityMode.HEVC_STORAGE_SAVER ->
                                            R.string.download_quality_hevc_storage_saver_title
                                    }
                                ),
                            style =
                                MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                        )
                    },
                    supportingContent = {
                        Text(
                            text =
                                stringResource(
                                    when (mode) {
                                        DownloadQualityMode.ORIGINAL ->
                                            R.string.download_quality_original_summary
                                        DownloadQualityMode.HEVC_QUALITY ->
                                            R.string.download_quality_hevc_quality_summary
                                        DownloadQualityMode.HEVC_STORAGE_SAVER ->
                                            R.string.download_quality_hevc_storage_saver_summary
                                    }
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun ActiveDownloadCard(
    download: DownloadInfo,
    onPause: (UUID) -> Unit,
    onResume: (UUID) -> Unit,
    onCancel: (UUID) -> Unit,
    formatSize: (Long) -> String,
    showControls: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val isEpisode = download.itemType.equals("Episode", ignoreCase = true)
    val imageRatio = if (isEpisode) 16f / 9f else 2f / 3f

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                imageUrl = download.imageUrl,
                contentDescription = download.itemName,
                modifier =
                    Modifier.width(if (isEpisode) 120.dp else 80.dp)
                        .aspectRatio(imageRatio)
                        .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.itemName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val subtitle =
                    if (isEpisode && !download.seriesName.isNullOrBlank()) {
                        download.seriesName
                    } else if (!isEpisode && !download.releaseYear.isNullOrBlank()) {
                        download.releaseYear
                    } else {
                        download.sourceName
                    }

                Text(
                    text = subtitle ?: download.sourceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (download.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { download.progress },
                        modifier =
                            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color =
                            if (download.status == DownloadStatus.FAILED)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier =
                            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color =
                            if (download.status == DownloadStatus.FAILED)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        val statusText =
                            when (download.status) {
                                DownloadStatus.QUEUED ->
                                    stringResource(R.string.download_status_queued).uppercase()
                                DownloadStatus.DOWNLOADING ->
                                    stringResource(R.string.download_status_downloading).uppercase()
                                DownloadStatus.PAUSED ->
                                    stringResource(R.string.download_status_paused).uppercase()
                                DownloadStatus.FAILED ->
                                    stringResource(
                                            R.string.download_status_failed_fmt,
                                            download.error ?: "",
                                        )
                                        .uppercase()
                                else -> ""
                            }

                        Text(
                            text = statusText,
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                            color =
                                if (download.status == DownloadStatus.FAILED)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        )

                        Text(
                            text =
                                if (download.totalBytes > 0) {
                                    "${formatSize(download.bytesDownloaded)} / ${formatSize(download.totalBytes)}"
                                } else {
                                    formatSize(download.bytesDownloaded)
                                },
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (showControls) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (
                                download.status == DownloadStatus.DOWNLOADING ||
                                    download.status == DownloadStatus.QUEUED
                            ) {
                                IconButton(
                                    onClick = { onPause(download.id) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painter =
                                            painterResource(id = R.drawable.ic_player_pause_filled),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else if (
                                download.status == DownloadStatus.PAUSED ||
                                    download.status == DownloadStatus.FAILED
                            ) {
                                IconButton(
                                    onClick = { onResume(download.id) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = R.drawable.ic_player_play_filled
                                            ),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }

                            IconButton(
                                onClick = { onCancel(download.id) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_cancel),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompletedDownloadRow(
    download: DownloadInfo,
    onDelete: (UUID) -> Unit,
    formatSize: (Long) -> String,
    showDelete: Boolean = true,
) {
    val isEpisode = download.itemType.equals("Episode", ignoreCase = true)

    val runtimeMinutes = (download.runtimeTicks ?: 0L) / 10000000 / 60
    val runtimeStr = if (runtimeMinutes > 0) "${runtimeMinutes}m • " else ""

    val subtitleText = buildString {
        if (isEpisode) {
            if (!download.seriesName.isNullOrBlank()) append("${download.seriesName} • ")
            if (download.seasonNumber != null && download.episodeNumber != null) {
                append(
                    "S${download.seasonNumber.toString().padStart(2, '0')}:E${download.episodeNumber.toString().padStart(2, '0')} • "
                )
            }
        } else {
            if (!download.releaseYear.isNullOrBlank()) append("${download.releaseYear} • ")
        }
        append(runtimeStr)
        append(formatSize(download.totalBytes))
    }

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            AsyncImage(
                imageUrl =
                    if (isEpisode) download.seriesImageUrl ?: download.imageUrl
                    else download.imageUrl,
                contentDescription = null,
                modifier =
                    Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        },
        headlineContent = {
            Text(
                text = download.itemName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent =
            if (showDelete) {
                {
                    IconButton(onClick = { onDelete(download.id) }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.cd_delete_download),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else null,
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

@Composable
fun EmptyDownloadsState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
            modifier = Modifier.size(120.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.empty_downloads_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.empty_downloads_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun AbsActiveDownloadCard(
    download: AbsDownloadInfo,
    onCancel: (UUID) -> Unit,
    formatSize: (Long) -> String,
    showControls: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                imageUrl = download.coverUrl,
                contentDescription = download.title,
                modifier = Modifier.width(64.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!download.authorName.isNullOrBlank()) {
                    Text(
                        text = download.authorName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { download.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color =
                        if (download.status == AbsDownloadStatus.FAILED)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        val statusText =
                            when (download.status) {
                                AbsDownloadStatus.QUEUED -> "QUEUED"
                                AbsDownloadStatus.DOWNLOADING ->
                                    "TRACK ${download.tracksDownloaded}/${download.tracksTotal}"
                                AbsDownloadStatus.FAILED -> "FAILED"
                                else -> ""
                            }
                        Text(
                            text = statusText,
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                            color =
                                if (download.status == AbsDownloadStatus.FAILED)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = formatSize(download.bytesDownloaded),
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (showControls) {
                        IconButton(
                            onClick = { onCancel(download.id) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_cancel),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AbsCompletedDownloadRow(
    download: AbsDownloadInfo,
    onClick: () -> Unit = {},
    onDelete: (UUID) -> Unit,
    formatSize: (Long) -> String,
    showDelete: Boolean = true,
) {
    val durationMinutes = (download.duration / 60).toInt()
    val durationStr =
        when {
            durationMinutes >= 60 -> "${durationMinutes / 60}h ${durationMinutes % 60}m"
            durationMinutes > 0 -> "${durationMinutes}m"
            else -> ""
        }
    val subtitleText = buildString {
        if (!download.authorName.isNullOrBlank()) append("${download.authorName} • ")
        if (durationStr.isNotEmpty()) append("$durationStr • ")
        append(formatSize(download.bytesDownloaded))
    }

    ListItem(
        modifier = Modifier.clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            AsyncImage(
                imageUrl = download.coverUrl,
                contentDescription = null,
                modifier = Modifier.width(56.dp).aspectRatio(1f).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        },
        headlineContent = {
            Text(
                text = download.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent =
            if (showDelete) {
                {
                    IconButton(onClick = { onDelete(download.id) }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.cd_delete_download),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else null,
    )
}

@Composable
fun AbsPodcastGroupRow(
    libraryItemId: String,
    episodes: List<AbsDownloadInfo>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    formatSize: (Long) -> String,
    showDelete: Boolean = true,
) {
    val first = episodes.first()
    val podcastName = first.authorName?.takeIf { it.isNotBlank() } ?: first.title
    val totalBytes = episodes.sumOf { it.bytesDownloaded }
    val count = episodes.size
    val subtitleText =
        "$count episode${if (count > 1) "s" else ""} \u00B7 ${formatSize(totalBytes)}"

    ListItem(
        modifier = Modifier.clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            AsyncImage(
                imageUrl = first.coverUrl,
                contentDescription = null,
                modifier = Modifier.width(56.dp).aspectRatio(1f).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        },
        headlineContent = {
            Text(
                text = podcastName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent =
            if (showDelete) {
                {
                    IconButton(onClick = onDelete) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.cd_delete_download),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else null,
    )
}

@Composable
fun ImageCacheSettingsCard(
    isCacheEnabled: Boolean,
    cacheSizeMb: Float,
    cacheStorageUsed: Long,
    downloadedImageStorageUsed: Long,
    completedVideoCount: Int,
    artworkRefresh: ArtworkRefreshUiState,
    onCacheEnabledChange: (Boolean) -> Unit,
    onCacheSizeChange: (Float) -> Unit,
    onRefreshDownloadedArtwork: () -> Unit,
    onCancelArtworkRefresh: () -> Unit,
    formatSize: (Long) -> String,
    canModify: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showRefreshDialog by remember { mutableStateOf(false) }

    if (showRefreshDialog) {
        AlertDialog(
            onDismissRequest = { showRefreshDialog = false },
            title = { Text("Refresh downloaded artwork?") },
            text = {
                Text(
                    "Refresh artwork for $completedVideoCount downloaded videos. " +
                        "Existing downloaded artwork will be replaced."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRefreshDialog = false
                        onRefreshDownloadedArtwork()
                    }
                ) {
                    Text("Refresh")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = "Image Caching",
                        style =
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text =
                            "Save media images to disk for faster loading and reduced network usage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Switch(
                    checked = isCacheEnabled,
                    onCheckedChange = onCacheEnabledChange,
                    enabled = canModify,
                    modifier = Modifier.scale(0.8f),
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                )
            }

            Text(
                text = "Takes effect on next app launch",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))
            StorageUsageRow(
                label = "Cache used",
                value = formatSize(cacheStorageUsed),
            )
            StorageUsageRow(
                label = "Downloaded images",
                value = formatSize(downloadedImageStorageUsed),
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Downloaded artwork",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$completedVideoCount downloaded videos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 2.dp),
            )

            if (artworkRefresh.isRunning) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { artworkRefresh.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
                Text(
                    text =
                        buildString {
                            append("${artworkRefresh.completed}/${artworkRefresh.total}")
                            artworkRefresh.currentItemName?.takeIf { it.isNotBlank() }?.let {
                                append(" - ")
                                append(it)
                            }
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = onCancelArtworkRefresh,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Cancel")
                }
            } else {
                TextButton(
                    onClick = { showRefreshDialog = true },
                    enabled = canModify && completedVideoCount > 0,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Refresh downloaded artwork")
                }
            }

            AnimatedVisibility(
                visible = isCacheEnabled,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Maximum Disk Space",
                            style =
                                MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            val formattedSize =
                                if (cacheSizeMb >= 1024f) {
                                    String.format(
                                            Locale.getDefault(),
                                            "%.1f GB",
                                            cacheSizeMb / 1024f,
                                        )
                                        .replace(".0", "")
                                        .replace(",0", "")
                                } else {
                                    "${cacheSizeMb.toInt()} MB"
                                }

                            Text(
                                text = formattedSize,
                                style =
                                    MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Slider(
                        value = cacheSizeMb,
                        onValueChange = onCacheSizeChange,
                        enabled = canModify,
                        valueRange = 256f..2048f,
                        steps = 6,
                        colors =
                            SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor =
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                activeTickColor =
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
                                inactiveTickColor =
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageUsageRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                text = value,
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
