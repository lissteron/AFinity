package com.makd.afinity.data.storage

import java.io.File
import java.util.Locale

object DownloadedImageStorage {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun imageBytes(root: File): Long =
        files(root)
            .filter { file -> file.extension.lowercase(Locale.ROOT) in imageExtensions }
            .sumOf { file -> file.length().coerceAtLeast(0L) }

    fun allBytes(root: File): Long =
        files(root).sumOf { file -> file.length().coerceAtLeast(0L) }

    private fun files(root: File): Sequence<File> =
        if (!root.exists()) {
            emptySequence()
        } else {
            root.walkTopDown().onFail { _, _ -> }.filter { it.isFile }
        }
}
