package com.makd.afinity.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibraryVisibilityPolicyTest {
    private val policy = LocalLibraryVisibilityPolicy()

    @Test
    fun kidModeDoesNotExposeLocalOnlyImportsByFileExistence() {
        val visible =
            policy.isVisible(
                sidecar = null,
                context =
                    LocalLibraryVisibilityContext(
                        currentUserId = "user-1",
                        kidModeEnabled = true,
                        parentUnlocked = false,
                    ),
            )

        assertFalse(visible)
    }

    @Test
    fun downloadedUserContentIsVisibleForMatchingProfileInKidMode() {
        val visible =
            policy.isVisible(
                sidecar = AfinityMediaSidecar(mediaKind = "movie", user = AfinitySidecarUser("user-1")),
                context =
                    LocalLibraryVisibilityContext(
                        currentUserId = "user-1",
                        kidModeEnabled = true,
                        parentUnlocked = false,
                    ),
            )

        assertTrue(visible)
    }

    @Test
    fun mismatchedUserSidecarIsHiddenFromCurrentProfile() {
        val visible =
            policy.isVisible(
                sidecar = AfinityMediaSidecar(mediaKind = "movie", user = AfinitySidecarUser("other-user")),
                context =
                    LocalLibraryVisibilityContext(
                        currentUserId = "user-1",
                        kidModeEnabled = false,
                        parentUnlocked = false,
                    ),
            )

        assertFalse(visible)
    }
}
