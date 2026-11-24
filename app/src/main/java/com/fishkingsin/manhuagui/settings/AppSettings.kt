package com.fishkingsin.manhuagui.settings

import uniffi.manhuagui_ffi.AndroidUpdateWindow

enum class ReadingDirection {
    RTL,
    LTR,
}

data class AppSettings(
    val readingDirection: ReadingDirection = ReadingDirection.RTL,
    val defaultUpdateWindow: AndroidUpdateWindow = AndroidUpdateWindow.DAYS7,
    val checkIntervalHours: Int = DEFAULT_CHECK_INTERVAL_HOURS,
    val offlineOnlyMode: Boolean = false,
    val prefetchCount: Int = DEFAULT_PREFETCH_COUNT,
    val diskCacheSizeMb: Int = DEFAULT_DISK_CACHE_SIZE_MB,
) {
    fun normalized(): AppSettings = copy(
        checkIntervalHours = if (checkIntervalHours in ALLOWED_CHECK_INTERVAL_HOURS) {
            checkIntervalHours
        } else {
            DEFAULT_CHECK_INTERVAL_HOURS
        },
        prefetchCount = if (prefetchCount in ALLOWED_PREFETCH_COUNTS) {
            prefetchCount
        } else {
            DEFAULT_PREFETCH_COUNT
        },
        diskCacheSizeMb = if (diskCacheSizeMb in ALLOWED_DISK_CACHE_SIZES_MB) {
            diskCacheSizeMb
        } else {
            DEFAULT_DISK_CACHE_SIZE_MB
        },
    )

    companion object {
        const val DEFAULT_CHECK_INTERVAL_HOURS = 6
        const val DEFAULT_PREFETCH_COUNT = 3
        const val DEFAULT_DISK_CACHE_SIZE_MB = 512
        val ALLOWED_CHECK_INTERVAL_HOURS = listOf(1, 6, 12, 24)
        val ALLOWED_PREFETCH_COUNTS = listOf(0, 2, 3, 5)
        val ALLOWED_DISK_CACHE_SIZES_MB = listOf(256, 512, 1024)
    }
}
