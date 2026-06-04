package com.makd.afinity.data.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadedImageStorageTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun imageBytesCountsOnlyDownloadedImageFiles() {
        val root = temporaryFolder.newFolder("downloads")
        writeBytes(File(root, "show/images/primary.png"), 100)
        writeBytes(File(root, "show/season/episode/images/still.JPG"), 200)
        writeBytes(File(root, "show/season/episode/images/poster.webp"), 300)
        writeBytes(File(root, "show/season/episode/media/source.mkv"), 400)
        writeBytes(File(root, "show/season/episode/images/source.jpg.download"), 500)

        assertEquals(600L, DownloadedImageStorage.imageBytes(root))
    }

    @Test
    fun allBytesCountsExtensionlessCacheFiles() {
        val root = temporaryFolder.newFolder("image_cache")
        writeBytes(File(root, "metadata"), 12)
        writeBytes(File(root, "entries/001"), 34)

        assertEquals(46L, DownloadedImageStorage.allBytes(root))
    }

    private fun writeBytes(file: File, size: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size))
    }
}
