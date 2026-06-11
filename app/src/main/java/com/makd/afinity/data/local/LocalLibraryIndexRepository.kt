package com.makd.afinity.data.local

import java.util.UUID

interface LocalLibraryIndexRepository {
    fun replaceRootScan(
        root: LocalLibraryRootRecord,
        files: List<LocalMediaFileRecord>,
        importJobs: List<LocalMediaImportJobRecord> = emptyList(),
    )

    fun markRootUnavailable(root: LocalLibraryRootRecord)

    fun allMediaFiles(): List<LocalMediaFileRecord>

    fun visibleMediaFiles(): List<LocalMediaFileRecord>

    fun visibleMediaFiles(visibilityContext: LocalLibraryVisibilityContext): List<LocalMediaFileRecord>

    fun findByMediaFileId(mediaFileId: UUID): LocalMediaFileRecord?

    fun findByLocalItemId(localItemId: String): LocalMediaFileRecord?

    fun duplicateGroupCount(): Int

    fun recordImportJob(job: LocalMediaImportJobRecord)

    fun importJobs(): List<LocalMediaImportJobRecord>
}

class InMemoryLocalLibraryIndexRepository : LocalLibraryIndexRepository {
    private val filesByRootAndPath = linkedMapOf<Pair<UUID, String>, LocalMediaFileRecord>()
    private val unavailableRoots = mutableSetOf<UUID>()
    private val rootPriorities = mutableMapOf<UUID, Int>()

    private val importJobsByRootAndPath = linkedMapOf<Pair<UUID, String>, LocalMediaImportJobRecord>()

    override fun replaceRootScan(
        root: LocalLibraryRootRecord,
        files: List<LocalMediaFileRecord>,
        importJobs: List<LocalMediaImportJobRecord>,
    ) {
        filesByRootAndPath.keys.removeAll { it.first == root.registryId }
        importJobsByRootAndPath.keys.removeAll { it.first == root.registryId }
        files.forEach { file -> filesByRootAndPath[root.registryId to file.relativePath] = file }
        importJobs.forEach { job -> importJobsByRootAndPath[root.registryId to job.relativePath] = job }
        unavailableRoots.remove(root.registryId)
        rootPriorities[root.registryId] = root.priority
    }

    override fun markRootUnavailable(root: LocalLibraryRootRecord) {
        unavailableRoots += root.registryId
        rootPriorities[root.registryId] = root.priority
    }

    override fun allMediaFiles(): List<LocalMediaFileRecord> = filesByRootAndPath.values.toList()

    override fun visibleMediaFiles(): List<LocalMediaFileRecord> =
        visibleMediaFiles { it.visibleByDefault && it.rootRegistryId !in unavailableRoots }

    override fun visibleMediaFiles(
        visibilityContext: LocalLibraryVisibilityContext
    ): List<LocalMediaFileRecord> =
        visibleMediaFiles {
            it.visibleByDefault &&
                it.rootRegistryId !in unavailableRoots &&
                LocalLibraryVisibilityPolicy().isVisible(it.ownerUserId, visibilityContext)
        }

    private fun visibleMediaFiles(
        predicate: (LocalMediaFileRecord) -> Boolean
    ): List<LocalMediaFileRecord> =
        filesByRootAndPath.values
            .filter(predicate)
            .groupBy { it.identity.durableKey }
            .values
            .map { group ->
                group
                    .sortedWith(
                        compareBy<LocalMediaFileRecord> {
                                rootPriorities[it.rootRegistryId] ?: Int.MAX_VALUE
                            }
                            .thenBy { it.rootRegistryId }
                            .thenBy { it.relativePath }
                    )
                    .first()
            }

    override fun findByMediaFileId(mediaFileId: UUID): LocalMediaFileRecord? =
        filesByRootAndPath.values.firstOrNull { it.mediaFileId == mediaFileId }

    override fun findByLocalItemId(localItemId: String): LocalMediaFileRecord? =
        visibleMediaFiles().firstOrNull { it.identity.localItemId == localItemId }

    override fun duplicateGroupCount(): Int =
        filesByRootAndPath.values.groupBy { it.identity.durableKey }.count { it.value.size > 1 }

    override fun recordImportJob(job: LocalMediaImportJobRecord) {
        importJobsByRootAndPath[job.rootRegistryId to job.relativePath] = job
    }

    override fun importJobs(): List<LocalMediaImportJobRecord> = importJobsByRootAndPath.values.toList()
}
