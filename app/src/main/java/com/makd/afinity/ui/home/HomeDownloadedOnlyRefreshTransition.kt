package com.makd.afinity.ui.home

internal class HomeDownloadedOnlyRefreshTransition {
    private var observedInitialValue = false

    fun refreshForceFor(isDownloadedOnly: Boolean): Boolean? {
        val force = observedInitialValue && isDownloadedOnly
        observedInitialValue = true
        return if (isDownloadedOnly) force else null
    }
}
