package com.fishkingsin.manhuagui.settings

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.manhuagui_ffi.AndroidUpdateWindow

class SettingsStoreTest {
    @Test
    fun appSettingsUseTheDocumentedDefaults() {
        assertEquals(ReadingDirection.RTL, AppSettings().readingDirection)
        assertEquals(AndroidUpdateWindow.DAYS7, AppSettings().defaultUpdateWindow)
        assertEquals(6, AppSettings().checkIntervalHours)
        assertEquals(false, AppSettings().offlineOnlyMode)
        assertEquals(3, AppSettings().prefetchCount)
        assertEquals(512, AppSettings().diskCacheSizeMb)
    }

    @Test
    fun normalizedSettingsKeepAllowedValuesAndReplaceInvalidValues() {
        AppSettings.ALLOWED_CHECK_INTERVAL_HOURS.forEach { value ->
            assertEquals(value, AppSettings(checkIntervalHours = value).normalized().checkIntervalHours)
        }
        AppSettings.ALLOWED_PREFETCH_COUNTS.forEach { value ->
            assertEquals(value, AppSettings(prefetchCount = value).normalized().prefetchCount)
        }
        AppSettings.ALLOWED_DISK_CACHE_SIZES_MB.forEach { value ->
            assertEquals(value, AppSettings(diskCacheSizeMb = value).normalized().diskCacheSizeMb)
        }

        val normalized = AppSettings(
            checkIntervalHours = 2,
            prefetchCount = 4,
            diskCacheSizeMb = 768,
        ).normalized()

        assertEquals(6, normalized.checkIntervalHours)
        assertEquals(3, normalized.prefetchCount)
        assertEquals(512, normalized.diskCacheSizeMb)
    }

    @Test
    fun inMemorySettingsNormalizeInitialAndUpdatedValues() = runTest {
        val store = InMemorySettingsStore(
            AppSettings(checkIntervalHours = 2, prefetchCount = 4, diskCacheSizeMb = 768),
        )

        assertEquals(AppSettings(), store.settings.first())

        val updated = store.updateSettings {
            it.copy(
                readingDirection = ReadingDirection.LTR,
                defaultUpdateWindow = AndroidUpdateWindow.DAYS30,
                offlineOnlyMode = true,
                prefetchCount = 4,
            )
        }
        val expected = AppSettings(
            readingDirection = ReadingDirection.LTR,
            defaultUpdateWindow = AndroidUpdateWindow.DAYS30,
            offlineOnlyMode = true,
            prefetchCount = 3,
        )

        assertEquals(expected, updated)
        assertEquals(expected, store.settings.first())
    }

    @Test
    fun dataStoreSettingsPersistAcrossStoreInstances() = runTest {
        val directory = Files.createTempDirectory("settings-store").toFile()
        val file = directory.resolve("app.preferences_pb")
        val firstJob = SupervisorJob()
        val secondJob = SupervisorJob()
        val expected = AppSettings(
            readingDirection = ReadingDirection.LTR,
            defaultUpdateWindow = AndroidUpdateWindow.DAYS30,
            checkIntervalHours = 24,
            offlineOnlyMode = true,
            prefetchCount = 0,
            diskCacheSizeMb = 1024,
        )

        try {
            val firstStore = DataStoreSettingsStore.create(
                file = file,
                scope = CoroutineScope(Dispatchers.IO + firstJob),
            )
            assertEquals(expected, firstStore.updateSettings { expected })
            firstJob.cancelAndJoin()

            val secondStore = DataStoreSettingsStore.create(
                file = file,
                scope = CoroutineScope(Dispatchers.IO + secondJob),
            )
            assertEquals(expected, secondStore.getSettings())
            assertEquals(expected, secondStore.settings.first())
        } finally {
            firstJob.cancelAndJoin()
            secondJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
