package com.makd.afinity.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalLibraryRootManager
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val rootStore: LocalLibraryRootStore,
    private val rootMarkerRepository: LocalLibraryRootMarkerRepository,
) {
    suspend fun addSafRoot(uri: Uri): LocalLibraryRootRecord {
        val readPermission = takePersistablePermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val writePermission = takePersistablePermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val fallbackRootId = UUID.nameUUIDFromBytes("saf:${uri}".toByteArray(StandardCharsets.UTF_8))
        val displayName = displayName(uri)
        val provisionalRoot =
            LocalLibraryRootRecord(
                registryId = fallbackRootId,
                stableRootId = null,
                displayName = displayName,
                kind = LocalLibraryRootKind.SAF_TREE,
                uriOrPath = uri.toString(),
                enabled = true,
                writable = writePermission,
                removable = true,
                defaultForDownloads = false,
                priority = rootStore.getRoots().size,
                persistedUriPermission = readPermission || writePermission,
                lastKnownAvailable = readPermission || writePermission,
            )
        val markerRootId = rootMarkerRepository.readStableRootId(provisionalRoot)
        val stableRootId =
            markerRootId
                ?: if (writePermission) {
                    runCatching {
                            rootMarkerRepository.readOrCreateStableRootId(
                                provisionalRoot,
                                displayName,
                            )
                        }
                        .getOrNull()
                } else {
                    null
                }
        val registryId = stableRootId ?: fallbackRootId
        val writable = writePermission && stableRootId != null
        val roots = rootStore.getRoots()
        val existingRoot =
            roots.firstOrNull {
                it.registryId == registryId ||
                    (stableRootId != null && it.stableRootId == stableRootId) ||
                    it.uriOrPath == uri.toString()
            }
        val root =
            existingRoot?.copy(
                registryId = registryId,
                stableRootId = stableRootId,
                displayName = displayName,
                enabled = true,
                writable = writable,
                defaultForDownloads = existingRoot.defaultForDownloads && writable,
                persistedUriPermission = readPermission || writePermission,
                lastKnownAvailable = readPermission || writePermission,
            )
                ?: LocalLibraryRootRecord(
                    registryId = registryId,
                    stableRootId = stableRootId,
                    displayName = displayName,
                    kind = LocalLibraryRootKind.SAF_TREE,
                    uriOrPath = uri.toString(),
                    enabled = true,
                    writable = writable,
                    removable = true,
                    defaultForDownloads = false,
                    priority = roots.size,
                    persistedUriPermission = readPermission || writePermission,
                    lastKnownAvailable = readPermission || writePermission,
                )
        rootStore.upsertRoot(root)
        return root
    }

    suspend fun setEnabled(registryId: UUID, enabled: Boolean) {
        val roots =
            rootStore.getRoots().map { root ->
                if (root.registryId == registryId) {
                    root.copy(enabled = enabled, defaultForDownloads = root.defaultForDownloads && enabled)
                } else {
                    root
                }
            }
        rootStore.replaceRoots(roots)
    }

    suspend fun setDefaultForDownloads(registryId: UUID) {
        rootStore.setDefaultDownloadRoot(registryId)
    }

    suspend fun removeRoot(registryId: UUID) {
        rootStore.removeRoot(registryId)
    }

    private fun takePersistablePermission(uri: Uri, flag: Int): Boolean =
        runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flag)
                true
            }
            .getOrElse {
                context.contentResolver.persistedUriPermissions.any { permission ->
                    permission.uri == uri &&
                        if (flag == Intent.FLAG_GRANT_READ_URI_PERMISSION) {
                            permission.isReadPermission
                        } else {
                            permission.isWritePermission
                        }
                }
            }

    private fun displayName(uri: Uri): String {
        val rootUri =
            DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri),
            )
        return runCatching {
                context.contentResolver
                    .query(rootUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) cursor.getString(index) else null
                        } else {
                            null
                        }
                    }
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Local library folder"
    }
}
