package com.makd.afinity.data.models.download

data class DownloadStorageLocation(
    val id: String,
    val name: String,
    val description: String,
    val path: String,
    val freeBytes: Long,
    val isSelected: Boolean,
    val isCustom: Boolean = false,
)
