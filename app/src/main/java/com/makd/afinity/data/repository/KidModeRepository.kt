package com.makd.afinity.data.repository

import kotlinx.coroutines.flow.StateFlow

data class AppCapabilityPolicy(
    val isKidModeEnabled: Boolean = false,
    val isParentUnlocked: Boolean = false,
) {
    val canOpenSettings: Boolean
        get() = !isKidModeEnabled || isParentUnlocked

    val canUseMutableNavigation: Boolean
        get() = true

    val canModifyUserData: Boolean
        get() = true

    val canUseSearch: Boolean
        get() = !isKidModeEnabled || isParentUnlocked

    val canUseDiscoveryUi: Boolean
        get() = !isKidModeEnabled || isParentUnlocked

    val canOpenExternalLinks: Boolean
        get() = !isKidModeEnabled || isParentUnlocked

    val canManageRequests: Boolean
        get() = !isKidModeEnabled || isParentUnlocked

    val canManageDownloads: Boolean
        get() = !isKidModeEnabled || isParentUnlocked

    val canDeleteDownloads: Boolean
        get() = !isKidModeEnabled || isParentUnlocked

    val canManageServers: Boolean
        get() = !isKidModeEnabled || isParentUnlocked
}

interface KidModeRepository {
    val isKidModeEnabled: StateFlow<Boolean>
    val isParentUnlocked: StateFlow<Boolean>
    val policy: StateFlow<AppCapabilityPolicy>

    suspend fun enableKidMode(pin: String): Result<Unit>

    suspend fun disableKidMode(pin: String): Result<Unit>

    suspend fun verifyParentPin(pin: String): Boolean

    fun lockParent()
}
