package com.fishkingsin.manhuagui.ui.settings

import com.fishkingsin.manhuagui.settings.AppSettings
import com.fishkingsin.manhuagui.settings.ReadingDirection
import com.fishkingsin.manhuagui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.manhuagui_ffi.AndroidUpdateWindow

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val onIntervalChanged: (Int) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    val settings: StateFlow<AppSettings> = settingsStore.settings
        .map(AppSettings::normalized)
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    fun setReadingDirection(direction: ReadingDirection) {
        updateSettings(transform = { it.copy(readingDirection = direction) })
    }

    fun setDefaultUpdateWindow(window: AndroidUpdateWindow) {
        updateSettings(transform = { it.copy(defaultUpdateWindow = window) })
    }

    fun setCheckIntervalHours(hours: Int) {
        updateSettings(
            transform = { it.copy(checkIntervalHours = hours) },
            afterUpdate = { updated -> onIntervalChanged(updated.checkIntervalHours) },
        )
    }

    fun setOfflineOnlyMode(enabled: Boolean) {
        updateSettings(transform = { it.copy(offlineOnlyMode = enabled) })
    }

    fun setPrefetchCount(count: Int) {
        updateSettings(transform = { it.copy(prefetchCount = count) })
    }

    fun setDiskCacheSizeMb(sizeMb: Int) {
        updateSettings(transform = { it.copy(diskCacheSizeMb = sizeMb) })
    }

    private fun updateSettings(
        transform: (AppSettings) -> AppSettings,
        afterUpdate: (AppSettings) -> Unit = {},
    ) {
        scope.launch {
            afterUpdate(settingsStore.updateSettings(transform))
        }
    }
}
