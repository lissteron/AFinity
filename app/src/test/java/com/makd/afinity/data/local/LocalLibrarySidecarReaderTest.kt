package com.makd.afinity.data.local

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibrarySidecarReaderTest {
    private val reader = LocalLibrarySidecarReader()

    @Test
    fun acceptsPortableIdentitySidecarWithoutCredentials() {
        val result =
            reader.readMediaSidecar(
                """
                {
                  "schemaVersion": 1,
                  "mediaKind": "movie",
                  "server": {
                    "serverId": "server",
                    "baseUrlHint": "http://192.168.0.10:8096"
                  },
                  "identity": { "itemId": "movie-1", "sourceId": "source-1" },
                  "titles": { "name": "Movie", "year": 2026 },
                  "mediaFile": { "relativePath": "Movies/Movie (2026)/Movie (2026).mkv" }
                }
                """
                    .trimIndent()
            )

        assertNotNull(result.sidecar)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun rejectsCredentialFieldsAndCredentialBearingUrls() {
        val result =
            reader.readMediaSidecar(
                """
                {
                  "schemaVersion": 1,
                  "mediaKind": "movie",
                  "server": {
                    "serverId": "server",
                    "baseUrlHint": "http://host/Videos/movie/stream?api_key=secret"
                  },
                  "accessToken": "secret",
                  "titles": { "name": "Movie" }
                }
                """
                    .trimIndent()
            )

        assertNull(result.sidecar)
        assertTrue(result.warnings.any { it.contains("Forbidden") })
    }
}
