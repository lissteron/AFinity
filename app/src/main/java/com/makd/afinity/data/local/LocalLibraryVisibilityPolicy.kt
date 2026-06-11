package com.makd.afinity.data.local

data class LocalLibraryVisibilityContext(
    val currentUserId: String?,
    val kidModeEnabled: Boolean,
    val parentUnlocked: Boolean,
)

class LocalLibraryVisibilityPolicy {
    fun isVisible(sidecar: AfinityMediaSidecar?, context: LocalLibraryVisibilityContext): Boolean {
        return isVisible(sidecar?.user?.userId, context)
    }

    fun isVisible(ownerUserId: String?, context: LocalLibraryVisibilityContext): Boolean {
        if (context.kidModeEnabled && !context.parentUnlocked) {
            return !ownerUserId.isNullOrBlank() && ownerUserId == context.currentUserId
        }
        return ownerUserId.isNullOrBlank() || ownerUserId == context.currentUserId
    }
}
