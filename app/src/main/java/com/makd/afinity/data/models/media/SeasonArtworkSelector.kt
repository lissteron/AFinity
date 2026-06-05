package com.makd.afinity.data.models.media

import android.net.Uri

data class SeasonCardArtwork(val imageUrl: String?, val blurHash: String?)

fun List<AfinitySeason>.sharedSeasonPrimaryArtworkKeys(): Set<String> =
    mapNotNull { season -> season.seasonPrimaryArtworkKey() }
        .groupingBy { key -> key }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys

fun AfinitySeason.seasonPrimaryArtworkKey(): String? {
    return imageArtworkKey(images.primary?.toString(), images.primaryImageBlurHash)
}

fun AfinitySeason.prefersRepresentativeSeasonArtwork(
    sharedPrimaryArtworkKeys: Set<String>
): Boolean {
    return prefersRepresentativeSeasonArtwork(
        seasonName = name,
        primaryArtworkKey = seasonPrimaryArtworkKey(),
        sharedPrimaryArtworkKeys = sharedPrimaryArtworkKeys,
    )
}

fun AfinitySeason.needsRepresentativeSeasonArtwork(sharedPrimaryArtworkKeys: Set<String>): Boolean {
    return needsRepresentativeSeasonArtwork(
        prefersRepresentativeArtwork = prefersRepresentativeSeasonArtwork(sharedPrimaryArtworkKeys),
        hasSeasonAlternateArtwork = images.thumb != null || images.backdrop != null,
        hasEpisodeArtwork = episodes.any { episode -> episode.images.hasSeasonCardArtwork() },
    )
}

fun AfinityImages.hasSeasonCardArtwork(): Boolean =
    primary != null || thumb != null || backdrop != null

fun AfinitySeason.seasonCardArtwork(preferRepresentativeArtwork: Boolean): SeasonCardArtwork {
    val episodeImages = episodes.firstOrNull { episode -> episode.images.hasSeasonCardArtwork() }?.images
    return selectSeasonCardArtwork(
        preferRepresentativeArtwork = preferRepresentativeArtwork,
        seasonPrimary = images.primary.toCandidate(images.primaryImageBlurHash),
        seasonThumb = images.thumb.toCandidate(images.thumbImageBlurHash),
        seasonBackdrop = images.backdrop.toCandidate(images.backdropImageBlurHash),
        episodePrimary = episodeImages?.primary.toCandidate(episodeImages?.primaryImageBlurHash),
        episodeThumb = episodeImages?.thumb.toCandidate(episodeImages?.thumbImageBlurHash),
        episodeBackdrop = episodeImages?.backdrop.toCandidate(episodeImages?.backdropImageBlurHash),
    )
}

internal fun imageArtworkKey(imageUrl: String?, imageBlurHash: String?): String? {
    if (imageUrl.isNullOrBlank()) return null
    return imageUrl.queryParameter("tag")?.takeIf { it.isNotBlank() }?.let { "tag:$it" }
        ?: imageBlurHash?.takeIf { it.isNotBlank() }?.let { "blur:$it" }
        ?: "url:$imageUrl"
}

internal fun prefersRepresentativeSeasonArtwork(
    seasonName: String,
    primaryArtworkKey: String?,
    sharedPrimaryArtworkKeys: Set<String>,
): Boolean {
    if (primaryArtworkKey != null && primaryArtworkKey in sharedPrimaryArtworkKeys) return true
    return !seasonName.looksLikeGenericSeasonName()
}

internal fun needsRepresentativeSeasonArtwork(
    prefersRepresentativeArtwork: Boolean,
    hasSeasonAlternateArtwork: Boolean,
    hasEpisodeArtwork: Boolean,
): Boolean {
    if (!prefersRepresentativeArtwork) return false
    if (hasSeasonAlternateArtwork) return false
    return !hasEpisodeArtwork
}

internal fun selectSeasonCardArtwork(
    preferRepresentativeArtwork: Boolean,
    seasonPrimary: SeasonCardArtwork?,
    seasonThumb: SeasonCardArtwork?,
    seasonBackdrop: SeasonCardArtwork?,
    episodePrimary: SeasonCardArtwork?,
    episodeThumb: SeasonCardArtwork?,
    episodeBackdrop: SeasonCardArtwork?,
): SeasonCardArtwork {
    val candidates =
        if (preferRepresentativeArtwork) {
            listOfNotNull(
                seasonThumb,
                seasonBackdrop,
                episodePrimary,
                episodeThumb,
                episodeBackdrop,
                seasonPrimary,
            )
        } else {
            listOfNotNull(
                seasonPrimary,
                seasonThumb,
                seasonBackdrop,
                episodePrimary,
                episodeThumb,
                episodeBackdrop,
            )
        }

    return candidates.firstOrNull() ?: SeasonCardArtwork(imageUrl = null, blurHash = null)
}

private fun Uri?.toCandidate(blurHash: String?): SeasonCardArtwork? =
    this?.let { uri -> SeasonCardArtwork(imageUrl = uri.toString(), blurHash = blurHash) }

private fun String.queryParameter(name: String): String? {
    val query = substringAfter('?', missingDelimiterValue = "").substringBefore('#')
    if (query.isBlank()) return null

    return query.split('&').firstNotNullOfOrNull { part ->
        val separatorIndex = part.indexOf('=')
        val key = if (separatorIndex >= 0) part.substring(0, separatorIndex) else part
        val value = if (separatorIndex >= 0) part.substring(separatorIndex + 1) else ""
        value.takeIf { key == name }
    }
}

private fun String.looksLikeGenericSeasonName(): Boolean {
    val normalized = trim()
    if (normalized.isBlank()) return true
    if (normalized.all { it.isDigit() }) return true

    val lowerCaseName = normalized.lowercase()
    return genericSeasonNamePatterns.any { pattern -> pattern.matches(lowerCaseName) }
}

private val genericSeasonNamePatterns =
    listOf(
        Regex("""^(season|saison|temporada|stagione|staffel|сезон)\s*\d+$"""),
        Regex("""^(s|с)\s*\d+$"""),
    )
