package com.makd.afinity.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.makd.afinity.data.database.entities.LocalLibraryItemEntity
import com.makd.afinity.data.database.entities.LocalLibraryRootSnapshotEntity
import com.makd.afinity.data.database.entities.LocalLibraryScanRunEntity
import com.makd.afinity.data.database.entities.LocalMediaFileEntity
import com.makd.afinity.data.database.entities.LocalMediaIdentityEntity
import com.makd.afinity.data.database.entities.LocalMediaImportJobEntity
import com.makd.afinity.data.database.entities.LocalMediaSidecarEntity
import com.makd.afinity.data.database.entities.LocalMediaUserStateEntity
import com.makd.afinity.data.database.entities.LocalMediaVisibilityEntity

@Dao
interface LocalLibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRootSnapshot(snapshot: LocalLibraryRootSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertItems(items: List<LocalLibraryItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMediaFiles(files: List<LocalMediaFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertIdentities(identities: List<LocalMediaIdentityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSidecars(sidecars: List<LocalMediaSidecarEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertImportJob(job: LocalMediaImportJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertImportJobs(jobs: List<LocalMediaImportJobEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertScanRun(scanRun: LocalLibraryScanRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertUserState(state: LocalMediaUserStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertVisibility(visibility: LocalMediaVisibilityEntity)

    @Query("DELETE FROM local_media_files WHERE rootRegistryId = :rootRegistryId")
    fun deleteMediaFilesForRoot(rootRegistryId: String)

    @Query("DELETE FROM local_media_import_jobs WHERE rootRegistryId = :rootRegistryId")
    fun deleteImportJobsForRoot(rootRegistryId: String)

    @Query("DELETE FROM local_media_identities WHERE localItemId NOT IN (SELECT localItemId FROM local_media_files)")
    fun deleteOrphanedIdentities()

    @Query("DELETE FROM local_library_items WHERE localItemId NOT IN (SELECT localItemId FROM local_media_files)")
    fun deleteOrphanedItems()

    @Query("DELETE FROM local_media_sidecars WHERE mediaFileId NOT IN (SELECT mediaFileId FROM local_media_files)")
    fun deleteOrphanedSidecars()

    @Query("SELECT * FROM local_media_files ORDER BY rootRegistryId, relativePath")
    fun getAllMediaFiles(): List<LocalMediaFileEntity>

    @Query("SELECT * FROM local_media_files WHERE mediaFileId = :mediaFileId LIMIT 1")
    fun getMediaFile(mediaFileId: String): LocalMediaFileEntity?

    @Query("SELECT * FROM local_media_files WHERE localItemId = :localItemId ORDER BY rootRegistryId, relativePath LIMIT 1")
    fun getMediaFileForLocalItem(localItemId: String): LocalMediaFileEntity?

    @Query("SELECT * FROM local_media_identities WHERE localItemId = :localItemId LIMIT 1")
    fun getIdentity(localItemId: String): LocalMediaIdentityEntity?

    @Query("SELECT * FROM local_media_identities")
    fun getIdentities(): List<LocalMediaIdentityEntity>

    @Query("SELECT * FROM local_library_root_snapshots WHERE registryId = :registryId LIMIT 1")
    fun getRootSnapshot(registryId: String): LocalLibraryRootSnapshotEntity?

    @Query("SELECT * FROM local_library_root_snapshots")
    fun getRootSnapshots(): List<LocalLibraryRootSnapshotEntity>

    @Query("SELECT * FROM local_media_import_jobs ORDER BY rootRegistryId, relativePath")
    fun getImportJobs(): List<LocalMediaImportJobEntity>

    @Query("SELECT * FROM local_media_user_state WHERE localItemId = :localItemId AND profileUserId = :profileUserId LIMIT 1")
    fun getUserState(localItemId: String, profileUserId: String): LocalMediaUserStateEntity?

    @Query("SELECT * FROM local_media_user_state WHERE profileUserId = :profileUserId")
    fun getUserStates(profileUserId: String): List<LocalMediaUserStateEntity>

    @Query("SELECT * FROM local_media_visibility WHERE profileUserId = :profileUserId")
    fun getVisibilities(profileUserId: String): List<LocalMediaVisibilityEntity>

    @Query("UPDATE local_media_files SET visibleByDefault = 0 WHERE mediaFileId = :mediaFileId")
    fun hideMediaFile(mediaFileId: String): Int
}
