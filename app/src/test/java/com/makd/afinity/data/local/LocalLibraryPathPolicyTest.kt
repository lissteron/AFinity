package com.makd.afinity.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLibraryPathPolicyTest {
    private val policy = LocalLibraryPathPolicy()

    @Test
    fun moviePathUsesReadableCanonicalFolder() {
        assertEquals(
            "Movies/WALL-E (2008)/WALL-E (2008).mkv",
            policy.movieMediaPath("WALL-E", 2008, ".MKV"),
        )
    }

    @Test
    fun episodePathUsesShowSeasonAndEpisodeShape() {
        assertEquals(
            "Shows/Bluey/Season 01/Bluey - S01E01 - The Magic Xylophone.mp4",
            policy.episodeMediaPath("Bluey", 1, 1, "The Magic Xylophone", "mp4"),
        )
    }

    @Test
    fun pathSegmentsAreSanitizedDeterministically() {
        assertEquals(
            "Shows/Show Name/Season 02/Show Name - S02E03 - Bad Title.mkv",
            policy.episodeMediaPath("Show/Name", 2, 3, "Bad:Title?", "mkv"),
        )
    }
}
