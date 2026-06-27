package com.makd.afinity.data.storage

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.makd.afinity.data.database.entities.DownloadDto
import com.makd.afinity.data.models.download.DownloadStorageLocation
import com.makd.afinity.data.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadStorageManager
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
) {
    private companion object {
        const val DEVICE_SHARED_ID = "device_shared"
        const val APP_PRIVATE_ID = "app_private"
        const val SECONDARY_PREFIX = "secondary:"
        const val CUSTOM_TREE_ID = "custom_tree"
        const val DOWNLOADS_DIR = "AFinity/Downloads"
        const val DOCUMENT_MIME_TYPE_DIR = "vnd.android.document/directory"
    }

    suspend fun getSelectedDownloadsRoot(): File =
        withContext(Dispatchers.IO) {
            val selectedId = preferencesRepository.getDownloadStorageLocationId()
            val locations = buildLocations(selectedId)
            val selected = locations.firstOrNull { it.id == selectedId } ?: locations.first()
            val fileBackedPath =
                selected.path.takeUnless { selected.isCustom }
                    ?: locations.first { it.id == DEVICE_SHARED_ID }.path
            File(fileBackedPath).also { if (!it.exists()) it.mkdirs() }
        }

    suspend fun getAvailableLocations(): List<DownloadStorageLocation> =
        withContext(Dispatchers.IO) {
            buildLocations(preferencesRepository.getDownloadStorageLocationId())
        }

    suspend fun getDownloadedImageStorageUsed(): Long {
        val root = getSelectedDownloadsRoot()
        return withContext(Dispatchers.IO) { DownloadedImageStorage.imageBytes(root) }
    }

    suspend fun getImageCacheStorageUsed(): Long =
        withContext(Dispatchers.IO) {
            DownloadedImageStorage.allBytes(context.cacheDir.resolve("image_cache"))
        }

    suspend fun setCustomTreeLocation(uri: Uri) =
        withContext(Dispatchers.IO) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            preferencesRepository.setCustomDownloadTreeUri(uri.toString())
            preferencesRepository.setDownloadStorageLocationId(CUSTOM_TREE_ID)
        }

    suspend fun setSelectedLocation(locationId: String) {
        val locations = getAvailableLocations()
        if (locations.any { it.id == locationId }) {
            preferencesRepository.setDownloadStorageLocationId(locationId)
        }
    }

    suspend fun getItemDownloadDirectory(download: DownloadDto?, itemId: java.util.UUID): File {
        val rootFromFile = download?.filePath?.let(::getItemRootFromMediaPath)
        return (rootFromFile ?: File(getSelectedDownloadsRoot(), download?.folderPath ?: itemId.toString()))
            .also { if (!it.exists()) it.mkdirs() }
    }

    suspend fun getItemImagesDirectory(
        download: DownloadDto?,
        itemId: UUID,
        itemType: String? = download?.itemType,
    ): File {
        val imagesFromFile = download?.filePath?.let { getItemImagesDirectoryFromMediaPath(it, itemType) }
        return (imagesFromFile ?: File(getItemDownloadDirectory(download, itemId), "images"))
            .also { if (!it.exists()) it.mkdirs() }
    }

    suspend fun getShowImagesDirectory(download: DownloadDto?, showId: UUID): File {
        val imagesFromFile = download?.filePath?.let(::getShowImagesDirectoryFromMediaPath)
        val fallback =
            download
                ?.serverId
                ?.takeIf { it.isNotBlank() }
                ?.let { serverId -> File(getSelectedDownloadsRoot(), "$serverId/shows/$showId/images") }
                ?: File(getItemDownloadDirectory(download, download?.itemId ?: showId), "images")
        return (imagesFromFile ?: fallback).also { if (!it.exists()) it.mkdirs() }
    }

    suspend fun getSeasonImagesDirectory(
        download: DownloadDto?,
        seriesId: UUID,
        seasonNumber: Int,
    ): File {
        val imagesFromFile = download?.filePath?.let(::getSeasonImagesDirectoryFromMediaPath)
        val fallback =
            download
                ?.serverId
                ?.takeIf { it.isNotBlank() }
                ?.let { serverId ->
                    File(getSelectedDownloadsRoot(), "$serverId/shows/$seriesId/seasons/$seasonNumber/images")
                }
                ?: File(getItemDownloadDirectory(download, download?.itemId ?: seriesId), "images")
        return (imagesFromFile ?: fallback).also { if (!it.exists()) it.mkdirs() }
    }

    fun getItemRootFromMediaPath(filePath: String): File? {
        val uri = Uri.parse(filePath)
        if (uri.scheme == "content") return null
        val mediaFile = File(filePath)
        val parent = mediaFile.parentFile ?: return null
        return if (parent.name == "media") parent.parentFile else parent
    }

    fun getItemImagesDirectoryFromMediaPath(
        filePath: String,
        itemType: String?,
    ): File? {
        if (isContentUri(filePath)) return null
        return PortableMediaArtworkPaths.itemImagesDirectoryForMediaFile(
            File(filePath),
            isEpisode = itemType.isEpisodeItem(),
        )
    }

    fun getSeasonImagesDirectoryFromMediaPath(filePath: String): File? {
        if (isContentUri(filePath)) return null
        return PortableMediaArtworkPaths.seasonImagesDirectoryForMediaFile(File(filePath))
    }

    fun getShowImagesDirectoryFromMediaPath(filePath: String): File? {
        if (isContentUri(filePath)) return null
        return PortableMediaArtworkPaths.showImagesDirectoryForMediaFile(File(filePath))
    }

    suspend fun createMediaFileTarget(
        folderPath: String?,
        itemId: UUID,
        sourceId: String,
        extension: String,
        relativeMediaPath: String? = null,
    ): MediaFileTarget =
        withContext(Dispatchers.IO) {
            val selectedId = preferencesRepository.getDownloadStorageLocationId()
            val customTreeUri = preferencesRepository.getCustomDownloadTreeUri()?.let(Uri::parse)
            if (selectedId == CUSTOM_TREE_ID && customTreeUri != null) {
                if (relativeMediaPath != null) {
                    createCustomMediaFileTarget(customTreeUri, relativeMediaPath)
                } else {
                    createCustomMediaFileTarget(customTreeUri, folderPath ?: itemId.toString(), sourceId, extension)
                }
            } else {
                val finalFile =
                    if (relativeMediaPath != null) {
                        File(getSelectedDownloadsRoot(), relativeMediaPath)
                    } else {
                        val itemDir = File(getSelectedDownloadsRoot(), folderPath ?: itemId.toString())
                        File(File(itemDir, "media").also { it.mkdirs() }, "$sourceId.$extension")
                    }
                finalFile.parentFile?.mkdirs()
                val outputFile =
                    if (relativeMediaPath != null) {
                        File(finalFile.parentFile, "${finalFile.name}.part")
                    } else {
                        File(finalFile.parentFile, "${finalFile.name}.download")
                    }
                MediaFileTarget.FileTarget(outputFile, finalFile)
            }
        }

    suspend fun createMediaFileTargetForRoot(
        rootUriOrPath: String,
        usesSafTree: Boolean,
        relativeMediaPath: String,
    ): MediaFileTarget =
        withContext(Dispatchers.IO) {
            if (usesSafTree) {
                createCustomMediaFileTarget(Uri.parse(rootUriOrPath), relativeMediaPath)
            } else {
                createFileMediaTarget(File(rootUriOrPath), relativeMediaPath)
            }
        }

    suspend fun createSidecarFileTarget(
        download: DownloadDto?,
        itemId: UUID,
        directoryName: String,
        fileName: String,
        mimeType: String,
        relativeSidecarPath: String? = null,
    ): SidecarFileTarget =
        withContext(Dispatchers.IO) {
            val customTreeUri = getCustomTreeUriForDownload(download)
            if (customTreeUri != null) {
                var parent = rootDocumentUri(customTreeUri)
                if (relativeSidecarPath != null) {
                    val segments = relativeSidecarPath.split('/').filter { it.isNotBlank() }
                    segments.dropLast(1).forEach { segment ->
                        parent = findOrCreateDirectory(parent, segment)
                    }
                    val documentUri =
                        findOrCreateFile(parent, segments.lastOrNull() ?: fileName, mimeType)
                    SidecarFileTarget.UriTarget(context, documentUri)
                } else {
                    (download?.folderPath ?: itemId.toString())
                        .split('/')
                        .filter { it.isNotBlank() }
                        .forEach { segment -> parent = findOrCreateDirectory(parent, segment) }
                    directoryName
                        .split('/')
                        .filter { it.isNotBlank() }
                        .forEach { segment -> parent = findOrCreateDirectory(parent, segment) }
                    val sidecarDir = parent
                    val documentUri = findOrCreateFile(sidecarDir, fileName, mimeType)
                    SidecarFileTarget.UriTarget(context, documentUri)
                }
            } else {
                if (relativeSidecarPath != null) {
                    val sidecarFile = File(getSelectedDownloadsRoot(), relativeSidecarPath)
                    sidecarFile.parentFile?.mkdirs()
                    SidecarFileTarget.FileTarget(sidecarFile)
                } else {
                    val itemDir = getItemDownloadDirectory(download, itemId)
                    val sidecarDir = File(itemDir, directoryName).also { it.mkdirs() }
                    SidecarFileTarget.FileTarget(File(sidecarDir, fileName))
                }
            }
        }

    suspend fun createSidecarFileTargetForRoot(
        rootUriOrPath: String,
        usesSafTree: Boolean,
        relativeSidecarPath: String,
        mimeType: String,
    ): SidecarFileTarget =
        withContext(Dispatchers.IO) {
            if (usesSafTree) {
                createCustomSidecarFileTarget(Uri.parse(rootUriOrPath), relativeSidecarPath, mimeType)
            } else {
                val sidecarFile = File(rootUriOrPath, relativeSidecarPath)
                sidecarFile.parentFile?.mkdirs()
                SidecarFileTarget.FileTarget(sidecarFile)
            }
        }

    fun deleteDocumentUri(uriString: String): Boolean =
        try {
            val uri = Uri.parse(uriString)
            uri.scheme == "content" && DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            false
        }

    fun isContentUri(path: String?): Boolean = path?.let { Uri.parse(it).scheme == "content" } == true

    private suspend fun getCustomTreeUriForDownload(download: DownloadDto?): Uri? {
        val customTreeUri = preferencesRepository.getCustomDownloadTreeUri()?.let(Uri::parse)
        val selectedId = preferencesRepository.getDownloadStorageLocationId()
        return customTreeUri?.takeIf {
            selectedId == CUSTOM_TREE_ID || isContentUri(download?.filePath)
        }
    }

    private suspend fun buildLocations(selectedId: String?): List<DownloadStorageLocation> {
        val locations = mutableListOf<RawLocation>()
        val sharedFilesDir = context.getExternalFilesDir(null) ?: context.filesDir
        val customTreeUri = preferencesRepository.getCustomDownloadTreeUri()?.let(Uri::parse)

        locations +=
            RawLocation(
                id = DEVICE_SHARED_ID,
                name = "Device storage",
                description = "App media folder on this device",
                root = File(sharedFilesDir, DOWNLOADS_DIR),
            )

        locations +=
            RawLocation(
                id = APP_PRIVATE_ID,
                name = "Private app storage",
                description = "Internal app-only storage",
                root = File(context.filesDir, DOWNLOADS_DIR),
            )

        context.getExternalFilesDirs(null).drop(1).forEachIndexed { index, dir ->
            if (dir != null && Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED) {
                locations +=
                    RawLocation(
                        id = "$SECONDARY_PREFIX${dir.absolutePath}",
                        name = "Secondary storage ${index + 1}",
                        description = "App media folder on removable or secondary storage",
                        root = File(dir, DOWNLOADS_DIR),
                    )
            }
        }

        val distinctLocations =
            locations.distinctBy { it.root?.absolutePath ?: it.treeUri.toString() }.toMutableList()
        customTreeUri?.let { treeUri ->
            distinctLocations +=
                RawLocation(
                    id = CUSTOM_TREE_ID,
                    name = getTreeDisplayName(treeUri),
                    description = "Folder selected from device storage",
                    root = null,
                    treeUri = treeUri,
                    isCustom = true,
                )
        }
        val effectiveSelectedId =
            selectedId?.takeIf { id -> distinctLocations.any { it.id == id } }
                ?: DEVICE_SHARED_ID

        return distinctLocations.map { location ->
            val root = location.root
            val freeBytes =
                try {
                    if (root != null) {
                        root.mkdirs()
                        StatFs(root.path).availableBytes
                    } else {
                        0L
                    }
                } catch (_: Exception) {
                    0L
                }
            DownloadStorageLocation(
                id = location.id,
                name = location.name,
                description = location.description,
                path = root?.absolutePath ?: location.treeUri?.toString().orEmpty(),
                freeBytes = freeBytes,
                isSelected = location.id == effectiveSelectedId,
                isCustom = location.isCustom,
            )
        }
    }

    private fun createCustomMediaFileTarget(
        treeUri: Uri,
        folderPath: String,
        sourceId: String,
        extension: String,
    ): MediaFileTarget.UriTarget {
        var parent = rootDocumentUri(treeUri)
        folderPath.split('/').filter { it.isNotBlank() }.forEach { segment ->
            parent = findOrCreateDirectory(parent, segment)
        }
        val mediaDir = findOrCreateDirectory(parent, "media")
        val outputUri =
            findOrCreateFile(mediaDir, "$sourceId.$extension.download", "application/octet-stream")
        val finalName = "$sourceId.$extension"
        findChild(mediaDir, finalName)?.let { existing ->
            DocumentsContract.deleteDocument(context.contentResolver, existing)
        }
        return MediaFileTarget.UriTarget(context, outputUri, finalName)
    }

    private fun createCustomMediaFileTarget(
        treeUri: Uri,
        relativeMediaPath: String,
    ): MediaFileTarget.UriTarget {
        var parent = rootDocumentUri(treeUri)
        val segments = relativeMediaPath.split('/').filter { it.isNotBlank() }
        segments.dropLast(1).forEach { segment ->
            parent = findOrCreateDirectory(parent, segment)
        }
        val finalName = segments.lastOrNull() ?: "media.mkv"
        val outputUri = findOrCreateFile(parent, "$finalName.part", "application/octet-stream")
        findChild(parent, finalName)?.let { existing ->
            DocumentsContract.deleteDocument(context.contentResolver, existing)
        }
        return MediaFileTarget.UriTarget(context, outputUri, finalName)
    }

    private fun createFileMediaTarget(root: File, relativeMediaPath: String): MediaFileTarget.FileTarget {
        val finalFile = File(root, relativeMediaPath)
        finalFile.parentFile?.mkdirs()
        val outputFile = File(finalFile.parentFile, "${finalFile.name}.part")
        return MediaFileTarget.FileTarget(outputFile, finalFile)
    }

    private fun createCustomSidecarFileTarget(
        treeUri: Uri,
        relativeSidecarPath: String,
        mimeType: String,
    ): SidecarFileTarget.UriTarget {
        var parent = rootDocumentUri(treeUri)
        val segments = relativeSidecarPath.split('/').filter { it.isNotBlank() }
        segments.dropLast(1).forEach { segment ->
            parent = findOrCreateDirectory(parent, segment)
        }
        val documentUri = findOrCreateFile(parent, segments.lastOrNull() ?: "media.afinity.json", mimeType)
        return SidecarFileTarget.UriTarget(context, documentUri)
    }

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    private fun findOrCreateDirectory(parent: Uri, name: String): Uri =
        findChild(parent, name, DOCUMENT_MIME_TYPE_DIR)
            ?: DocumentsContract.createDocument(context.contentResolver, parent, DOCUMENT_MIME_TYPE_DIR, name)
            ?: error("Failed to create folder $name")

    private fun findOrCreateFile(parent: Uri, name: String, mimeType: String): Uri =
        findChild(parent, name)
            ?: DocumentsContract.createDocument(context.contentResolver, parent, mimeType, name)
            ?: error("Failed to create file $name")

    private fun findChild(parent: Uri, name: String, mimeType: String? = null): Uri? {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parent,
                DocumentsContract.getDocumentId(parent),
            )
        return context.contentResolver
            .query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childName =
                        cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME
                            )
                        )
                    val childMime =
                        cursor.getString(
                            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        )
                    if (childName == name && (mimeType == null || childMime == mimeType)) {
                        val docId =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                                )
                            )
                        return@use DocumentsContract.buildDocumentUriUsingTree(parent, docId)
                    }
                }
                null
            }
    }

    private fun getTreeDisplayName(uri: Uri): String =
        queryDocument(rootDocumentUri(uri), arrayOf(OpenableColumns.DISPLAY_NAME)) { cursor ->
            val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (columnIndex >= 0) cursor.getString(columnIndex) else null
        } ?: "Custom folder"

    private fun <T> queryDocument(uri: Uri, projection: Array<String>, read: (Cursor) -> T?): T? =
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) read(cursor) else null
        }

    private data class RawLocation(
        val id: String,
        val name: String,
        val description: String,
        val root: File?,
        val treeUri: Uri? = null,
        val isCustom: Boolean = false,
    )

    private fun String?.isEpisodeItem(): Boolean =
        equals("EPISODE", ignoreCase = true)

    sealed class MediaFileTarget {
        abstract val displayPath: String
        abstract val resumeSize: Long
        abstract fun openOutputStream(append: Boolean): OutputStream
        abstract fun finish(): CompletedMediaFile

        data class FileTarget(val outputFile: File, val finalFile: File) : MediaFileTarget() {
            override val displayPath: String
                get() = outputFile.absolutePath
            override val resumeSize: Long
                get() = if (outputFile.exists()) outputFile.length() else 0L

            override fun openOutputStream(append: Boolean): OutputStream =
                java.io.FileOutputStream(outputFile, append)

            override fun finish(): CompletedMediaFile {
                if (outputFile.exists()) outputFile.renameTo(finalFile)
                return CompletedMediaFile(finalFile.absolutePath, finalFile.length())
            }
        }

        class UriTarget(
            private val context: Context,
            private val outputUri: Uri,
            private val finalName: String,
        ) : MediaFileTarget() {
            override val displayPath: String
                get() = outputUri.toString()
            override val resumeSize: Long
                get() = getSize(outputUri)

            override fun openOutputStream(append: Boolean): OutputStream =
                context.contentResolver.openOutputStream(outputUri, if (append) "wa" else "w")
                    ?: error("Failed to open $outputUri for writing")

            override fun finish(): CompletedMediaFile {
                val finalUri =
                    DocumentsContract.renameDocument(context.contentResolver, outputUri, finalName)
                        ?: outputUri
                return CompletedMediaFile(finalUri.toString(), getSize(finalUri))
            }

            private fun getSize(uri: Uri): Long =
                context.contentResolver
                    .query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val columnIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                            if (columnIndex >= 0 && !cursor.isNull(columnIndex)) {
                                cursor.getLong(columnIndex)
                            } else 0L
                        } else 0L
                    } ?: 0L
        }
    }

    sealed class SidecarFileTarget {
        abstract val displayPath: String
        abstract val uriString: String
        abstract val existsAndNonEmpty: Boolean
        abstract fun openOutputStream(): OutputStream
        abstract fun deleteIfExists(): Boolean

        data class FileTarget(val file: File) : SidecarFileTarget() {
            override val displayPath: String
                get() = file.absolutePath
            override val uriString: String
                get() = Uri.fromFile(file).toString()
            override val existsAndNonEmpty: Boolean
                get() = file.exists() && file.length() > 0

            override fun openOutputStream(): OutputStream = java.io.FileOutputStream(file)

            override fun deleteIfExists(): Boolean = !file.exists() || file.delete()
        }

        class UriTarget(private val context: Context, private val uri: Uri) : SidecarFileTarget() {
            override val displayPath: String
                get() = uri.toString()
            override val uriString: String
                get() = uri.toString()
            override val existsAndNonEmpty: Boolean
                get() = getSize() > 0

            override fun openOutputStream(): OutputStream =
                context.contentResolver.openOutputStream(uri, "w")
                    ?: error("Failed to open $uri for writing")

            override fun deleteIfExists(): Boolean =
                try {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } catch (_: Exception) {
                    false
                }

            private fun getSize(): Long =
                context.contentResolver
                    .query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val columnIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                            if (columnIndex >= 0 && !cursor.isNull(columnIndex)) {
                                cursor.getLong(columnIndex)
                            } else 0L
                        } else 0L
                    } ?: 0L
        }
    }

    data class CompletedMediaFile(val path: String, val size: Long)
}
