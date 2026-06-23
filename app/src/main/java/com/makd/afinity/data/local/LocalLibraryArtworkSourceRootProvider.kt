package com.makd.afinity.data.local

import com.makd.afinity.data.storage.DownloadStorageManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface LocalLibraryArtworkSourceRootProvider {
    suspend fun sourceRoots(): List<File>
}

@Singleton
class DownloadStorageArtworkSourceRootProvider
@Inject
constructor(private val downloadStorageManager: DownloadStorageManager) :
    LocalLibraryArtworkSourceRootProvider {
    override suspend fun sourceRoots(): List<File> =
        downloadStorageManager
            .getAvailableLocations()
            .filterNot { it.isCustom }
            .mapNotNull { location ->
                location.path.takeIf { it.isNotBlank() }?.let(::File)
            }
            .filter { it.exists() && it.isDirectory }
            .distinctBy { it.absoluteFile.absolutePath }
}
