package com.makd.afinity.data.repository.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DownloadQueueSchedulerState {
    private val _deferredUidtSchedule = MutableStateFlow(false)
    val deferredUidtSchedule: StateFlow<Boolean> = _deferredUidtSchedule.asStateFlow()

    private val _schedulerMessage = MutableStateFlow<String?>(null)
    val schedulerMessage: StateFlow<String?> = _schedulerMessage.asStateFlow()

    fun clear() {
        _deferredUidtSchedule.value = false
        _schedulerMessage.value = null
    }

    fun recordUidtDeferral(reason: String) {
        _deferredUidtSchedule.value = true
        _schedulerMessage.value = reason
    }

    fun recordSchedulerFailure(reason: String) {
        _deferredUidtSchedule.value = false
        _schedulerMessage.value = reason
    }
}
