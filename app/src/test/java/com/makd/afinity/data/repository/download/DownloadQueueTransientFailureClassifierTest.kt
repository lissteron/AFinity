package com.makd.afinity.data.repository.download

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTransientFailureClassifierTest {
    @Test
    fun wrappedUnknownHostFailureIsTransient() {
        val failure =
            IllegalStateException(
                "fetching item metadata failed",
                UnknownHostException("Unable to resolve host jellyfin.local"),
            )

        assertTrue(DownloadQueueTransientFailureClassifier.isTransientFailure(failure))
    }

    @Test
    fun timeoutFailureIsTransient() {
        val failure = SocketTimeoutException("timeout")

        assertTrue(DownloadQueueTransientFailureClassifier.isTransientFailure(failure))
    }

    @Test
    fun terminalBusinessFailureIsNotTransient() {
        val failure = IllegalArgumentException("Unsupported item type: Folder")

        assertFalse(DownloadQueueTransientFailureClassifier.isTransientFailure(failure))
    }

    @Test
    fun sqlPatternsMatchStagePrefixedFailures() {
        val stagePrefixed = "fetching item metadata: java.net.UnknownHostException: host"

        assertTrue(
            DownloadQueueTransientFailureClassifier.sqlLikePatterns.any { pattern ->
                stagePrefixed.like(pattern)
            }
        )
    }

    private fun String.like(pattern: String): Boolean {
        val regex =
            pattern
                .replace("\\", "\\\\")
                .replace(".", "\\.")
                .replace("%", ".*")
        return Regex(regex, RegexOption.IGNORE_CASE).matches(this)
    }
}
