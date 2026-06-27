package com.makd.afinity.data.local

import com.makd.afinity.data.storage.PortableMediaArtworkPaths

internal object LocalLibraryArtworkPaths {
    fun itemImagesDirectory(
        relativeMediaPath: String,
        mediaKind: LocalMediaKind,
    ): String =
        PortableMediaArtworkPaths.itemImagesDirectory(
            relativeMediaPath,
            isEpisode = mediaKind == LocalMediaKind.EPISODE,
        )

    fun seasonImagesDirectory(relativeMediaPath: String): String =
        PortableMediaArtworkPaths.seasonImagesDirectory(relativeMediaPath)

    fun showImagesDirectory(relativeMediaPath: String): String =
        PortableMediaArtworkPaths.showImagesDirectory(relativeMediaPath)

    fun mediaDirectory(relativeMediaPath: String): String =
        PortableMediaArtworkPaths.mediaDirectory(relativeMediaPath)

    fun mediaBaseName(relativeMediaPath: String): String =
        PortableMediaArtworkPaths.mediaBaseName(relativeMediaPath)
}
