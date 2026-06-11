package com.makd.afinity.data.local

import com.makd.afinity.data.storage.DownloadStorageManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalLibraryRootBootstrapper
@Inject
    constructor(
        private val rootStore: LocalLibraryRootStore,
        private val downloadStorageManager: DownloadStorageManager,
        private val rootMarkerRepository: LocalLibraryRootMarkerRepository,
    ) {
    suspend fun ensureDefaultRoot(
        preferSelectedDownloadLocation: Boolean = false
    ): LocalLibraryRootRecord {
        val selectedRoot = selectedDownloadRoot()
        val roots = rootStore.getRoots()
        val existingRoot =
            roots.firstOrNull { it.kind == selectedRoot.kind && it.uriOrPath == selectedRoot.uriOrPath }
        if (!preferSelectedDownloadLocation) {
            roots.firstOrNull { it.defaultForDownloads && it.enabled && it.writable }?.let {
                return it
            }
            roots.firstOrNull { it.enabled && it.writable }?.let { root ->
                rootStore.setDefaultDownloadRoot(root.registryId)
                return root.copy(defaultForDownloads = true)
            }
        }
        if (existingRoot != null) {
            val updatedRoot =
                existingRoot.copy(
                    stableRootId = existingRoot.stableRootId ?: selectedRoot.stableRootId,
                    displayName = selectedRoot.displayName,
                    writable = selectedRoot.writable,
                    removable = selectedRoot.removable,
                    persistedUriPermission = selectedRoot.persistedUriPermission,
                    lastKnownAvailable = true,
                )
            rootStore.upsertRoot(updatedRoot)
            rootStore.setDefaultDownloadRoot(updatedRoot.registryId)
            return updatedRoot.copy(defaultForDownloads = true)
        }

        rootStore.upsertRoot(selectedRoot)
        rootStore.setDefaultDownloadRoot(selectedRoot.registryId)
        return selectedRoot
    }

    private suspend fun selectedDownloadRoot(): LocalLibraryRootRecord {
        val selectedLocation =
            downloadStorageManager.getAvailableLocations().firstOrNull { it.isSelected }
        if (selectedLocation?.isCustom == true && selectedLocation.path.isNotBlank()) {
            val fallbackRootId =
                UUID.nameUUIDFromBytes("saf:${selectedLocation.path}".toByteArray(StandardCharsets.UTF_8))
            val root =
                LocalLibraryRootRecord(
                    registryId = fallbackRootId,
                    stableRootId = null,
                    displayName = selectedLocation.name.ifBlank { "AFinity Library" },
                    kind = LocalLibraryRootKind.SAF_TREE,
                    uriOrPath = selectedLocation.path,
                    enabled = true,
                    writable = true,
                    removable = true,
                    defaultForDownloads = true,
                    priority = 0,
                    persistedUriPermission = true,
                    lastKnownAvailable = true,
                )
            val stableRootId =
                rootMarkerRepository.readOrCreateStableRootId(root, root.displayName)
            return root.copy(
                registryId = stableRootId,
                stableRootId = stableRootId,
            )
        }

        val rootDir =
            selectedLocation
                ?.path
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?: downloadStorageManager.getSelectedDownloadsRoot()
        val fallbackRootId =
            UUID.nameUUIDFromBytes("path:${rootDir.absolutePath}".toByteArray(StandardCharsets.UTF_8))
        val root =
            LocalLibraryRootRecord(
                registryId = fallbackRootId,
                stableRootId = null,
                displayName = (selectedLocation?.name ?: rootDir.name).ifBlank { "AFinity Library" },
                kind = if (selectedLocation?.id == "app_private") LocalLibraryRootKind.APP_PRIVATE else LocalLibraryRootKind.DEVICE_SHARED,
                uriOrPath = rootDir.absolutePath,
                enabled = true,
                writable = true,
                removable = selectedLocation?.id?.startsWith("secondary:") == true,
                defaultForDownloads = true,
                priority = 0,
                persistedUriPermission = false,
                lastKnownAvailable = true,
            )
        rootDir.mkdirs()
        val stableRootId = rootMarkerRepository.readOrCreateStableRootId(root, root.displayName)
        return root.copy(
            registryId = stableRootId,
            stableRootId = stableRootId,
        )
    }
}
