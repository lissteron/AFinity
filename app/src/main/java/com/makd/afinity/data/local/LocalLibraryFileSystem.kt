package com.makd.afinity.data.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject

data class LocalLibraryNode(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class LocalLibraryCompletedMediaFile(val path: String, val sizeBytes: Long)

interface LocalLibraryMediaWriteTarget {
    val displayPath: String
    val resumeSize: Long

    fun openOutputStream(append: Boolean): OutputStream

    fun finish(): LocalLibraryCompletedMediaFile

    fun deleteIfExists(): Boolean
}

interface LocalLibraryFileSystem {
    fun list(root: LocalLibraryRootRecord, relativePath: String = ""): List<LocalLibraryNode>

    fun readText(root: LocalLibraryRootRecord, relativePath: String): String?

    fun writeText(
        root: LocalLibraryRootRecord,
        relativePath: String,
        text: String,
        mimeType: String = "text/plain",
    ): Boolean

    fun writeBytes(
        root: LocalLibraryRootRecord,
        relativePath: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
    ): Boolean

    fun exists(root: LocalLibraryRootRecord, relativePath: String): Boolean

    fun isReadable(root: LocalLibraryRootRecord, relativePath: String): Boolean

    fun playerUri(root: LocalLibraryRootRecord, relativePath: String): String

    fun assetUri(root: LocalLibraryRootRecord, relativePath: String): String?

    fun delete(root: LocalLibraryRootRecord, relativePath: String): Boolean

    fun createMediaWriteTarget(
        root: LocalLibraryRootRecord,
        relativeMediaPath: String,
    ): LocalLibraryMediaWriteTarget
}

class AndroidLocalLibraryFileSystem
@Inject
constructor(private val context: Context) : LocalLibraryFileSystem {
    private val filePathFileSystem = FilePathLibraryFileSystem()
    private val safFileSystem = SafTreeLibraryFileSystem(context)

    override fun list(root: LocalLibraryRootRecord, relativePath: String): List<LocalLibraryNode> =
        root.delegate().list(root, relativePath)

    override fun readText(root: LocalLibraryRootRecord, relativePath: String): String? =
        root.delegate().readText(root, relativePath)

    override fun writeText(
        root: LocalLibraryRootRecord,
        relativePath: String,
        text: String,
        mimeType: String,
    ): Boolean = root.delegate().writeText(root, relativePath, text, mimeType)

    override fun writeBytes(
        root: LocalLibraryRootRecord,
        relativePath: String,
        bytes: ByteArray,
        mimeType: String,
    ): Boolean = root.delegate().writeBytes(root, relativePath, bytes, mimeType)

    override fun exists(root: LocalLibraryRootRecord, relativePath: String): Boolean =
        root.delegate().exists(root, relativePath)

    override fun isReadable(root: LocalLibraryRootRecord, relativePath: String): Boolean =
        root.delegate().isReadable(root, relativePath)

    override fun playerUri(root: LocalLibraryRootRecord, relativePath: String): String =
        root.delegate().playerUri(root, relativePath)

    override fun assetUri(root: LocalLibraryRootRecord, relativePath: String): String? =
        root.delegate().assetUri(root, relativePath)

    override fun delete(root: LocalLibraryRootRecord, relativePath: String): Boolean =
        root.delegate().delete(root, relativePath)

    override fun createMediaWriteTarget(
        root: LocalLibraryRootRecord,
        relativeMediaPath: String,
    ): LocalLibraryMediaWriteTarget = root.delegate().createMediaWriteTarget(root, relativeMediaPath)

    private fun LocalLibraryRootRecord.delegate(): LocalLibraryFileSystem =
        if (kind == LocalLibraryRootKind.SAF_TREE || Uri.parse(uriOrPath).scheme == "content") {
            safFileSystem
        } else {
            filePathFileSystem
        }
}

class FilePathLibraryFileSystem : LocalLibraryFileSystem {
    override fun list(root: LocalLibraryRootRecord, relativePath: String): List<LocalLibraryNode> {
        val directory = root.resolve(relativePath)
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        return directory.listFiles()?.map { file ->
            LocalLibraryNode(
                relativePath = file.relativeTo(root.rootFile()).invariantSeparatorsPath,
                name = file.name,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isFile) file.length() else 0L,
                modifiedAt = file.lastModified(),
            )
        } ?: emptyList()
    }

    override fun readText(root: LocalLibraryRootRecord, relativePath: String): String? {
        val file = root.resolve(relativePath)
        return if (file.exists() && file.isFile) file.readText() else null
    }

    override fun writeText(
        root: LocalLibraryRootRecord,
        relativePath: String,
        text: String,
        mimeType: String,
    ): Boolean =
        runCatching {
                val file = root.resolve(relativePath)
                file.parentFile?.mkdirs()
                file.writeText(text)
                true
            }
            .getOrDefault(false)

    override fun writeBytes(
        root: LocalLibraryRootRecord,
        relativePath: String,
        bytes: ByteArray,
        mimeType: String,
    ): Boolean =
        runCatching {
                val file = root.resolve(relativePath)
                file.parentFile?.mkdirs()
                val tempFile = File(file.parentFile, "${file.name}.refresh")
                tempFile.writeBytes(bytes)
                if (tempFile.length() != bytes.size.toLong()) {
                    tempFile.delete()
                    return@runCatching false
                }
                if (file.exists()) file.delete()
                if (!tempFile.renameTo(file)) {
                    tempFile.copyTo(file, overwrite = true)
                    tempFile.delete()
                }
                true
            }
            .getOrDefault(false)

    override fun exists(root: LocalLibraryRootRecord, relativePath: String): Boolean =
        root.resolve(relativePath).exists()

    override fun isReadable(root: LocalLibraryRootRecord, relativePath: String): Boolean =
        root.resolve(relativePath).let { it.exists() && it.isFile && it.canRead() }

    override fun playerUri(root: LocalLibraryRootRecord, relativePath: String): String =
        root.resolve(relativePath).absolutePath

    override fun assetUri(root: LocalLibraryRootRecord, relativePath: String): String? =
        root.resolve(relativePath).takeIf { it.exists() && it.isFile }?.toPath()?.toUri()?.toASCIIString()

    override fun delete(root: LocalLibraryRootRecord, relativePath: String): Boolean {
        val file = root.resolve(relativePath)
        return !file.exists() || file.delete()
    }

    override fun createMediaWriteTarget(
        root: LocalLibraryRootRecord,
        relativeMediaPath: String,
    ): LocalLibraryMediaWriteTarget {
        val finalFile = root.resolve(relativeMediaPath)
        finalFile.parentFile?.mkdirs()
        val outputFile = File(finalFile.parentFile, "${finalFile.name}.part")
        return FileMediaWriteTarget(outputFile, finalFile)
    }

    private fun LocalLibraryRootRecord.rootFile(): File = File(uriOrPath)

    private fun LocalLibraryRootRecord.resolve(relativePath: String): File =
        if (relativePath.isBlank()) rootFile() else File(rootFile(), relativePath)

    private data class FileMediaWriteTarget(
        private val outputFile: File,
        private val finalFile: File,
    ) : LocalLibraryMediaWriteTarget {
        override val displayPath: String
            get() = outputFile.absolutePath

        override val resumeSize: Long
            get() = if (outputFile.exists()) outputFile.length() else 0L

        override fun openOutputStream(append: Boolean): OutputStream =
            java.io.FileOutputStream(outputFile, append)

        override fun finish(): LocalLibraryCompletedMediaFile {
            moveStagedOutputToFinal()
            require(finalFile.exists() && finalFile.isFile) {
                "Media finish did not create final file ${finalFile.absolutePath}"
            }
            return LocalLibraryCompletedMediaFile(finalFile.absolutePath, finalFile.length())
        }

        override fun deleteIfExists(): Boolean = !outputFile.exists() || outputFile.delete()

        private fun moveStagedOutputToFinal() {
            if (!outputFile.exists()) {
                require(finalFile.exists() && finalFile.isFile) {
                    "Missing staged media file ${outputFile.absolutePath}"
                }
                return
            }
            try {
                Files.move(
                    outputFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                replaceWithBackup()
            }
        }

        private fun replaceWithBackup() {
            val backupFile =
                File(
                    finalFile.parentFile,
                    "${finalFile.name}.replace-${UUID.randomUUID()}.bak",
                )
            var hasBackup = false
            if (finalFile.exists()) {
                require(finalFile.renameTo(backupFile)) {
                    "Failed to protect existing media file ${finalFile.absolutePath}"
                }
                hasBackup = true
            }
            val moved = outputFile.renameTo(finalFile)
            if (!moved) {
                if (hasBackup) backupFile.renameTo(finalFile)
                error("Failed to promote staged media file ${outputFile.absolutePath}")
            }
            if (hasBackup && backupFile.exists()) {
                backupFile.delete()
            }
        }
    }
}

class SafTreeLibraryFileSystem(private val context: Context) : LocalLibraryFileSystem {
    override fun list(root: LocalLibraryRootRecord, relativePath: String): List<LocalLibraryNode> {
        val directoryUri = resolveDocument(root, relativePath, requireDirectory = true)?.uri ?: return emptyList()
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                directoryUri,
                DocumentsContract.getDocumentId(directoryUri),
            )
        return queryChildren(childrenUri, relativePath)
    }

    override fun readText(root: LocalLibraryRootRecord, relativePath: String): String? {
        val documentUri = resolveDocument(root, relativePath, requireDirectory = false)?.uri ?: return null
        return runCatching {
                context.contentResolver.openInputStream(documentUri)?.bufferedReader()?.use { it.readText() }
            }
            .getOrNull()
    }

    override fun writeText(
        root: LocalLibraryRootRecord,
        relativePath: String,
        text: String,
        mimeType: String,
    ): Boolean {
        val documentUri = resolveOrCreateDocument(root, relativePath, mimeType) ?: return false
        return runCatching {
                context.contentResolver.openOutputStream(documentUri, "w")?.bufferedWriter()?.use {
                    it.write(text)
                } != null
            }
            .getOrDefault(false)
    }

    override fun writeBytes(
        root: LocalLibraryRootRecord,
        relativePath: String,
        bytes: ByteArray,
        mimeType: String,
    ): Boolean {
        val documentUri = resolveOrCreateDocument(root, relativePath, mimeType) ?: return false
        return runCatching {
                context.contentResolver.openOutputStream(documentUri, "w")?.use { output ->
                    output.write(bytes)
                } != null
            }
            .getOrDefault(false)
    }

    override fun exists(root: LocalLibraryRootRecord, relativePath: String): Boolean =
        resolveDocument(root, relativePath, requireDirectory = null) != null

    override fun isReadable(root: LocalLibraryRootRecord, relativePath: String): Boolean =
        resolveDocument(root, relativePath, requireDirectory = false) != null

    override fun playerUri(root: LocalLibraryRootRecord, relativePath: String): String =
        resolveDocument(root, relativePath, requireDirectory = false)?.uri?.toString()
            ?: root.uriOrPath

    override fun assetUri(root: LocalLibraryRootRecord, relativePath: String): String? =
        resolveDocument(root, relativePath, requireDirectory = false)?.uri?.toString()

    override fun delete(root: LocalLibraryRootRecord, relativePath: String): Boolean {
        val documentUri = resolveDocument(root, relativePath, requireDirectory = false)?.uri ?: return true
        return runCatching { DocumentsContract.deleteDocument(context.contentResolver, documentUri) }
            .getOrDefault(false)
    }

    override fun createMediaWriteTarget(
        root: LocalLibraryRootRecord,
        relativeMediaPath: String,
    ): LocalLibraryMediaWriteTarget {
        val outputUri =
            resolveOrCreateDocument(
                root = root,
                relativePath = "$relativeMediaPath.part",
                mimeType = "application/octet-stream",
            ) ?: error("Failed to create media target $relativeMediaPath")
        val finalName = relativeMediaPath.substringAfterLast('/')
        return SafMediaWriteTarget(
            context = context,
            fileSystem = this,
            root = root,
            relativeMediaPath = relativeMediaPath,
            outputUri = outputUri,
            finalName = finalName,
        )
    }

    private fun queryChildren(childrenUri: Uri, parentRelativePath: String): List<LocalLibraryNode> =
        runCatching {
                context.contentResolver
                    .query(childrenUri, CHILD_PROJECTION, null, null, null)
                    ?.use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val name =
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            DocumentsContract.Document.COLUMN_DISPLAY_NAME
                                        )
                                    )
                                val mimeType =
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            DocumentsContract.Document.COLUMN_MIME_TYPE
                                        )
                                    )
                                add(
                                    LocalLibraryNode(
                                        relativePath =
                                            listOf(parentRelativePath, name)
                                                .filter { it.isNotBlank() }
                                                .joinToString("/"),
                                        name = name,
                                        isDirectory = mimeType == DOCUMENT_MIME_TYPE_DIR,
                                        sizeBytes =
                                            cursor.getLongOrDefault(
                                                DocumentsContract.Document.COLUMN_SIZE
                                            ),
                                        modifiedAt =
                                            cursor.getLongOrDefault(
                                                DocumentsContract.Document.COLUMN_LAST_MODIFIED
                                            ),
                                    )
                                )
                            }
                        }
                    }
            }
            .getOrNull()
            .orEmpty()

    private fun resolveDocument(
        root: LocalLibraryRootRecord,
        relativePath: String,
        requireDirectory: Boolean?,
    ): SafDocument? {
        val treeUri = runCatching { Uri.parse(root.uriOrPath) }.getOrNull() ?: return null
        if (treeUri.scheme != "content") return null
        var current =
            SafDocument(
                uri =
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri),
                    ),
                mimeType = DOCUMENT_MIME_TYPE_DIR,
            )
        if (relativePath.isBlank()) {
            return current.takeIf { requireDirectory != false }
        }
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        segments.forEachIndexed { index, segment ->
            val requiredMimeType =
                when {
                    index < segments.lastIndex -> DOCUMENT_MIME_TYPE_DIR
                    requireDirectory == true -> DOCUMENT_MIME_TYPE_DIR
                    else -> null
                }
            current = findChild(current.uri, segment, requiredMimeType) ?: return null
        }
        if (requireDirectory == false && current.mimeType == DOCUMENT_MIME_TYPE_DIR) return null
        if (requireDirectory == true && current.mimeType != DOCUMENT_MIME_TYPE_DIR) return null
        return current
    }

    private fun findChild(parent: Uri, name: String, mimeType: String? = null): SafDocument? {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parent,
                DocumentsContract.getDocumentId(parent),
            )
        return runCatching {
                context.contentResolver
                    .query(childrenUri, CHILD_PROJECTION, null, null, null)
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
                                    cursor.getColumnIndexOrThrow(
                                        DocumentsContract.Document.COLUMN_MIME_TYPE
                                    )
                                )
                            if (childName == name && (mimeType == null || childMime == mimeType)) {
                                val docId =
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            DocumentsContract.Document.COLUMN_DOCUMENT_ID
                                        )
                                    )
                                return@use SafDocument(
                                    uri = DocumentsContract.buildDocumentUriUsingTree(parent, docId),
                                    mimeType = childMime,
                                )
                            }
                        }
                        null
                    }
            }
            .getOrNull()
    }

    private fun resolveOrCreateDocument(
        root: LocalLibraryRootRecord,
        relativePath: String,
        mimeType: String,
    ): Uri? {
        val treeUri = runCatching { Uri.parse(root.uriOrPath) }.getOrNull() ?: return null
        if (treeUri.scheme != "content") return null
        var parent =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        segments.dropLast(1).forEach { segment ->
            parent =
                findChild(parent, segment, DOCUMENT_MIME_TYPE_DIR)?.uri
                    ?: DocumentsContract.createDocument(
                        context.contentResolver,
                        parent,
                        DOCUMENT_MIME_TYPE_DIR,
                        segment,
                    )
                    ?: return null
        }
        return findChild(parent, segments.last())?.uri
            ?: DocumentsContract.createDocument(context.contentResolver, parent, mimeType, segments.last())
    }

    private fun android.database.Cursor.getLongOrDefault(columnName: String): Long {
        val columnIndex = getColumnIndex(columnName)
        return if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else 0L
    }

    private data class SafDocument(val uri: Uri, val mimeType: String)

    private class SafMediaWriteTarget(
        private val context: Context,
        private val fileSystem: SafTreeLibraryFileSystem,
        private val root: LocalLibraryRootRecord,
        private val relativeMediaPath: String,
        private val outputUri: Uri,
        private val finalName: String,
    ) : LocalLibraryMediaWriteTarget {
        override val displayPath: String
            get() = outputUri.toString()

        override val resumeSize: Long
            get() = getSize(outputUri)

        override fun openOutputStream(append: Boolean): OutputStream =
            context.contentResolver.openOutputStream(outputUri, if (append) "wa" else "w")
                ?: error("Failed to open $outputUri for writing")

        override fun finish(): LocalLibraryCompletedMediaFile {
            val existingFinal = fileSystem.resolveDocument(root, relativeMediaPath, requireDirectory = false)
            if (existingFinal != null) {
                val existingSize = getSize(existingFinal.uri)
                val stagedSize = getSize(outputUri)
                if (existingSize == stagedSize && stagedSize > 0L) {
                    require(deleteIfExists()) {
                        "Failed to remove duplicate staged media document $outputUri"
                    }
                    return LocalLibraryCompletedMediaFile(existingFinal.uri.toString(), existingSize)
                }
                error("Refusing to replace existing SAF media without atomic replace: $relativeMediaPath")
            }
            val finalUri =
                DocumentsContract.renameDocument(context.contentResolver, outputUri, finalName)
                    ?: error("Failed to rename staged SAF media $outputUri to $finalName")
            val finalSize = getSize(finalUri)
            require(finalSize > 0L) { "Finished SAF media has empty or unknown size: $finalUri" }
            return LocalLibraryCompletedMediaFile(finalUri.toString(), finalSize)
        }

        override fun deleteIfExists(): Boolean =
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, outputUri) }
                .getOrDefault(false)

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

    private companion object {
        const val DOCUMENT_MIME_TYPE_DIR = "vnd.android.document/directory"
        val CHILD_PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }
}
