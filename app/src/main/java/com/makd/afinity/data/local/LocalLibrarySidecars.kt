package com.makd.afinity.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.util.UUID

@Serializable
data class AfinityRootSidecar(
    val schemaVersion: Int,
    val rootId: String,
    val createdBy: String,
    val createdAt: Long,
    val displayName: String,
    val libraryLayout: String,
) {
    fun stableRootId(): UUID? = runCatching { UUID.fromString(rootId) }.getOrNull()
}

@Serializable
data class AfinityMediaSidecar(
    val schemaVersion: Int = 1,
    val mediaKind: String,
    val server: AfinitySidecarServer? = null,
    val user: AfinitySidecarUser? = null,
    val identity: AfinitySidecarIdentity? = null,
    val localIdentity: AfinitySidecarLocalIdentity? = null,
    val titles: AfinitySidecarTitles? = null,
    val mediaFile: AfinitySidecarMediaFile? = null,
    val download: AfinitySidecarDownload? = null,
)

@Serializable
data class AfinitySidecarServer(
    val serverId: String? = null,
    val serverName: String? = null,
    val baseUrlHint: String? = null,
)

@Serializable data class AfinitySidecarUser(val userId: String? = null)

@Serializable
data class AfinitySidecarIdentity(
    val itemId: String? = null,
    val sourceId: String? = null,
    val providerIds: Map<String, String> = emptyMap(),
)

@Serializable
data class AfinitySidecarLocalIdentity(
    val localItemId: String? = null,
    val stableRootId: String? = null,
    val rootId: String? = null,
    val relativePathAtWrite: String? = null,
    val fingerprint: AfinitySidecarFingerprint? = null,
)

@Serializable data class AfinitySidecarFingerprint(val strategy: String, val value: String)

@Serializable
data class AfinitySidecarTitles(
    val name: String? = null,
    val showName: String? = null,
    val year: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

@Serializable
data class AfinitySidecarMediaFile(
    val relativePath: String? = null,
    val container: String? = null,
    val sizeBytes: Long? = null,
    val runtimeTicks: Long? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class AfinitySidecarDownload(
    val qualityMode: String? = null,
    val downloadedAt: Long? = null,
    val downloadedByAFinity: Boolean = false,
)

data class LocalSidecarParseResult(
    val sidecar: AfinityMediaSidecar?,
    val warnings: List<String>,
)

class LocalLibrarySidecarReader(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }
) {
    fun readMediaSidecar(rawJson: String): LocalSidecarParseResult {
        val element =
            try {
                json.parseToJsonElement(rawJson)
            } catch (e: SerializationException) {
                return LocalSidecarParseResult(null, listOf("Invalid .afinity.json: ${e.message}"))
            }
        val privacyWarnings = validateNoCredentials(element)
        if (privacyWarnings.isNotEmpty()) return LocalSidecarParseResult(null, privacyWarnings)

        return try {
            LocalSidecarParseResult(json.decodeFromJsonElement(AfinityMediaSidecar.serializer(), element), emptyList())
        } catch (e: SerializationException) {
            LocalSidecarParseResult(null, listOf("Unsupported .afinity.json: ${e.message}"))
        }
    }

    fun encodeMediaSidecar(sidecar: AfinityMediaSidecar): String {
        val rawJson = json.encodeToString(AfinityMediaSidecar.serializer(), sidecar)
        val warnings = validateNoCredentials(json.parseToJsonElement(rawJson))
        require(warnings.isEmpty()) { warnings.joinToString("; ") }
        return rawJson
    }

    fun readRootSidecar(rawJson: String): AfinityRootSidecar? =
        runCatching { json.decodeFromString(AfinityRootSidecar.serializer(), rawJson) }.getOrNull()

    private fun validateNoCredentials(element: JsonElement, path: String = "$"): List<String> {
        val warnings = mutableListOf<String>()
        when (element) {
            is JsonObject -> {
                element.forEach { (key, value) ->
                    val normalized = key.lowercase()
                    if (FORBIDDEN_KEYS.any { normalized.contains(it) }) {
                        warnings += "Forbidden credential field at $path.$key"
                    }
                    warnings += validateNoCredentials(value, "$path.$key")
                }
            }

            is JsonPrimitive -> {
                val value = element.contentOrNull.orEmpty()
                if (looksLikeCredentialBearingUrl(value)) {
                    warnings += "Forbidden credential-bearing URL at $path"
                }
                if (value.startsWith("Bearer ", ignoreCase = true)) {
                    warnings += "Forbidden bearer token value at $path"
                }
            }

            else -> Unit
        }
        return warnings
    }

    private fun looksLikeCredentialBearingUrl(value: String): Boolean {
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        val query = runCatching { URI(value).rawQuery.orEmpty().lowercase() }.getOrDefault("")
        return FORBIDDEN_QUERY_KEYS.any { query.contains("$it=") }
    }

    private companion object {
        val FORBIDDEN_KEYS =
            listOf(
                "access_token",
                "refresh_token",
                "bearer",
                "cookie",
                "authorization",
                "api_key",
                "apikey",
                "token",
            )
        val FORBIDDEN_QUERY_KEYS =
            listOf("api_key", "apikey", "access_token", "refresh_token", "token")
    }
}
