package com.fishkingsin.manhuagui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import uniffi.manhuagui_ffi.AndroidUpdateWindow

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun getSettings(): AppSettings

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings
}

class InMemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial.normalized())

    override val settings: Flow<AppSettings> = state

    override suspend fun getSettings(): AppSettings = state.value

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings =
        state.updateAndGet { transform(it).normalized() }
}

class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {
    override val settings: Flow<AppSettings> = dataStore.data.map(Preferences::toAppSettings)

    override suspend fun getSettings(): AppSettings = settings.first()

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings =
        dataStore.edit { preferences ->
            val updated = transform(preferences.toAppSettings()).normalized()
            preferences[readingDirectionKey] = updated.readingDirection.name
            preferences[defaultUpdateWindowKey] = updated.defaultUpdateWindow.name
            preferences[checkIntervalHoursKey] = updated.checkIntervalHours
            preferences[offlineOnlyModeKey] = updated.offlineOnlyMode
            preferences[prefetchCountKey] = updated.prefetchCount
            preferences[diskCacheSizeMbKey] = updated.diskCacheSizeMb
        }.toAppSettings()

    companion object {
        fun create(
            file: File,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStoreSettingsStore = DataStoreSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}

private val readingDirectionKey = stringPreferencesKey("reading_direction")
private val defaultUpdateWindowKey = stringPreferencesKey("default_update_window")
private val checkIntervalHoursKey = intPreferencesKey("check_interval_hours")
private val offlineOnlyModeKey = booleanPreferencesKey("offline_only_mode")
private val prefetchCountKey = intPreferencesKey("prefetch_count")
private val diskCacheSizeMbKey = intPreferencesKey("disk_cache_size_mb")

private fun Preferences.toAppSettings() = AppSettings(
    readingDirection = ReadingDirection.entries.firstOrNull { it.name == this[readingDirectionKey] }
        ?: ReadingDirection.RTL,
    defaultUpdateWindow = AndroidUpdateWindow.entries.firstOrNull { it.name == this[defaultUpdateWindowKey] }
        ?: AndroidUpdateWindow.DAYS7,
    checkIntervalHours = this[checkIntervalHoursKey] ?: AppSettings.DEFAULT_CHECK_INTERVAL_HOURS,
    offlineOnlyMode = this[offlineOnlyModeKey] ?: false,
    prefetchCount = this[prefetchCountKey] ?: AppSettings.DEFAULT_PREFETCH_COUNT,
    diskCacheSizeMb = this[diskCacheSizeMbKey] ?: AppSettings.DEFAULT_DISK_CACHE_SIZE_MB,
).normalized()
