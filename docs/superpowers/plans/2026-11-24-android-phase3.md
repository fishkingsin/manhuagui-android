# Manhuagui Android Phase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver Phase 3 of `manhuagui-android` for BOOX Leaf 5 / 5C: UniFFI offline chapter downloading (`files/downloaded/{comicId}/{chapterId}/` with `元数据.json` and `章节元数据.json`), startup filesystem-to-Room reconciliation, foreground download service with progress notification, CBZ/PDF export via SAF, DataStore settings (reading direction, default update window, check interval, offline-only mode, prefetch count, disk cache size), and offline-first reading.

**Architecture:** Extend `manhuagui-ffi` (`AndroidLibrary`) to wrap `library::downloader` and `library::export` behind UniFFI methods (`download_chapter` with throttled `AndroidDownloadObserver`, `cancel_download`, `delete_downloaded_chapter`, `scan_downloaded_chapters`, and `export_chapters`). Add a Room `downloaded_chapters` index in `LocalStore` reconciled against on-disk metadata files at startup, and a DataStore-backed `SettingsStore` (`InMemorySettingsStore` in JVM tests). Coordinate chapter downloads and foreground notifications through `OfflineDownloadManager` + `DownloadForegroundService`, stream CBZ/PDF exports from app cache to SAF `content://` URIs via `ArchiveExporter`, make `ReaderViewModel` prefer local downloaded pages and prompt before streaming in offline-only mode, and expose per-chapter download/export actions on `DetailScreen` plus a monochrome e-ink `SettingsScreen`.

**Tech Stack:** Rust (`manhuagui-ffi`, `library`, `source-api`, `uniffi` `0.28.3`), Kotlin `2.2.10`, Jetpack Compose Material 3 (`2025.10.00`), Room `2.7.2` + KSP `2.2.10-2.0.2`, WorkManager `2.10.5`, DataStore Preferences `1.1.7`, Coil `3.3.0`.

---

## File Structure

- `ffi/Cargo.toml`: add `eyre`, `parking_lot`, `serde_json`, `walkdir` dependencies for FFI download/export/scan glue.
- `ffi/src/types.rs`: add `AndroidDownloadObserver` callback interface, `DownloadedChapter` record, `AndroidExportFormat` enum, and `ExportedChapterFile` record.
- `ffi/src/library.rs`: implement `download_chapter` (throttled ~4Hz progress, temp-dir cleanup on error/cancel), `cancel_download`, `delete_downloaded_chapter`, `scan_downloaded_chapters`, `export_chapters`, and `read_local_chapter_navigation`.
- `ffi/tests/library_test.rs`: end-to-end Rust FFI tests for download, progress callbacks, cancellation, metadata scan, deletion, and CBZ/PDF export using a local TCP image server.
- `app/src/main/java/com/fishkingsin/manhuagui/core/LibraryService.kt`: suspend wrappers for `downloadChapter`, `cancelDownload`, `deleteDownloadedChapter`, `scanDownloadedChapters`, `exportChapters`, and `readLocalChapterNavigation`.
- `gradle/libs.versions.toml` & `app/build.gradle.kts`: add `androidx.datastore:datastore-preferences:1.1.7` and `androidx.documentfile:documentfile:1.1.0`.
- `app/src/main/java/com/fishkingsin/manhuagui/data/DownloadedChapterRecord.kt`: domain record for indexed offline chapters.
- `app/src/main/java/com/fishkingsin/manhuagui/data/AppDatabase.kt`: add `DownloadedChapterEntity`, `DownloadedChapterDao`, and Room version 2 migration (`MIGRATION_1_2`).
- `app/src/main/java/com/fishkingsin/manhuagui/data/LocalStore.kt` & `InMemoryLocalStore.kt`: add downloaded-chapter queries, mutations, and `reconcileDownloadedChapters`.
- `app/src/main/java/com/fishkingsin/manhuagui/settings/AppSettings.kt` & `SettingsStore.kt`: `ReadingDirection`, `AppSettings`, `SettingsStore`, `DataStoreSettingsStore`, and `InMemorySettingsStore`.
- `app/src/main/java/com/fishkingsin/manhuagui/downloads/OfflineDownloadManager.kt`: sequential chapter download queue, progress `StateFlow`, startup reconciliation, delete operations, and foreground-service lifecycle callback.
- `app/src/main/java/com/fishkingsin/manhuagui/downloads/DownloadForegroundService.kt`: Android `dataSync` foreground service with ongoing progress notification and swipe-away (`onTaskRemoved`) cancellation.
- `app/src/main/java/com/fishkingsin/manhuagui/downloads/ArchiveExporter.kt`: exports single or multiple chapters to cache via `LibraryService.exportChapters`, streams bytes into a target `OutputStream` or SAF directory provider, and deletes temporary files.
- `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/PagePrefetcher.kt`, `ReaderViewModel.kt`, `ReaderScreen.kt`: offline-first local file loading, offline-only mode streaming confirmation prompt, configurable reading direction (`RTL`/`LTR`), and configurable prefetch count (`0`/`2`/`3`/`5`).
- `app/src/main/java/com/fishkingsin/manhuagui/ui/detail/DetailViewModel.kt` & `DetailScreen.kt`: per-chapter download/cancel/delete/CBZ/PDF controls, comic-level `"Download Unread"`, `"Export CBZ"`, `"Export PDF"`, and `"Delete Downloads"` actions.
- `app/src/main/java/com/fishkingsin/manhuagui/ui/settings/SettingsViewModel.kt` & `SettingsScreen.kt`: e-ink settings controls for direction, default update window, check interval, offline-only mode, prefetch count, and disk cache size.
- `app/src/main/java/com/fishkingsin/manhuagui/ui/latest/LatestViewModel.kt`, `LatestScreen.kt`, `BookmarksScreen.kt`, `AppNav.kt`, `App.kt`, `MainActivity.kt`, `BookmarkUpdateWorker.kt`: wire default update window, configurable periodic check interval, startup download reconciliation + on-open bookmark check, and 3-tab (`Browse` / `Bookmarks` / `Settings`) top bar.
- `README.md` & `docs/device-checklist.md`: document Phase 3 architecture, offline storage layout, SAF export, and Settings.

---

### Task 1: UniFFI Offline Downloader, Metadata Scanner, and CBZ/PDF Exporter

**Files:**
- Modify: `ffi/Cargo.toml`
- Modify: `ffi/src/lib.rs`
- Modify: `ffi/src/types.rs`
- Modify: `ffi/src/library.rs`
- Modify: `ffi/tests/library_test.rs`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/core/LibraryService.kt`
- Modify: `app/src/test/java/com/fishkingsin/manhuagui/bookmarks/BookmarkUpdateCheckerTest.kt`
- Modify: `app/src/test/java/com/fishkingsin/manhuagui/ui/detail/DetailViewModelTest.kt`
- Modify: `app/src/test/java/com/fishkingsin/manhuagui/ui/latest/LatestViewModelTest.kt`
- Modify: `app/src/test/java/com/fishkingsin/manhuagui/ui/reader/ReaderViewModelTest.kt`
- Modify: `app/src/test/java/com/fishkingsin/manhuagui/ui/search/SearchViewModelTest.kt`

- [ ] **Step 1: Write failing Rust tests in `ffi/tests/library_test.rs`**
  - Add a local `tokio::net::TcpListener` HTTP server helper serving a valid 1x1 JPEG image and a `FakeSource` returning chapter pages pointing to that server.
  - Test `download_chapter` creates `<download_root>/{comic_id}/元数据.json` and `<download_root>/{comic_id}/{chapter_id}/章节元数据.json` plus `001.jpg`, `002.jpg`, invokes `AndroidDownloadObserver::on_progress`, and returns `DownloadedChapter`.
  - Test `scan_downloaded_chapters` removes leftover `.下载中-*` directories and returns completed chapters from disk.
  - Test `read_local_chapter_navigation` computes `prev_chapter_id` and `next_chapter_id` from `<download_root>/{comic_id}/元数据.json` using the same group ordering as online reading.
  - Test `export_chapters` exports a downloaded chapter to `.cbz` and `.pdf` in `export_root`.
  - Test `delete_downloaded_chapter` removes the chapter directory and removes the comic directory once the last chapter is deleted.
  - Test `cancel_download` cancels an in-flight download, returns `AndroidError::Cancelled`, and removes the `.下载中-*` temporary directory.

- [ ] **Step 2: Implement the UniFFI types and methods in `ffi/src/types.rs`, `ffi/src/library.rs`, and `ffi/src/lib.rs`**
  - Define:
    - `#[uniffi::export(callback_interface)] pub trait AndroidDownloadObserver: Send + Sync { fn on_progress(&self, comic_id: i64, chapter_id: i64, downloaded_pages: u32, total_pages: u32); }`
    - `DownloadedChapter { comic_id: i64, comic_title: String, chapter_id: i64, chapter_title: String, group_name: String, order: f64, page_count: i32, chapter_dir: String }`
    - `LocalChapterNavigation { prev_chapter_id: Option<i64>, next_chapter_id: Option<i64> }`
    - `AndroidExportFormat { Cbz, Pdf }`
    - `ExportedChapterFile { chapter_id: i64, file_name: String, file_path: String }`
  - In `AndroidLibrary`:
    - Configure download `CoreConfig` with `comic_dir_fmt = "{comic_id}"`, `chapter_dir_fmt = "{chapter_id}"`, `chapter_download_interval_sec = 0`, `img_download_interval_sec = 0`, `api_timeout_sec = 15`, `img_timeout_sec = 30`.
    - Implement `download_chapter`, `cancel_download`, `delete_downloaded_chapter`, `scan_downloaded_chapters`, `read_local_chapter_navigation`, and `export_chapters`.
  - Extend `LibraryService.kt` with suspend wrappers for the new `AndroidLibraryInterface` methods and update `FakeLibrary` implementations in existing JVM tests so `testDebugUnitTest` compiles and passes.

- [ ] **Step 3: Run Rust and Android verification**
  - Run `cargo test --manifest-path ffi/Cargo.toml`
  - Run `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.8-zulu" ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest`

---

### Task 2: Room Downloaded-Chapters Index and DataStore `AppSettings`

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/fishkingsin/manhuagui/data/DownloadedChapterRecord.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/data/LocalStore.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/data/InMemoryLocalStore.kt`
- Create: `app/src/main/java/com/fishkingsin/manhuagui/settings/AppSettings.kt`
- Create: `app/src/main/java/com/fishkingsin/manhuagui/settings/SettingsStore.kt`
- Modify: `app/src/test/java/com/fishkingsin/manhuagui/data/LocalStoreTest.kt`
- Create: `app/src/test/java/com/fishkingsin/manhuagui/settings/SettingsStoreTest.kt`

- [ ] **Step 1: Write failing unit tests in `LocalStoreTest.kt` and `SettingsStoreTest.kt`**
  - In `LocalStoreTest.kt`: test `upsertDownloadedChapter`, `getDownloadedChapter`, `getDownloadedChapters`, `observeDownloadedChapters` (sorted by `groupName` asc, `order` desc, `chapterId` asc), `deleteDownloadedChapter`, and `reconcileDownloadedChapters` (replaces records for the source atomically).
  - In `SettingsStoreTest.kt`: test `AppSettings` defaults (`ReadingDirection.RTL`, `AndroidUpdateWindow.DAYS7`, `checkIntervalHours = 6`, `offlineOnlyMode = false`, `prefetchCount = 3`, `diskCacheSizeMb = 512`), coercion/validation of allowed values (`checkIntervalHours` in `{1, 6, 12, 24}`, `prefetchCount` in `{0, 2, 3, 5}`, `diskCacheSizeMb` in `{256, 512, 1024}`), and `InMemorySettingsStore` + `DataStoreSettingsStore` persistence round-trip.

- [ ] **Step 2: Implement Room `DownloadedChapterEntity` (`MIGRATION_1_2`), `LocalStore` extensions, and `SettingsStore`**
  - Add `androidx.datastore:datastore-preferences:1.1.7` and `androidx.documentfile:documentfile:1.1.0` to `gradle/libs.versions.toml` and `app/build.gradle.kts`.
  - Create `DownloadedChapterRecord.kt` and add `DownloadedChapterEntity` (`primaryKeys = ["sourceId", "comicId", "chapterId"]`) + `DownloadedChapterDao` + `MIGRATION_1_2` to `AppDatabase.kt` (version = 2).
  - Implement the downloaded-chapter methods on `LocalStore`, `RoomLocalStore`, and `InMemoryLocalStore`.
  - Create `AppSettings.kt` (`ReadingDirection`, `AppSettings` with normalized/validated fields) and `SettingsStore.kt` (`SettingsStore`, `DataStoreSettingsStore`, `InMemorySettingsStore`).

- [ ] **Step 3: Run unit tests**
  - Run `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.8-zulu" ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest`

---

### Task 3: `OfflineDownloadManager`, `DownloadForegroundService`, and `ArchiveExporter`

**Files:**
- Create: `app/src/main/java/com/fishkingsin/manhuagui/downloads/OfflineDownloadManager.kt`
- Create: `app/src/main/java/com/fishkingsin/manhuagui/downloads/DownloadForegroundService.kt`
- Create: `app/src/main/java/com/fishkingsin/manhuagui/downloads/ArchiveExporter.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/java/com/fishkingsin/manhuagui/downloads/OfflineDownloadManagerTest.kt`
- Create: `app/src/test/java/com/fishkingsin/manhuagui/downloads/ArchiveExporterTest.kt`

- [ ] **Step 1: Write failing unit tests in `OfflineDownloadManagerTest.kt` and `ArchiveExporterTest.kt`**
  - In `OfflineDownloadManagerTest.kt`:
    - Test `reconcileOnStartup()` scans disk via `LibraryService.scanDownloadedChapters` and populates `LocalStore.reconcileDownloadedChapters`.
    - Test `enqueueChapters` downloads queued chapters sequentially, emits `QUEUED` -> `DOWNLOADING` (with page progress from `AndroidDownloadObserver`) -> `COMPLETED`, persists each `DownloadedChapterRecord` in `LocalStore`, and invokes the foreground service controller on queue start and drain.
    - Test `cancelChapter` cancels a queued or active chapter and removes it from active progress.
    - Test `cancelAllActive()` (invoked when the app is swiped away in `DownloadForegroundService.onTaskRemoved`) cancels active and queued chapters and stops the foreground service.
    - Test `deleteChapter` and `deleteComicDownloads` remove chapters on disk via `LibraryService.deleteDownloadedChapter` and remove them from `LocalStore`.
  - In `ArchiveExporterTest.kt`:
    - Test `exportChapterToStream` calls `LibraryService.exportChapters`, copies the exported `.cbz` / `.pdf` bytes to the target `OutputStream`, and deletes the temporary export directory afterward (even when the stream throws).
    - Test `exportComicToDirectory` exports all downloaded chapters of a comic and copies each file into the provided directory stream opener, cleaning up the temporary export directory afterward.

- [ ] **Step 2: Implement `OfflineDownloadManager`, `DownloadForegroundService`, and `ArchiveExporter`**
  - Declare `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` permissions and `<service android:name=".downloads.DownloadForegroundService" android:exported="false" android:foregroundServiceType="dataSync" />` in `AndroidManifest.xml`.
  - Implement `OfflineDownloadManager`, `DownloadForegroundService`, and `ArchiveExporter`.

- [ ] **Step 3: Run unit tests**
  - Run `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.8-zulu" ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest`

---

### Task 4: Offline-First Reader, Offline-Only Streaming Prompt, and Settings-Driven Reader Behavior

**Files:**
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/PagePrefetcher.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderScreen.kt`
- Modify: `app/src/test/java/com/fishkingsin/manhuagui/ui/reader/PagePrefetcherTest.kt`
- Modify: `app/src/test/java/com/fishkingsin/manhuagui/ui/reader/ReaderViewModelTest.kt`

- [ ] **Step 1: Write failing unit tests in `PagePrefetcherTest.kt` and `ReaderViewModelTest.kt`**
  - In `PagePrefetcherTest.kt`:
    - Test `prefetchIndices` respects custom `aheadCount` (`0`, `2`, `3`, `5`).
    - Test `PagePrefetcher.onPageTurn` skips network prefetching for local `file:` URLs while still evicting distant pages from the memory cache.
  - In `ReaderViewModelTest.kt`:
    - Test `load()` prefers local `.jpg` files in `DownloadedChapterRecord.chapterDir` when the chapter is downloaded, without calling `service.getChapterPages`, and populates `prevChapterId` / `nextChapterId` via `service.readLocalChapterNavigation`.
    - Test `load()` falls back to streaming when the local directory has no `.jpg` files.
    - Test `load()` with `offlineOnlyMode = true` and an undownloaded chapter pauses with `awaitingOfflineStreamConfirmation = true` without calling `service.getChapterPages`, and `confirmStreaming()` then loads the chapter from `service.getChapterPages`.

- [ ] **Step 2: Implement offline-first loading, offline-only confirmation, and configurable direction/prefetch in `PagePrefetcher`, `ReaderViewModel`, and `ReaderScreen`**
  - Update `PagePrefetcher.onPageTurn` to accept `aheadCount: Int = DEFAULT_AHEAD_COUNT` and skip prefetching `file:` URLs.
  - Inject `settingsStore: SettingsStore = InMemorySettingsStore()` into `ReaderViewModel` and `ReaderScreen`.
  - Add `awaitingOfflineStreamConfirmation: Boolean = false`, `isOfflineChapter: Boolean = false`, `readingDirection: ReadingDirection = ReadingDirection.RTL`, and `prefetchCount: Int = 3` to `ReaderUiState`, plus `fun confirmStreaming()` on `ReaderViewModel`.
  - Render the e-ink offline-only confirmation prompt (`"Offline-only mode is on. This chapter is not downloaded."` with `"Stream Anyway"` and `"Back"` buttons) in `ReaderScreen`, apply `LayoutDirection.Rtl` or `LayoutDirection.Ltr` from `uiState.readingDirection`, and pass `rtl = uiState.readingDirection == ReadingDirection.RTL` to `handleReaderKey`.

- [ ] **Step 3: Run unit tests**
  - Run `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.8-zulu" ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest`

---

### Task 5: Detail Download/Export UI, Settings Screen, App-Open Checks, and Phase 3 Docs

**Files:**
- Create: `app/src/main/java/com/fishkingsin/manhuagui/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/com/fishkingsin/manhuagui/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/detail/DetailViewModel.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/detail/DetailScreen.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/latest/LatestViewModel.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/latest/LatestScreen.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/bookmarks/BookmarksScreen.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/AppNav.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/bookmarks/BookmarkUpdateWorker.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/App.kt`
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/MainActivity.kt`
- Create: `app/src/test/java/com/fishkingsin/manhuagui/ui/settings/SettingsViewModelTest.kt`
- Modify: `app/src/test/java/com/fishkingsin/manhuagui/ui/detail/DetailViewModelTest.kt`
- Modify: `app/src/test/java/com/fishkingsin/manhuagui/ui/latest/LatestViewModelTest.kt`
- Modify: `README.md`
- Modify: `docs/device-checklist.md`

- [ ] **Step 1: Write failing unit tests in `SettingsViewModelTest.kt`, `DetailViewModelTest.kt`, and `LatestViewModelTest.kt`**
  - In `SettingsViewModelTest.kt`: test updating reading direction, default update window, check interval hours (and invoking the `onIntervalChanged` callback), offline-only mode, prefetch count, and disk cache size MB.
  - In `DetailViewModelTest.kt`: test observing downloaded chapters and active download progress, `downloadChapter`, `downloadUnreadChapters` (all chapters when no progress exists; chapters newer than `readingProgress.chapterId` when progress exists), `cancelDownload`, `deleteDownloadedChapter`, `deleteAllDownloads`, and single-chapter / comic-level export requests + status messages.
  - In `LatestViewModelTest.kt`: test initializing the selected update window from `SettingsStore` on first load.

- [ ] **Step 2: Implement `SettingsViewModel`, `SettingsScreen`, `DetailViewModel`/`DetailScreen` download & export controls, 3-tab navigation, and startup hooks**
  - Create `SettingsViewModel.kt` and monochrome e-ink `SettingsScreen.kt`.
  - Extend `DetailViewModel.kt` and `DetailScreen.kt` with per-chapter download/cancel/delete/CBZ/PDF actions, comic-level `"Download Unread"`, `"Export CBZ"`, `"Export PDF"`, and `"Delete Downloads"` actions, and SAF `CreateDocument` / `OpenDocumentTree` launchers.
  - Update `LatestViewModel.kt`, `LatestScreen.kt`, `BookmarksScreen.kt`, and `AppNav.kt` with the 3-tab top bar (`Browse` / `Bookmarks` / `Settings`) and wire `SettingsStore`, `OfflineDownloadManager`, and `ArchiveExporter` from `AppContainer` (`App.kt` / `MainActivity.kt`).
  - Update `BookmarkUpdateWorker.schedulePeriodic(context, intervalHours, policy)` and trigger startup `downloadManager.reconcileOnStartup()` + on-app-open bookmark check in `App.onCreate()`.
  - Update `README.md` and `docs/device-checklist.md` for Phase 3.

- [ ] **Step 3: Run full verification**
  - Run `cargo test --manifest-path ffi/Cargo.toml`
  - Run `JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.8-zulu" ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest assembleDebug`
