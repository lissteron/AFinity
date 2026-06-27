package com.makd.afinity.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLooseEpisodeSeasonContextSourceTest {
    @Test
    fun detailPlaybackPassesEpisodeSeasonContextIntoPlayer() {
        val mainNavigation = readSource("src/main/java/com/makd/afinity/navigation/MainNavigation.kt")
        val itemDetailScreen = readSource("src/main/java/com/makd/afinity/ui/item/ItemDetailScreen.kt")
        val downloadDto =
            readSource("src/main/java/com/makd/afinity/data/database/entities/DownloadDto.kt")
        val downloadRepository =
            readSource("src/main/java/com/makd/afinity/data/repository/download/DownloadRepository.kt")
        val itemDownloadDelegate =
            readSource("src/main/java/com/makd/afinity/ui/item/delegates/ItemDownloadDelegate.kt")
        val jellyfinDownloadRepository =
            readSource("src/main/java/com/makd/afinity/data/repository/download/JellyfinDownloadRepository.kt")
        val mediaDownloadTransferRunner =
            readSource("src/main/java/com/makd/afinity/data/repository/download/MediaDownloadTransferRunner.kt")
        val downloadedArtworkRefresher =
            readSource("src/main/java/com/makd/afinity/data/repository/download/DownloadedArtworkRefresher.kt")
        val jellyfinMediaRepository =
            readSource("src/main/java/com/makd/afinity/data/repository/media/JellyfinMediaRepository.kt")
        val playerScreenWrapper = readSource("src/main/java/com/makd/afinity/ui/player/PlayerScreenWrapper.kt")
        val playerWrapperViewModel =
            readSource("src/main/java/com/makd/afinity/ui/player/PlayerWrapperViewModel.kt")

        assertTrue(mainNavigation.contains("seasonId = (item as? AfinityEpisode)?.seasonId"))
        assertTrue(
            itemDetailScreen.contains(
                "seasonId = (targetPlayItem as? AfinityEpisode)?.seasonId ?: (item as? AfinitySeason)?.id"
            )
        )
        assertTrue(
            playerScreenWrapper.contains(
                "viewModel.loadItem(itemId, fallbackSeriesId = seriesId, fallbackSeasonId = seasonId)"
            )
        )
        assertTrue(playerWrapperViewModel.contains("fallbackSeasonId: UUID? = null"))
        assertTrue(playerWrapperViewModel.contains("BaseItemKind.EPISODE ->"))
        assertTrue(playerWrapperViewModel.contains("fallbackSeasonId = fallbackSeasonId"))
        assertTrue(jellyfinMediaRepository.contains("toAfinityItemWithResolvedEpisodeContext"))
        assertTrue(jellyfinMediaRepository.contains("toAfinityEpisodeWithResolvedContext"))
        assertTrue(jellyfinMediaRepository.contains("getSeasons(episodeSeriesId).singleOrNull()?.id"))
        assertTrue(downloadDto.contains("val seasonId: String? = null"))
        assertTrue(downloadRepository.contains("seasonId: UUID? = null"))
        assertTrue(itemDownloadDelegate.contains("seasonId = (target as? AfinityEpisode)?.seasonId"))
        assertTrue(
            jellyfinDownloadRepository.contains(
                "seasonId = (item as? AfinityEpisode)?.seasonId?.toString()"
            )
        )
        assertTrue(jellyfinDownloadRepository.contains("fallbackSeasonId = seasonId"))
        assertTrue(mediaDownloadTransferRunner.contains("download.seasonId?.toUuidOrNull()"))
        assertTrue(mediaDownloadTransferRunner.contains("fallbackSeasonId = fallbackSeasonId"))
        assertTrue(mediaDownloadTransferRunner.contains("\"${'$'}baseUrl/Videos/${'$'}itemId/stream.${'$'}extension\""))
        assertTrue(downloadedArtworkRefresher.contains("download.seasonId?.toUuidOrNull()"))
    }

    private fun readSource(path: String): String = File(path).readText()
}
