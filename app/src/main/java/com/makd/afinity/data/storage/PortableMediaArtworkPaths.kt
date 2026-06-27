package com.makd.afinity.data.storage

import java.io.File

internal object PortableMediaArtworkPaths {
    fun itemImagesDirectory(
        relativeMediaPath: String,
        isEpisode: Boolean,
    ): String {
        val mediaDir = mediaDirectory(relativeMediaPath)
        if (mediaDir.isBlank()) return ""
        return when {
            isEpisode -> listOf(mediaDir, "images", mediaBaseName(relativeMediaPath)).joinRelativePath()
            else -> listOf(mediaDir, "images").joinRelativePath()
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

    fun itemImagesDirectoryForMediaFile(
        mediaFile: File,
        isEpisode: Boolean,
    ): File? {
        val mediaDir = mediaFile.parentFile ?: return null
        if (mediaDir.name == "media") {
            return mediaDir.parentFile?.let { File(it, "images") }
        }
        return when {
            isEpisode -> File(File(mediaDir, "images"), mediaFile.nameWithoutExtension)
            else -> File(mediaDir, "images")
        }
    }

    fun seasonImagesDirectoryForMediaFile(mediaFile: File): File? {
        val mediaDir = mediaFile.parentFile ?: return null
        val seasonDir =
            if (mediaDir.name == "media") {
                mediaDir.parentFile?.parentFile
            } else {
                mediaDir
            }
        return seasonDir?.let { File(it, "images") }
    }

    fun showImagesDirectoryForMediaFile(mediaFile: File): File? {
        val mediaDir = mediaFile.parentFile ?: return null
        val showDir =
            if (mediaDir.name == "media") {
                mediaDir.parentFile?.parentFile?.parentFile
            } else {
                mediaDir.parentFile
            }
        return showDir?.let { File(it, "images") }
    }

    private fun List<String>.joinRelativePath(): String =
        filter { it.isNotBlank() }.joinToString("/")
}
