package com.makd.afinity.data.repository.download

internal object DownloadQueueTransientFailureClassifier {
    val sqlLikePatterns =
        listOf(
            "Download job stopped%",
            "Download interrupted%",
            "%Canceled%",
            "%Cancelled%",
            "%Socket%",
            "%UnknownHost%",
            "%Unable to resolve host%",
            "%failed to connect%",
            "%Connection reset%",
            "%Connection refused%",
            "%Connection shutdown%",
            "%Network is unreachable%",
            "%No address associated%",
            "%Software caused connection abort%",
            "%timeout%",
            "%timed out%",
            "%HTTP host unreachable%",
            "%foreground notification%",
            "%foreground service%",
            "%UIDT required network%",
        )

    private val messageTokens =
        listOf(
            "canceled",
            "cancelled",
            "socket",
            "unknownhost",
            "unable to resolve host",
            "failed to connect",
            "connection reset",
            "connection refused",
            "connection shutdown",
            "network is unreachable",
            "no address associated",
            "software caused connection abort",
            "timeout",
            "timed out",
            "http host unreachable",
            "foreground notification",
            "foreground service",
            "uidt required network",
        )

    fun isTransientReason(reason: String): Boolean =
        messageTokens.any { token -> reason.lowercase().contains(token) }

    fun isTransientFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .any { throwable ->
                isTransientReason(throwable::class.java.name) ||
                    throwable.message?.let(::isTransientReason) == true
            }
}
