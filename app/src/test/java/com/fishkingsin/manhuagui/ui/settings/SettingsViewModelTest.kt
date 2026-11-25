package com.fishkingsin.manhuagui.ui.settings

import com.fishkingsin.manhuagui.settings.AppSettings
import com.fishkingsin.manhuagui.settings.InMemorySettingsStore
import com.fishkingsin.manhuagui.settings.ReadingDirection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.manhuagui_ffi.AndroidUpdateWindow

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun settersPersistEverySettingThroughTheStore() = runTest {
        val store = InMemorySettingsStore()
        val intervals = mutableListOf<Int>()
        val viewModel = SettingsViewModel(store, { intervals.add(it) }, backgroundScope)

        viewModel.setReadingDirection(ReadingDirection.LTR)
        viewModel.setDefaultUpdateWindow(AndroidUpdateWindow.ALL)
        viewModel.setCheckIntervalHours(24)
        viewModel.setOfflineOnlyMode(true)
        viewModel.setPrefetchCount(5)
        viewModel.setDiskCacheSizeMb(1024)
        runCurrent()

        val expected = AppSettings(
            readingDirection = ReadingDirection.LTR,
            defaultUpdateWindow = AndroidUpdateWindow.ALL,
            checkIntervalHours = 24,
            offlineOnlyMode = true,
            prefetchCount = 5,
            diskCacheSizeMb = 1024,
        )
        assertEquals(expected, store.getSettings())
        assertEquals(expected, viewModel.settings.value)
        assertEquals(listOf(24), intervals)
    }

    @Test
    fun numericSettersNormalizeUnsupportedValuesAndNotifyWithThePersistedInterval() = runTest {
        val store = InMemorySettingsStore()
        val intervals = mutableListOf<Int>()
        val viewModel = SettingsViewModel(store, { intervals.add(it) }, backgroundScope)

        viewModel.setCheckIntervalHours(0)
        viewModel.setPrefetchCount(1)
        viewModel.setDiskCacheSizeMb(1000)
        runCurrent()

        assertEquals(AppSettings(), store.getSettings())
        assertEquals(listOf(AppSettings.DEFAULT_CHECK_INTERVAL_HOURS), intervals)
    }
}
