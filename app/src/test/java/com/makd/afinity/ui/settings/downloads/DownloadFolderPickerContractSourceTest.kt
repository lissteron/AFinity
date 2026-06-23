package com.makd.afinity.ui.settings.downloads

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFolderPickerContractSourceTest {
    @Test
    fun folderPickerLaunchIsPreflightedAndDiagnosable() {
        val screen = readSource("src/main/java/com/makd/afinity/ui/settings/downloads/DownloadSettingsScreen.kt")
        val viewModel = readSource("src/main/java/com/makd/afinity/ui/downloads/DownloadsViewModel.kt")
        val manifest = readSource("src/main/AndroidManifest.xml")

        assertTrue(screen.contains("canOpenSystemFolderPicker(context)"))
        assertTrue(screen.contains("Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)"))
        assertTrue(screen.contains(".resolveActivity(context.packageManager)"))
        assertTrue(screen.contains("runCatching { launch() }"))
        assertTrue(screen.contains("onFolderPickerLaunchFailed"))
        assertFalse(screen.contains("onChooseFolder = { folderPickerLauncher.launch(null) }"))
        assertFalse(screen.contains("onAddLocalLibraryFolder = { localLibraryFolderPickerLauncher.launch(null) }"))

        assertTrue(viewModel.contains("fun onFolderPickerUnavailable"))
        assertTrue(viewModel.contains("fun onFolderPickerLaunchFailed"))
        assertTrue(viewModel.contains("System folder picker is unavailable in this profile"))
        assertTrue(viewModel.contains("Timber.w("))
        assertTrue(viewModel.contains("Timber.e(error"))

        assertTrue(manifest.contains("<queries>"))
        assertTrue(manifest.contains("android.intent.action.OPEN_DOCUMENT_TREE"))
    }

    private fun readSource(relativePath: String): String = File(relativePath).readText()
}
