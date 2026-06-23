package com.makd.afinity.navigation

import com.makd.afinity.data.models.media.AfinityFolder
import com.makd.afinity.data.models.media.AfinityItem
import com.makd.afinity.data.models.media.AfinitySeason
import com.makd.afinity.data.models.media.AfinityShow
import com.makd.afinity.data.models.media.AfinitySourceType

internal sealed interface HomeItemNavigationTarget {
    data class Container(val id: String, val name: String) : HomeItemNavigationTarget

    data object Detail : HomeItemNavigationTarget
}

internal fun homeItemNavigationTarget(item: AfinityItem): HomeItemNavigationTarget =
    when {
        item is AfinityFolder -> HomeItemNavigationTarget.Container(item.id.toString(), item.name)
        item is AfinityShow && item.isLocalCatalogContainer() ->
            HomeItemNavigationTarget.Container(item.id.toString(), item.name)
        item is AfinitySeason && item.isLocalCatalogContainer() ->
            HomeItemNavigationTarget.Container(item.id.toString(), item.name)
        else -> HomeItemNavigationTarget.Detail
    }

private fun AfinityShow.isLocalCatalogContainer(): Boolean =
    status == "Local" &&
        seasons.any { season ->
            season.episodes.any { episode ->
                episode.sources.any { source -> source.type == AfinitySourceType.LOCAL }
            }
        }

private fun AfinitySeason.isLocalCatalogContainer(): Boolean =
    episodes.any { episode ->
        episode.sources.any { source -> source.type == AfinitySourceType.LOCAL }
    }
