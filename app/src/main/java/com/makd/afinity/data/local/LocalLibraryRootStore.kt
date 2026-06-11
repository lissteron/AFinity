package com.makd.afinity.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.makd.afinity.di.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface LocalLibraryRootStore {
    fun rootsFlow(): Flow<List<LocalLibraryRootRecord>>

    suspend fun getRoots(): List<LocalLibraryRootRecord>

    suspend fun replaceRoots(roots: List<LocalLibraryRootRecord>)

    suspend fun upsertRoot(root: LocalLibraryRootRecord)

    suspend fun removeRoot(registryId: UUID)

    suspend fun setDefaultDownloadRoot(registryId: UUID)
}

@Singleton
class DataStoreLocalLibraryRootStore
@Inject
constructor(@param:AppPreferences private val dataStore: DataStore<Preferences>) :
    LocalLibraryRootStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun rootsFlow(): Flow<List<LocalLibraryRootRecord>> =
        dataStore.data.map { preferences ->
            preferences[Keys.LOCAL_LIBRARY_ROOTS]?.let(::decodeRoots).orEmpty()
        }

    override suspend fun getRoots(): List<LocalLibraryRootRecord> {
        return dataStore.data.first()[Keys.LOCAL_LIBRARY_ROOTS]?.let(::decodeRoots).orEmpty()
    }

    override suspend fun replaceRoots(roots: List<LocalLibraryRootRecord>) {
        val normalized = roots.normalizedDefaults()
        dataStore.edit { preferences ->
            preferences[Keys.LOCAL_LIBRARY_ROOTS] =
                json.encodeToString(normalized.map { it.toStored() })
        }
    }

    override suspend fun upsertRoot(root: LocalLibraryRootRecord) {
        val roots = getRoots().filterNot { it.registryId == root.registryId } + root
        replaceRoots(roots)
    }

    override suspend fun removeRoot(registryId: UUID) {
        replaceRoots(getRoots().filterNot { it.registryId == registryId })
    }

    override suspend fun setDefaultDownloadRoot(registryId: UUID) {
        replaceRoots(
            getRoots().map { root ->
                root.copy(defaultForDownloads = root.registryId == registryId)
            }
        )
    }

    private fun decodeRoots(raw: String): List<LocalLibraryRootRecord> =
        runCatching {
                json.decodeFromString<List<StoredLocalLibraryRoot>>(raw).mapNotNull { it.toDomain() }
            }
            .getOrDefault(emptyList())
            .normalizedDefaults()

    private fun List<LocalLibraryRootRecord>.normalizedDefaults(): List<LocalLibraryRootRecord> {
        val enabledWritable = filter { it.enabled && it.writable }
        val defaultId =
            firstOrNull { it.defaultForDownloads && it.enabled && it.writable }?.registryId
                ?: enabledWritable.firstOrNull()?.registryId
        return mapIndexed { index, root ->
            root.copy(defaultForDownloads = root.registryId == defaultId, priority = root.priority.takeIf { it != 0 } ?: index)
        }
    }

    private fun LocalLibraryRootRecord.toStored(): StoredLocalLibraryRoot =
        StoredLocalLibraryRoot(
            registryId = registryId.toString(),
            stableRootId = stableRootId?.toString(),
            displayName = displayName,
            kind = kind.name,
            uriOrPath = uriOrPath,
            enabled = enabled,
            writable = writable,
            removable = removable,
            defaultForDownloads = defaultForDownloads,
            priority = priority,
            persistedUriPermission = persistedUriPermission,
            lastKnownAvailable = lastKnownAvailable,
        )

    private fun StoredLocalLibraryRoot.toDomain(): LocalLibraryRootRecord? {
        val registryUuid = runCatching { UUID.fromString(registryId) }.getOrNull() ?: return null
        val stableUuid = stableRootId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val rootKind = runCatching { LocalLibraryRootKind.valueOf(kind) }.getOrDefault(LocalLibraryRootKind.APP_PRIVATE)
        return LocalLibraryRootRecord(
            registryId = registryUuid,
            stableRootId = stableUuid,
            displayName = displayName,
            kind = rootKind,
            uriOrPath = uriOrPath,
            enabled = enabled,
            writable = writable,
            removable = removable,
            defaultForDownloads = defaultForDownloads,
            priority = priority,
            persistedUriPermission = persistedUriPermission,
            lastKnownAvailable = lastKnownAvailable,
        )
    }

    private object Keys {
        val LOCAL_LIBRARY_ROOTS = stringPreferencesKey("local_library_roots_v2")
    }
}

@Serializable
private data class StoredLocalLibraryRoot(
    val registryId: String,
    val stableRootId: String?,
    val displayName: String,
    val kind: String,
    val uriOrPath: String,
    val enabled: Boolean,
    val writable: Boolean,
    val removable: Boolean,
    val defaultForDownloads: Boolean,
    val priority: Int,
    val persistedUriPermission: Boolean,
    val lastKnownAvailable: Boolean,
)
