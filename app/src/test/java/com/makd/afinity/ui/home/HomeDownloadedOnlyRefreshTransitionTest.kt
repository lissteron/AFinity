package com.makd.afinity.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDownloadedOnlyRefreshTransitionTest {
    @Test
    fun initialDownloadedOnlyEmissionRefreshesWithoutForce() {
        val transition = HomeDownloadedOnlyRefreshTransition()

        assertEquals(false, transition.refreshForceFor(isDownloadedOnly = true))
    }

    @Test
    fun transitionIntoDownloadedOnlyAfterInitialEmissionRefreshesWithForce() {
        val transition = HomeDownloadedOnlyRefreshTransition()

        assertNull(transition.refreshForceFor(isDownloadedOnly = false))

        assertEquals(true, transition.refreshForceFor(isDownloadedOnly = true))
    }

    @Test
    fun remoteAvailableEmissionDoesNotRequestDownloadedRefresh() {
        val transition = HomeDownloadedOnlyRefreshTransition()

        assertNull(transition.refreshForceFor(isDownloadedOnly = false))
    }
}
