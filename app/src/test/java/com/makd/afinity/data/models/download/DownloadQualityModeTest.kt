package com.makd.afinity.data.models.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQualityModeTest {
    @Test
    fun missingOrUnknownPreferenceUsesHevcOptimizedDownloads() {
        assertEquals(DownloadQualityMode.HEVC_QUALITY, DownloadQualityMode.fromPreference(null))
        assertEquals(DownloadQualityMode.HEVC_QUALITY, DownloadQualityMode.fromPreference("legacy"))
    }

    @Test
    fun explicitHevcPreferenceStillUsesOptimizedDownloads() {
        assertEquals(
            DownloadQualityMode.HEVC_QUALITY,
            DownloadQualityMode.fromPreference("cpu_hevc_compact"),
        )
    }
}
