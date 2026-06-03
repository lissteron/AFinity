package com.makd.afinity.data.repository.download

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AppVisibilityTracker
@Inject
constructor(@param:ApplicationContext context: Context) {
    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var startedCount = 0

                override fun onActivityStarted(activity: Activity) {
                    startedCount += 1
                    _isVisible.value = startedCount > 0
                }

                override fun onActivityStopped(activity: Activity) {
                    startedCount = (startedCount - 1).coerceAtLeast(0)
                    _isVisible.value = startedCount > 0
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    fun isVisibleNow(): Boolean = _isVisible.value
}

enum class DownloadQueueScheduleTrigger(val userInitiatedVisible: Boolean) {
    USER_ACTION(userInitiatedVisible = true),
    VISIBLE_LIVENESS(userInitiatedVisible = true),
    PASSIVE_BACKGROUND(userInitiatedVisible = false),
    LEGACY_WORKER(userInitiatedVisible = false),
    MIGRATION(userInitiatedVisible = false),
}
