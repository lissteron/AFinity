package com.makd.afinity.data.updater

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateScheduler @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun cancelUpdateChecks() {
        workManager.cancelUniqueWork(UpdateCheckWorker.WORK_NAME)
        Timber.d("Cancelled scheduled update checks")
    }
}
