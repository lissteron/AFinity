package com.makd.afinity.data.local

internal object LocalLibraryArtworkPaths {
    fun itemImagesDirectory(
        relativeMediaPath: String,
        mediaKind: LocalMediaKind,
    ): String {
        val mediaDir = mediaDirectory(relativeMediaPath)
        if (mediaDir.isBlank()) return ""
        return when (mediaKind) {
            LocalMediaKind.MOVIE -> listOf(mediaDir, "images").joinRelativePath()
            LocalMediaKind.EPISODE ->
                listOf(mediaDir, "images", mediaBaseName(relativeMediaPath)).joinRelativePath()
        }
    }

    fun seasonImagesDirectory(relativeMediaPath: String): String {
        val mediaDir = mediaDirectory(relativeMediaPath)
        return listOf(mediaDir, "images").joinRelativePath()
    }

    fun showImagesDirectory(relativeMediaPath: String): String {
        val mediaDir = mediaDirectory(relativeMediaPath)
        val showDir = mediaDir.substringBeforeLast('/', "")
        return listOf(showDir, "images").joinRelativePath()
    }

    fun mediaDirectory(relativeMediaPath: String): String =
        relativeMediaPath.substringBeforeLast('/', "")

    fun mediaBaseName(relativeMediaPath: String): String =
        relativeMediaPath.substringAfterLast('/').substringBeforeLast('.')

    private fun List<String>.joinRelativePath(): String =
        filter { it.isNotBlank() }.joinToString("/")
}
