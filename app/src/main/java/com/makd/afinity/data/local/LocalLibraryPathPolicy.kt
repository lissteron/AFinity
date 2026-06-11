package com.makd.afinity.data.local

class LocalLibraryPathPolicy {
    fun movieMediaPath(title: String, year: Int?, extension: String): String {
        val folderName = movieFolderName(title, year)
        return "Movies/$folderName/$folderName.${normalizeExtension(extension)}"
    }

    fun movieSidecarPath(title: String, year: Int?): String {
        val folderName = movieFolderName(title, year)
        return "Movies/$folderName/$folderName.afinity.json"
    }

    fun movieNfoPath(title: String, year: Int?): String {
        return "Movies/${movieFolderName(title, year)}/movie.nfo"
    }

    fun episodeMediaPath(
        showTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String,
        extension: String,
    ): String {
        val show = sanitizePathSegment(showTitle)
        val baseName = episodeBaseName(showTitle, seasonNumber, episodeNumber, episodeTitle)
        return "Shows/$show/Season ${seasonNumber.twoDigits()}/$baseName.${normalizeExtension(extension)}"
    }

    fun episodeSidecarPath(
        showTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String,
    ): String {
        val show = sanitizePathSegment(showTitle)
        val baseName = episodeBaseName(showTitle, seasonNumber, episodeNumber, episodeTitle)
        return "Shows/$show/Season ${seasonNumber.twoDigits()}/$baseName.afinity.json"
    }

    fun tvShowNfoPath(showTitle: String): String = "Shows/${sanitizePathSegment(showTitle)}/tvshow.nfo"

    fun seasonNfoPath(showTitle: String, seasonNumber: Int): String =
        "Shows/${sanitizePathSegment(showTitle)}/Season ${seasonNumber.twoDigits()}/season.nfo"

    fun sidecarPathForMedia(relativeMediaPath: String): String =
        relativeMediaPath.substringBeforeLast('.', relativeMediaPath) + ".afinity.json"

    fun sanitizePathSegment(value: String?): String {
        val cleaned =
            value
                ?.replace(Regex("[\\\\/:*?\"<>|]"), " ")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Untitled"
        return cleaned.trim('.').ifBlank { "Untitled" }
    }

    private fun movieFolderName(title: String, year: Int?): String {
        val name = sanitizePathSegment(title)
        return if (year != null) "$name ($year)" else name
    }

    private fun episodeBaseName(
        showTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String,
    ): String =
        "${sanitizePathSegment(showTitle)} - S${seasonNumber.twoDigits()}E${episodeNumber.twoDigits()} - " +
            sanitizePathSegment(episodeTitle)

    private fun normalizeExtension(extension: String): String =
        extension.trim().trimStart('.').lowercase().ifBlank { "mkv" }

    private fun Int.twoDigits(): String = toString().padStart(2, '0')
}
