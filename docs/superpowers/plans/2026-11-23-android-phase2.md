# manhuagui-android Phase 2 Implementation Plan (Reader Keys, Prefetch, Progress Resume, Bookmarks, and Update Notifications)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the e-ink Android manga reader with physical page-turn button support, direction-aware page prefetching and bitmap memory eviction, Room-backed reading-progress resume and local bookmarks, and WorkManager periodic chapter-update checks with summary notifications.

**Architecture:** Room 2.7.2 (with KSP 2.2.10-2.0.2) persists bookmarks and reading progress keyed by `(sourceId, comicId)` behind a Kotlin `LocalStore` interface with both a Room implementation and an in-memory test implementation. The reader handles physical key events (`PAGE_UP`/`PAGE_DOWN`, `VOLUME_UP`/`VOLUME_DOWN`, `DPAD_LEFT`/`DPAD_RIGHT`, and `BUTTON_L1`/`BUTTON_R1`), prefetches the next 3 pages in reading direction, evicts decoded bitmaps more than 2 pages away from Coil's `MemoryCache`, and saves/resumes `(comicId, chapterId, pageIndex)` on every page turn. A pure `BookmarkUpdateChecker` checks bookmarked comics sequentially with 1 to 2 second jitter, updates Room, and drives both manual checks on the Bookmarks screen and 6-hour background checks via WorkManager 2.10.5 and Android notifications.

**Tech Stack:** AGP 8.13.2, Gradle 8.13, Kotlin 2.2.10, KSP 2.2.10-2.0.2, Room 2.7.2 (`room-runtime`, `room-ktx`, `room-compiler`), WorkManager 2.10.5 (`work-runtime-ktx`), Compose Material3, Navigation-Compose 2.9.5, Coil 3.3.0, UniFFI 0.28.3 (`v0.1.1-core`).

**Spec:** `docs/superpowers/specs/2026-11-22-manhuagui-android-ereader-design.md` (§Goals 4-5, §Constraints, §Architecture, §Phasing phase 2)

## Global Constraints

- Keep `minSdk 29` and `targetSdk/compileSdk 35`. Pin Room to `2.7.2`, WorkManager to `2.10.5`, and KSP to `2.2.10-2.0.2`.
- Every Room content table is keyed by `(sourceId: String, comicId: Long)` with default `sourceId = "manhuagui"`.
- E-ink style is law: pure black-on-white in light mode and white-on-black in dark mode via `MaterialTheme.colorScheme`, no ripples (`NoOpIndication`), no `NavHost` transitions, no crossfades, no elevation shadows, 1dp borders, system font, and 48dp minimum tap targets.
- Physical page-turn keys (`KEYCODE_PAGE_UP`, `KEYCODE_PAGE_DOWN`, `KEYCODE_VOLUME_UP`, `KEYCODE_VOLUME_DOWN`, `KEYCODE_DPAD_LEFT`, `KEYCODE_DPAD_RIGHT`, `KEYCODE_BUTTON_L1`, `KEYCODE_BUTTON_R1`) consume both `ACTION_DOWN` and `ACTION_UP` so system volume UI never appears while reading, and advance or return one page only on `ACTION_DOWN`.
- Prefetch the next 3 pages in reading direction at full source resolution (`Size.ORIGINAL` with `PageImages.modelFor(page)`) and evict decoded pages more than 2 indices away (`abs(index - currentPage) > 2`) from Coil's `MemoryCache`.
- Bookmark update checks must fetch comic details sequentially with a 1000 to 2000ms delay between comics, never in a concurrent burst.
- Every task ends with `./gradlew testDebugUnitTest assembleDebug` green.

## Review Focus

1. **Physical key repeat / double page turn on key release** — pressing and releasing a volume or page-turn button dispatches both `ACTION_DOWN` and `ACTION_UP`. `ACTION_DOWN` turns one page and consumes the event; `ACTION_UP` is consumed without turning a second page. Test: `ReaderKeyHandlerTest` asserts `ACTION_DOWN` returns `Consumed(delta = 1)` and `ACTION_UP` returns `Consumed(delta = null)`.
2. **RAM pressure on 200-page chapters** — turning from page 0 to page 5 prefetches pages 6..8 and evicts pages 0..2 (`abs(index - 5) > 2`) from `MemoryCache`. Test: `PagePrefetcherTest` verifies prefetched URLs and evicted `MemoryCache.Key`s across forward and backward turns.
3. **Stale or out-of-range saved page index on resume** — if saved reading progress points to `chapterId` with `pageIndex = 40` on a 20-page chapter, or points to a different `chapterId`, opening a chapter clamps or starts at page 0 rather than crashing `HorizontalPager`. Test: `ReaderViewModelTest` resume test with matching chapter, non-matching chapter, and out-of-range page index.
4. **Bookmark update burst and baseline seeding** — starring a comic seeds `lastKnownChapterIds` with all current chapter IDs so the next update check reports 0 new chapters; when 2 bookmarks are checked, the checker awaits a 1000..2000ms delay between them and continues checking remaining bookmarks even if one comic fetch fails. Test: `BookmarkUpdateCheckerTest` with injected delay recorder and partial failure.
5. **Duplicate manual update check while checking** — tapping `"Check updates"` while a check is already running is a no-op. Test: `BookmarksViewModelTest` asserts a second `checkUpdatesNow()` during an in-flight check does not invoke the checker twice.

---

### Task 1: Room database, entities, DAOs, and `LocalStore` repository

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/java/com/fishkingsin/manhuagui/App.kt`
- Create: `app/src/main/java/com/fishkingsin/manhuagui/data/ReadingProgress.kt`, `app/src/main/java/com/fishkingsin/manhuagui/data/BookmarkRecord.kt`, `app/src/main/java/com/fishkingsin/manhuagui/data/AppDatabase.kt`, `app/src/main/java/com/fishkingsin/manhuagui/data/LocalStore.kt`, `app/src/main/java/com/fishkingsin/manhuagui/data/InMemoryLocalStore.kt`
- Test: `app/src/test/java/com/fishkingsin/manhuagui/data/LocalStoreTest.kt`

**Interfaces:**
- Produces:
  - `const val DEFAULT_SOURCE_ID = "manhuagui"`
  - `data class ReadingProgress(val sourceId: String = DEFAULT_SOURCE_ID, val comicId: Long, val chapterId: Long, val chapterTitle: String, val pageIndex: Int, val updatedAtEpochMs: Long)`
  - `data class BookmarkRecord(val sourceId: String = DEFAULT_SOURCE_ID, val comicId: Long, val title: String, val coverUrl: String, val lastKnownChapterIds: Set<Long>, val latestChapterTitle: String, val newChapterCount: Int = 0, val hasUnreadUpdate: Boolean = false, val lastCheckedEpochMs: Long = 0L, val updatedAtEpochMs: Long = 0L)`
  - `interface LocalStore`:
    - `fun observeBookmarks(sourceId: String = DEFAULT_SOURCE_ID): Flow<List<BookmarkRecord>>` (ordered by `hasUnreadUpdate` descending, then `updatedAtEpochMs` descending, then `comicId` ascending)
    - `suspend fun getBookmarks(sourceId: String = DEFAULT_SOURCE_ID): List<BookmarkRecord>`
    - `suspend fun getBookmark(comicId: Long, sourceId: String = DEFAULT_SOURCE_ID): BookmarkRecord?`
    - `suspend fun upsertBookmark(record: BookmarkRecord)`
    - `suspend fun deleteBookmark(comicId: Long, sourceId: String = DEFAULT_SOURCE_ID)`
    - `suspend fun getReadingProgress(comicId: Long, sourceId: String = DEFAULT_SOURCE_ID): ReadingProgress?`
    - `suspend fun saveReadingProgress(progress: ReadingProgress)`
  - `class RoomLocalStore(database: AppDatabase)` and `class InMemoryLocalStore : LocalStore`
  - `AppContainer.localStore: LocalStore` backed by `RoomLocalStore(AppDatabase.build(context))`
  - `ChapterIdsCodec`: `fun encode(ids: Set<Long>): String` and `fun decode(raw: String): Set<Long>` for storing `lastKnownChapterIds` in Room (`BookmarkEntity`).

- [ ] **Step 1: Add Room, KSP, and WorkManager dependencies** to `gradle/libs.versions.toml`, `build.gradle.kts`, and `app/build.gradle.kts` (`room = "2.7.2"`, `work = "2.10.5"`, `ksp = "2.2.10-2.0.2"`).
- [ ] **Step 2: Write failing unit tests** in `LocalStoreTest.kt` testing `ChapterIdsCodec` round-trips (empty set, unsorted set, whitespace/malformed tokens ignored), `InMemoryLocalStore` bookmark ordering (`hasUnreadUpdate` first, then `updatedAtEpochMs` descending), bookmark upsert/delete, and `ReadingProgress` save/overwrite per `(sourceId, comicId)`.
- [ ] **Step 3: Run test to verify RED**: `./gradlew testDebugUnitTest --tests com.fishkingsin.manhuagui.data.LocalStoreTest`.
- [ ] **Step 4: Implement** `ReadingProgress.kt`, `BookmarkRecord.kt`, `AppDatabase.kt` (`@Database(entities = [BookmarkEntity::class, ReadingProgressEntity::class], version = 1, exportSchema = false)`), `LocalStore.kt` (`RoomLocalStore`), `InMemoryLocalStore.kt`, and wire `localStore` in `App.kt`.
- [ ] **Step 5: Verify GREEN**: `./gradlew testDebugUnitTest assembleDebug` passes.
- [ ] **Step 6: Commit**: `feat(data): add Room database and LocalStore for bookmarks and progress`.

### Task 2: Reader physical page-turn keys, page prefetch, and bitmap eviction

**Files:**
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderState.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderViewModel.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderScreen.kt`
- Create: `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderKeyHandler.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/PagePrefetcher.kt`
- Test: `app/src/test/java/com/fishkingsin/manhuagui/ui/reader/ReaderKeyHandlerTest.kt`, `app/src/test/java/com/fishkingsin/manhuagui/ui/reader/PagePrefetcherTest.kt`

**Interfaces:**
- Consumes: `PageImages`, `ImagePage`
- Produces:
  - `data class ReaderKeyResult(val consumed: Boolean, val pageDelta: Int?)`
  - `fun handleReaderKey(keyCode: Int, action: Int, rtl: Boolean = true): ReaderKeyResult`:
    - Mapped next-page keys (`pageDelta = +1` on `KeyEvent.ACTION_DOWN`, `pageDelta = null` on `KeyEvent.ACTION_UP`, `consumed = true` on both): `KeyEvent.KEYCODE_PAGE_DOWN` (93), `KeyEvent.KEYCODE_VOLUME_DOWN` (25), `KeyEvent.KEYCODE_BUTTON_R1` (103), and `KeyEvent.KEYCODE_DPAD_LEFT` (21) when `rtl` is true (`KEYCODE_DPAD_RIGHT` (22) when `rtl` is false).
    - Mapped prev-page keys (`pageDelta = -1` on `KeyEvent.ACTION_DOWN`, `pageDelta = null` on `KeyEvent.ACTION_UP`, `consumed = true` on both): `KeyEvent.KEYCODE_PAGE_UP` (92), `KeyEvent.KEYCODE_VOLUME_UP` (24), `KeyEvent.KEYCODE_BUTTON_L1` (102), and `KeyEvent.KEYCODE_DPAD_RIGHT` (22) when `rtl` is true (`KEYEvent.KEYCODE_DPAD_LEFT` (21) when `rtl` is false).
    - All other keycodes return `ReaderKeyResult(consumed = false, pageDelta = null)`.
  - `fun prefetchIndices(currentPage: Int, totalPages: Int, directionDelta: Int = 1, count: Int = 3): List<Int>`
  - `fun evictIndices(currentPage: Int, totalPages: Int, keepRadius: Int = 2): List<Int>`
  - `class PagePrefetcher(private val enqueuePage: (ImagePage) -> Unit, private val evictUrl: (String) -> Unit)`:
    - `fun onPageTurn(pages: List<ImagePage>, currentPage: Int, directionDelta: Int)`: calls `enqueuePage(pages[i])` for `i in prefetchIndices(currentPage, pages.size, directionDelta, 3)` and calls `evictUrl(pages[i].url)` for `i in evictIndices(currentPage, pages.size, 2)`.
    - Companion factory `fun create(context: Context, imageLoader: ImageLoader): PagePrefetcher` that enqueues an `ImageRequest` with `Size.ORIGINAL` and `PageImages.modelFor(page)` and removes `MemoryCache.Key(url)` from `imageLoader.memoryCache`.

- [ ] **Step 1: Write failing unit tests** in `ReaderKeyHandlerTest.kt` (Review Focus 1: `ACTION_DOWN` turns page, `ACTION_UP` is consumed with `pageDelta = null`, RTL vs LTR DPAD mirroring, unmapped keys not consumed) and `PagePrefetcherTest.kt` (Review Focus 2: forward and backward prefetch windows, boundary clamping at start/end of chapter, and eviction of pages with `abs(i - currentPage) > 2`).
- [ ] **Step 2: Run tests to verify RED**: `./gradlew testDebugUnitTest --tests "com.fishkingsin.manhuagui.ui.reader.ReaderKeyHandlerTest" --tests "com.fishkingsin.manhuagui.ui.reader.PagePrefetcherTest"`.
- [ ] **Step 3: Implement** `ReaderKeyHandler.kt` and `PagePrefetcher.kt`, track `lastTurnDirection: Int = 1` in `ReaderUiState` (`1` when advancing, `-1` when going back), and wire `handleReaderKey` (preserving `Log.i("ReaderKey", ...)` before returning `result.consumed`) and `PagePrefetcher.onPageTurn` in `ReaderScreen.kt`.
- [ ] **Step 4: Verify GREEN**: `./gradlew testDebugUnitTest assembleDebug` passes.
- [ ] **Step 5: Commit**: `feat(reader): add physical page-turn keys, page prefetch, and bitmap eviction`.

### Task 3: Reading progress persistence & resume + Detail bookmark toggle

**Files:**
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderViewModel.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderScreen.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/detail/DetailViewModel.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/detail/DetailScreen.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/AppNav.kt`, `app/src/main/java/com/fishkingsin/manhuagui/MainActivity.kt`, `app/src/test/java/com/fishkingsin/manhuagui/ui/reader/ReaderViewModelTest.kt`, `app/src/test/java/com/fishkingsin/manhuagui/ui/detail/DetailViewModelTest.kt`

**Interfaces:**
- Consumes: `LocalStore`, `InMemoryLocalStore`, `ReadingProgress`, `BookmarkRecord` from Task 1.
- Produces:
  - `ReaderViewModel(nativeId: Long, chapterId: Long, service: LibraryService, localStore: LocalStore = InMemoryLocalStore(), chapterTitle: String = "", clockMs: () -> Long = System::currentTimeMillis, scope: CoroutineScope = ...)`:
    - On `load()` success: queries `localStore.getReadingProgress(nativeId)`; if `saved?.chapterId == chapterId`, sets initial `pageIndex = saved.pageIndex.coerceIn(0, result.pages.lastIndex)` (Review Focus 3), else `0`. Saves `ReadingProgress(comicId = nativeId, chapterId = chapterId, chapterTitle = chapterTitle.ifBlank { "Chapter $chapterId" }, pageIndex = initialPageIndex, updatedAtEpochMs = clockMs())`. Also if `localStore.getBookmark(nativeId)` exists with `hasUnreadUpdate = true`, clears `hasUnreadUpdate = false` and `newChapterCount = 0`.
    - On `onPageChanged(index)` and `turnPage(delta)` within the chapter: saves updated `ReadingProgress` with the new `pageIndex`.
  - `DetailUiState`: adds `val isBookmarked: Boolean = false`, `val readingProgress: ReadingProgress? = null`.
  - `DetailViewModel(nativeId: Long, service: LibraryService, localStore: LocalStore = InMemoryLocalStore(), clockMs: () -> Long = System::currentTimeMillis, scope: CoroutineScope = ...)`:
    - On `load()` success: loads `localStore.getBookmark(nativeId)` and `localStore.getReadingProgress(nativeId)`. If bookmarked, updates the bookmark's `lastKnownChapterIds = comic.chapters.map { it.chapterId }.toSet()`, `latestChapterTitle = sortedChapters.firstOrNull()?.chapterTitle.orEmpty()`, `hasUnreadUpdate = false`, `newChapterCount = 0`, and sets `isBookmarked = true`.
    - `fun toggleBookmark()`: if currently bookmarked, calls `localStore.deleteBookmark(nativeId)` and sets `isBookmarked = false`; if not bookmarked and `state.value.comic != null`, saves `BookmarkRecord` seeded with `lastKnownChapterIds = comic.chapters.map { it.chapterId }.toSet()` (Review Focus 4) and sets `isBookmarked = true`.
    - `fun refreshProgress()`: reloads `readingProgress` and `isBookmarked` from `localStore` when returning from the reader.
  - `DetailScreen`:
    - Top bar has `"Back"` button and `"Bookmarked"` / `"Bookmark"` toggle button.
    - When `uiState.readingProgress != null`, renders a `"Continue: ${progress.chapterTitle} (p.${progress.pageIndex + 1})"` button above the chapter list that navigates to `"reader/$nativeId/${progress.chapterId}"`.

- [ ] **Step 1: Write failing unit tests** in `ReaderViewModelTest.kt` (resuming matching chapter at saved `pageIndex`, clamping out-of-range saved `pageIndex`, starting at 0 when saved `chapterId` differs, saving progress on page turns, and clearing unread bookmark badge) and `DetailViewModelTest.kt` (`toggleBookmark` seeds `lastKnownChapterIds` and removes on second toggle, loading a bookmarked comic clears `hasUnreadUpdate`, and `readingProgress` surfaces in `DetailUiState`).
- [ ] **Step 2: Run tests to verify RED**: `./gradlew testDebugUnitTest --tests "com.fishkingsin.manhuagui.ui.reader.ReaderViewModelTest" --tests "com.fishkingsin.manhuagui.ui.detail.DetailViewModelTest"`.
- [ ] **Step 3: Implement** `ReaderViewModel.kt`, `DetailViewModel.kt`, `DetailScreen.kt`, `ReaderScreen.kt`, `AppNav.kt`, and `MainActivity.kt` passing `localStore`.
- [ ] **Step 4: Verify GREEN**: `./gradlew testDebugUnitTest assembleDebug` passes.
- [ ] **Step 5: Commit**: `feat: persist reading progress resume and detail bookmark toggle`.

### Task 4: Sequential `BookmarkUpdateChecker`, notification formatter, and WorkManager worker

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/fishkingsin/manhuagui/App.kt`
- Create: `app/src/main/java/com/fishkingsin/manhuagui/bookmarks/BookmarkUpdateChecker.kt`, `app/src/main/java/com/fishkingsin/manhuagui/bookmarks/BookmarkUpdateNotification.kt`, `app/src/main/java/com/fishkingsin/manhuagui/bookmarks/BookmarkUpdateWorker.kt`
- Test: `app/src/test/java/com/fishkingsin/manhuagui/bookmarks/BookmarkUpdateCheckerTest.kt`

**Interfaces:**
- Consumes: `LocalStore`, `BookmarkRecord`, `LibraryService`
- Produces:
  - `data class UpdatedBookmark(val comicId: Long, val title: String, val latestChapterTitle: String, val newChapterCount: Int)`
  - `data class NotificationSummary(val title: String, val body: String)`
  - `fun formatUpdateNotification(updates: List<UpdatedBookmark>): NotificationSummary?`:
    - Empty list -> `null`.
    - 1 item -> `NotificationSummary(title = "1 manga updated", body = "${u.title}: ${u.latestChapterTitle} (+${u.newChapterCount})")`.
    - N items -> `NotificationSummary(title = "$n manga updated", body = updates.joinToString(", ") { "${it.title} (${it.latestChapterTitle})" })`.
  - `class BookmarkUpdateChecker(private val localStore: LocalStore, private val service: LibraryService, private val delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }, private val jitterMs: () -> Long = { kotlin.random.Random.nextLong(1000L, 2001L) }, private val clockMs: () -> Long = System::currentTimeMillis)`:
    - `suspend fun checkAll(sourceId: String = DEFAULT_SOURCE_ID): List<UpdatedBookmark>`:
      - Loads `localStore.getBookmarks(sourceId)`.
      - Iterates sequentially; before each bookmark after index 0, calls `delayMs(jitterMs().coerceIn(1000L, 2000L))` (Review Focus 4).
      - Calls `service.getComic(bookmark.comicId)`; on failure, skips to the next bookmark without aborting the check.
      - Computes `currentIds = comic.chapters.map { it.chapterId }.toSet()` and `newIds = currentIds - bookmark.lastKnownChapterIds`.
      - Determines newest chapter title using the same comparator as `DetailViewModel` (`groupSize` desc, `groupName` asc, `order` desc).
      - Updates `BookmarkRecord` in `localStore`: `lastCheckedEpochMs = now`; if `newIds.isNotEmpty()`, increments `newChapterCount = bookmark.newChapterCount + newIds.size`, sets `hasUnreadUpdate = true`, `latestChapterTitle = newestTitle`, `updatedAtEpochMs = now`, `lastKnownChapterIds = currentIds`, and adds `UpdatedBookmark` to the result list. If `newIds.isEmpty()`, updates `lastCheckedEpochMs = now` while preserving any existing unread badge.
  - `BookmarkUpdateNotification`:
    - `const val CHANNEL_ID = "bookmark_updates"`
    - `const val EXTRA_OPEN_ROUTE = "open_route"`
    - `const val ROUTE_BOOKMARKS = "bookmarks"`
    - `fun ensureChannel(context: Context)`
    - `fun postIfUpdated(context: Context, updates: List<UpdatedBookmark>)`
  - `BookmarkUpdateWorker` (`CoroutineWorker`): runs `container.bookmarkUpdateChecker.checkAll()`, calls `BookmarkUpdateNotification.postIfUpdated(applicationContext, updates)`, and returns `Result.success()`.
  - `BookmarkUpdateWorker.schedulePeriodic(context: Context)` (6-hour periodic work with `NetworkType.CONNECTED` and `ExistingPeriodicWorkPolicy.KEEP`), called from `App.onCreate()`.
  - `AppContainer.bookmarkUpdateChecker: BookmarkUpdateChecker`.

- [ ] **Step 1: Write failing unit tests** in `BookmarkUpdateCheckerTest.kt` testing sequential execution with recorded 1000..2000ms jitter delays between multiple bookmarks, detecting new chapter IDs and updating `LocalStore`, resilience when one comic fetch throws an error while another succeeds, preserving existing unread badge when no additional chapters arrive, and `formatUpdateNotification` for 0, 1, and multiple updated manga.
- [ ] **Step 2: Run test to verify RED**: `./gradlew testDebugUnitTest --tests com.fishkingsin.manhuagui.bookmarks.BookmarkUpdateCheckerTest`.
- [ ] **Step 3: Implement** `BookmarkUpdateChecker.kt`, `BookmarkUpdateNotification.kt`, `BookmarkUpdateWorker.kt`, add `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` to `AndroidManifest.xml`, and wire `bookmarkUpdateChecker` + `BookmarkUpdateNotification.ensureChannel` + `BookmarkUpdateWorker.schedulePeriodic` in `App.kt`.
- [ ] **Step 4: Verify GREEN**: `./gradlew testDebugUnitTest assembleDebug` passes.
- [ ] **Step 5: Commit**: `feat(bookmarks): add sequential update checker, notifications, and WorkManager worker`.

### Task 5: Bookmarks screen, notification routing, battery-optimization hint, and docs

**Files:**
- Modify: `app/src/main/java/com/fishkingsin/manhuagui/ui/latest/LatestScreen.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/AppNav.kt`, `app/src/main/java/com/fishkingsin/manhuagui/MainActivity.kt`, `docs/device-checklist.md`, `README.md`
- Create: `app/src/main/java/com/fishkingsin/manhuagui/ui/bookmarks/BookmarksViewModel.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/bookmarks/BookmarksScreen.kt`
- Test: `app/src/test/java/com/fishkingsin/manhuagui/ui/bookmarks/BookmarksViewModelTest.kt`

**Interfaces:**
- Consumes: `LocalStore`, `BookmarkRecord`, `BookmarkUpdateChecker`, `BookmarkUpdateNotification`
- Produces:
  - `data class BookmarksUiState(val items: List<BookmarkRecord> = emptyList(), val checking: Boolean = false, val lastUpdatedCount: Int? = null)`
  - `class BookmarksViewModel(private val localStore: LocalStore, private val checker: BookmarkUpdateChecker, private val scope: CoroutineScope = ...)`:
    - Observes `localStore.observeBookmarks()` into `state.items`.
    - `fun checkUpdatesNow()`: guarded by `if (state.value.checking) return` (Review Focus 5); sets `checking = true`, calls `checker.checkAll()`, and sets `checking = false, lastUpdatedCount = updates.size`.
    - `fun removeBookmark(comicId: Long)`: calls `localStore.deleteBookmark(comicId)`.
  - `BookmarksScreen`:
    - Header with `"Back"` button, `"Bookmarks"` title, and `"Check updates"` button (disabled while `uiState.checking`).
    - When `!isIgnoringBatteryOptimizations(context)`, shows a 1dp-bordered e-ink hint box `"Battery optimization may stop background update checks."` with a `"Battery settings"` button launching `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
    - Renders bookmarked comics using monochrome 1dp-bordered cards with cover image, title, latest chapter text, `"Updated (+${item.newChapterCount})"` badge when `item.hasUnreadUpdate` is true, and a `"Remove"` button. Tapping a card navigates to `"comic/${item.comicId}"`.
  - `LatestScreen`: adds `"Bookmarks"` button in the top header bar next to `"Search"`, calling `onBookmarks: () -> Unit`.
  - `AppNav`: adds `initialRoute: String = "latest"` (or navigates to `"bookmarks"` when `openBookmarks` is true) and registers `composable("bookmarks") { BookmarksScreen(...) }`.
  - `MainActivity`: reads `intent?.getStringExtra(BookmarkUpdateNotification.EXTRA_OPEN_ROUTE)` and passes it into `AppNav` so tapping an update notification opens the Bookmarks screen.

- [ ] **Step 1: Write failing unit tests** in `BookmarksViewModelTest.kt` verifying bookmark list observation from `LocalStore`, `checkUpdatesNow()` updating `lastUpdatedCount` and bookmark badges, duplicate `checkUpdatesNow()` while `checking` being ignored (Review Focus 5), and `removeBookmark(comicId)`.
- [ ] **Step 2: Run test to verify RED**: `./gradlew testDebugUnitTest --tests com.fishkingsin.manhuagui.ui.bookmarks.BookmarksViewModelTest`.
- [ ] **Step 3: Implement** `BookmarksViewModel.kt`, `BookmarksScreen.kt`, wire `LatestScreen.kt`, `AppNav.kt`, and `MainActivity.kt`, and update `docs/device-checklist.md` and `README.md` with Phase 2 verification checks.
- [ ] **Step 4: Verify GREEN**: `./gradlew testDebugUnitTest assembleDebug` and `cargo test --manifest-path ffi/Cargo.toml` pass.
- [ ] **Step 5: Commit**: `feat(bookmarks): add bookmarks screen, notification routing, and phase 2 docs`.
