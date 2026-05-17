package com.makd.afinity.data.models.download

enum class DownloadQualityMode(
    val preferenceValue: String,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val cpuCoreLimit: Int?,
    val outputExtension: String?,
    val optimizeVideo: Boolean,
    val reencodeSuitableHevc: Boolean,
    val qualityCrf: Int?,
    val qualityPreset: String?,
    val qualityBias: QualityBias,
) {
    ORIGINAL(
        preferenceValue = "original",
        maxWidth = null,
        maxHeight = null,
        cpuCoreLimit = null,
        outputExtension = null,
        optimizeVideo = false,
        reencodeSuitableHevc = false,
        qualityCrf = null,
        qualityPreset = null,
        qualityBias = QualityBias.ORIGINAL,
    ),
    HEVC_QUALITY(
        preferenceValue = "cpu_hevc_compact",
        maxWidth = null,
        maxHeight = null,
        cpuCoreLimit = 10,
        outputExtension = "mp4",
        optimizeVideo = true,
        reencodeSuitableHevc = false,
        qualityCrf = 21,
        qualityPreset = "slow",
        qualityBias = QualityBias.QUALITY,
    ),
    HEVC_STORAGE_SAVER(
        preferenceValue = "cpu_hevc_storage_saver",
        maxWidth = null,
        maxHeight = null,
        cpuCoreLimit = 10,
        outputExtension = "mp4",
        optimizeVideo = true,
        reencodeSuitableHevc = true,
        qualityCrf = 24,
        qualityPreset = "slow",
        qualityBias = QualityBias.STORAGE_SAVER,
    );

    val requiresTranscode: Boolean
        get() = optimizeVideo

    val displayName: String
        get() =
            when (this) {
                ORIGINAL -> "Original"
                HEVC_QUALITY -> "HEVC best quality"
                HEVC_STORAGE_SAVER -> "HEVC storage saver"
            }

    companion object {
        fun fromPreference(value: String?): DownloadQualityMode =
            entries.firstOrNull { it.preferenceValue == value }
                ?: HEVC_QUALITY
    }

    enum class QualityBias {
        ORIGINAL,
        QUALITY,
        STORAGE_SAVER,
    }
}
