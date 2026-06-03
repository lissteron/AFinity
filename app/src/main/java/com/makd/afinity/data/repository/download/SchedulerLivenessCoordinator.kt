package com.makd.afinity.data.repository.download

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class SchedulerLivenessCoordinator
@Inject
constructor(
    private val visibilityTracker: AppVisibilityTracker,
    private val stateStore: DownloadQueueStateStore,
    private val scheduler: DownloadQueueScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            var lastVisible = false
            visibilityTracker.isVisible.collect { visible ->
                if (visible == lastVisible) return@collect
                lastVisible = visible
                if (!visible) return@collect
                val snapshot = stateStore.snapshot()
                if (!snapshot.hasRunnableQueuedRows && !scheduler.deferredUidtSchedule.value) {
                    return@collect
                }
                Timber.d("Visible entry point detected; checking deferred download queue scheduling")
                scheduler.scheduleQueue(DownloadQueueScheduleTrigger.VISIBLE_LIVENESS)
            }
        }
    }
}
