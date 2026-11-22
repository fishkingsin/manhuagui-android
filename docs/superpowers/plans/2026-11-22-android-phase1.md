# manhuagui-android Phase 1 Implementation Plan (Skeleton + Minimal Reader)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A debuggable Android APK that browses manhuagui's Japanese-comic updates (7/15/30-day windows), searches, opens comic details, and reads a chapter streamed live with RTL paging and tap zones — all over the shared Rust core via UniFFI, styled for e-ink.

**Architecture:** Kotlin + Jetpack Compose single-`:app` app; `ffi/` Rust crate (`manhuagui-ffi`) in this repo depends on the fork's core crates (git tag) and exposes one `AndroidLibrary` object via UniFFI (async, tokio); `source-api` gains an optional `uniffi` feature (Record/Enum derives, cfg_attr'd like `specta`) — a small fork-side change tagged `v0.1.1-core`. Coil 3 loads pages with per-`PageRef` headers. No DB, no DI framework, no WorkManager in this phase.

**Tech Stack:** Kotlin 2.x, Compose BOM (Material3), Navigation-Compose, Coil 3.x (Compose), OkHttp via Coil; Rust via `org.mozilla.rust-android-gradle` plugin (cargo-ndk, targets `aarch64-linux-android` + `x86_64-linux-android`), UniFFI 0.28 proc-macro + kotlin bindgen.

**Spec:** `docs/superpowers/specs/2026-11-22-manhuagui-android-ereader-design.md` (§Architecture, §Phasing phase 1)

## Global Constraints

- minSdk 29, targetSdk/compileSdk 35; Kotlin 2.x; single `:app` Gradle module + `ffi/` cargo crate.
- E-ink style is law: pure black-on-white, no ripples (neutral `LocalIndication`), no NavHost transitions, no crossfades, no elevation shadows; 1dp borders; system font; large tap targets.
- UI language English; comic/chapter titles render as-is (CJK).
- Reader defaults RTL (`LayoutDirection.Rtl`); pages decode at full source resolution (zoom is in-pipeline from day one).
- Core comes from the fork by tag — never path deps: `manhuagui-ffi` deps = `{ git = "https://github.com/fishkingsin/manhuagui-downloader.git", tag = "v0.1.1-core" }` (Task 2 creates that tag; until then Task 1 pins `v0.1.0-core` and the ffi crate is a stub).
- Kotlin code never sees site specifics: it consumes `AndroidLibrary`, `PageRef.headers`, and mapped sealed errors only.
- Interactive Latest window queries use `max_pages = 10` (anti-ban pacing: ~10 requests per window query); `All` mode paginates one page at a time on user demand.
- Every task ends with `./gradlew assembleDebug` green (and `cargo test -p manhuagui-ffi` where Rust changed).

## Review Focus

1. **Rate-limit burst on cold open** — Latest with a 30-day window walks ≤10 pages sequentially; a UI recomposition must not re-trigger the query (StateFlow + `loading` guard). Test: LatestViewModel test — second `refresh()` while loading is a no-op.
2. **Error text is English, structured** — `SourceError` maps to sealed `AppError` (Network/RateLimited/ParseFailed/NotFound/Cancelled) with English `userMessage`; raw site/eyre strings never reach UI. Test: mapping unit test per variant.
3. **RTL tap-zone mirroring** — `tapZoneAction(x, width, rtl)` pure fn: in RTL, left 40% = NEXT page, right 40% = PREV (mirrored), center = chrome; in LTR unreversed. Test: boundary values x=0, 0.39w, 0.5w, 0.61w, w.
4. **Zoom state isolation** — scale/offset reset when page index changes; pager composes only the visible page (no beyondBounds). Test: ReaderState pure class unit test.
5. **Empty/failed chapter** — 0 pages or decrypt failure → error state with retry, never a crash or blank screen. Test: ViewModel with fake service returning SourceError.ParseFailed → Error state.

---

### Task 1: Repo scaffold — Gradle + Rust build + CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/…/MainActivity.kt`, `app/src/main/java/…/ui/theme/Theme.kt`, `ffi/Cargo.toml`, `ffi/src/lib.rs`, `.github/workflows/android-ci.yml`, `.gitignore`, `README.md`

**Interfaces:**
- Produces: an APK showing a placeholder Compose screen, whose `libmanhuagui_ffi.so` (aarch64 + x86_64) is built by Gradle from `ffi/`; `ffi` exposes `#[uniffi::export] fn core_version() -> String` returning the tag it was built from.
- Gradle task graph: `cargoBuild` (rust plugin) → `generateUniffiBindings` (runs `uniffi-bindgen kotlin`, output into `app/src/main/java/uniffi/`) → `compileDebugKotlin`.

- [ ] **Step 1: Scaffold the Gradle project** — versions catalog pinning Kotlin 2.x, Compose BOM, Coil 3, Navigation-Compose; `minSdk 29`, `targetSdk 35`; `MainActivity` renders a centered `Text("manhuagui ${coreVersion()}")` from the generated binding.
- [ ] **Step 2: Wire the Rust build** — `org.mozilla.rust-android-gradle` plugin config (module `ffi`, targets aarch64 + x86_64, profile debug); `ffi` deps: `uniffi = { version = "0.28", features = ["build"] }`, dev `uniffi-bindgen` via `uniffi` cli feature; `uniffi::setup_scaffolding!("manhuagui_ffi");` in lib.rs; `generateUniffiBindings` Gradle task invoking `cargo run -p uniffi-bindgen kotlin`… or the crate's `uniffi-bindgen` binary — commit generated bindings are NOT committed.
- [ ] **Step 3: Verify** — `./gradlew assembleDebug` → APK contains both .so files (`unzip -l` check `lib/*/libmanhuagui_ffi.so` ×2) and installs/launches on emulator or device (device optional this task; emulator enough). `cargo test -p manhuagui-ffi` green.
- [ ] **Step 4: CI** — workflow: rust toolchain + JDK 17 + gradle `assembleDebug` + `cargo test -p manhuagui-ffi`; commit README (build + `adb install` instructions).
- [ ] **Step 5: Commit** — `feat: android scaffold with rust/uniFFI build`.

### Task 2: Fork-side `uniffi` feature + `AndroidLibrary` FFI surface

**Files:**
- Modify (FORK repo `fishkingsin/manhuagui-downloader`, new branch `uniffi-feature`): `core/source-api/Cargo.toml`, `core/source-api/src/{types,source,error,config}.rs`
- Create: `ffi/src/library.rs` (android repo)
- Test: fork `core/source-api/tests/uniffi_record_test.rs` (serde+uniffi derive coexistence); android `ffi/tests/library_test.rs`

**Interfaces:**
- Fork: `uniffi = { version = "0.28", optional = true }` feature on source-api; `#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]` on `ComicSummary`, `BrowseResult`, `ComicInSearch`, `SearchResult`, `Comic`, `ChapterInfo`, `PageRef`, `ChapterPages`, `ComicId`; `#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]` on `UpdateWindow`, `DownloadTaskState`?? (not needed phase 1 — only what crosses); `#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]` on `UpdateWindow` and `SourceError` becomes `#[cfg_attr(feature="uniffi", derive(uniffi::Error))]` (variants carry `message: String`). Tag `v0.1.1-core` (merge to fork main, push tag).
- Android `ffi`: `pub struct AndroidLibrary { inner: Library }` with `#[uniffi::export(async_runtime = "tokio")]`:
  - `#[uniffi::constructor] fn new(cookie: String, proxy_mode: String, proxy_host: String, proxy_port: u16) -> AndroidLibrary` (builds `CoreConfig` defaults + cookie/proxy; registers `ManhuaguiSource`)
  - `async fn browse_latest(&self, window: UpdateWindow, start_page: i32, max_pages: i32) -> Result<Vec<ComicSummary>, SourceError>` (delegates to `browse_within`, which internally pages `browse_latest`)
  - `async fn search(&self, keyword: String, page: i32) -> Result<SearchResult, SourceError>`
  - `async fn get_comic(&self, native_id: i64) -> Result<Comic, SourceError>`
  - `async fn get_chapter_pages(&self, native_id: i64, chapter_id: i64) -> Result<ChapterPages, SourceError>`

- [ ] **Step 1 (fork):** failing test — with `--features uniffi`, records derive and round-trip through uniffi metadata (compile-level test + a serde round-trip proving attrs coexist); RED → implement cfg_attrs → GREEN; `cargo test --workspace` (feature off) still green; branch `uniffi-feature`, PR-less merge to fork main, tag `v0.1.1-core`, push.
- [ ] **Step 2 (android):** bump ffi git dep to `v0.1.1-core`; failing rust test — `AndroidLibrary` with a registered `MockSource` (fixture pages, like core's service_test): browse_latest Days7 returns filtered items; search/get_comic/get_chapter_pages map through; error variant propagates. RED → implement `library.rs` → GREEN (`cargo test -p manhuagui-ffi`). Regenerate bindings; assembleDebug green.
- [ ] **Step 3: Commit** — `feat(ffi): AndroidLibrary surface over core (fork v0.1.1-core)`.

### Task 3: Kotlin service layer + Coil PageRef pipeline

**Files:**
- Create: `app/src/main/java/…/core/LibraryService.kt`, `app/src/main/java/…/core/AppError.kt`, `app/src/main/java/…/core/PageImages.kt`, `app/src/main/java/…/App.kt` (Application: ImageLoader + AppContainer)
- Test: `app/src/test/java/…/core/AppErrorTest.kt`, `app/src/test/java/…/core/PageImagesTest.kt`

**Interfaces:**
- `sealed interface AppError { val userMessage: String; data class Network(…); RateLimited; ParseFailed; NotFound; Cancelled; data class Unknown(String) }` — `fun SourceError.toAppError(): AppError` maps variant → English message ("Couldn't reach the site", "The site is rate-limiting — try later", "Site format changed — update the app", "Not found", "Cancelled").
- `class LibraryService(private val library: AndroidLibrary, private val io: CoroutineDispatcher)` — suspend fns returning `Result<T>` (kotlin.Result) wrapping the UniFFI calls on `io`; `fun interface` nothing else.
- `object PageImages`: `fun modelFor(page: PageRef): ImageRequest.Builder.() -> Unit` applying `.httpHeaders { set("Referer", …) for each header }` and `memoryCacheKey(page.url)`; Coil 3 `ImageLoader` built in `App` with `diskCache` 512MB, `crossfade(false)`.
- Consumes: generated `uniffi.manhuagui_ffi.*` types.

- [ ] **Step 1: Failing tests** — AppErrorTest: each SourceError variant → expected English userMessage (fake the generated types by constructing them — generated classes are constructible); PageImagesTest: PageRef with Referer header → HttpHeaders contains it; url key stable.
- [ ] **Step 2: RED → implement → GREEN**; `./gradlew testDebugUnitTest` green.
- [ ] **Step 3: Commit** — `feat: service layer + page image pipeline`.

### Task 4: Latest screen (window-filtered browse)

**Files:**
- Create: `app/src/main/java/…/ui/latest/LatestViewModel.kt`, `LatestScreen.kt`, shared `ui/comic/ComicCard.kt`, `ui/AppNav.kt`
- Test: `app/src/test/java/…/ui/latest/LatestViewModelTest.kt`

**Interfaces:**
- `LatestViewModel(service)` — state: `data class LatestUiState(window: UpdateWindow = Days7, loading: Boolean, items: List<ComicSummary>, error: AppError?, endReached: Boolean)`; `refresh()` (guarded by `loading` — Review Focus 1), `setWindow(w)`, `loadMore()` (All mode only: appends next `browse_latest` page; Days modes load once via `browse_latest(window, 1, 10)`).
- Nav routes: `"latest"`, `"search"`, `"comic/{nativeId}"`, `"reader/{nativeId}/{chapterId}"`.
- `ComicCard`: cover (Coil, no crossfade), title (2-line ellipsis), status_line + updated_on chip; no elevation; 1dp border.

- [ ] **Step 1: Failing ViewModel tests** — fake `LibraryService`: setWindow(Days30) → loading then items; refresh while loading = no second service call; loadMore in All appends and sets endReached at total_pages; error → AppError surfaced, items cleared. RED → implement → GREEN.
- [ ] **Step 2: Screen + nav wired**; assembleDebug; manual emulator smoke (window chips switch, cards scroll).
- [ ] **Step 3: Commit** — `feat: latest screen with 7/15/30-day windows`.

### Task 5: Search + Detail screens

**Files:**
- Create: `app/src/main/java/…/ui/search/SearchViewModel.kt`+`SearchScreen.kt`, `app/src/main/java/…/ui/detail/DetailViewModel.kt`+`DetailScreen.kt`
- Test: `app/src/test/java/…/ui/{search/SearchViewModelTest,detail/DetailViewModelTest}.kt`

**Interfaces:**
- SearchViewModel: `query`, `results`, `loading`, `error`; `submit()` → `service.search(query, 1)`; result cards reuse ComicCard → comic route.
- DetailViewModel(nativeId): `comic: Comic?`, `chapters: List<ChapterInfo>` displayed newest-first (site order reversed), `loading/error`; tap chapter → reader route. No bookmark button yet (phase 2).

- [ ] **Step 1: Failing tests** (fake service; newest-first ordering assertion; error paths) → RED → implement → GREEN.
- [ ] **Step 2: Screens + nav**; assembleDebug; emulator smoke.
- [ ] **Step 3: Commit** — `feat: search and comic detail screens`.

### Task 6: Reader screen (streaming, RTL, tap zones, zoom)

**Files:**
- Create: `app/src/main/java/…/ui/reader/ReaderState.kt`, `ReaderViewModel.kt`, `ReaderScreen.kt`
- Test: `app/src/test/java/…/ui/reader/{ReaderStateTest,ReaderViewModelTest}.kt`

**Interfaces:**
- `ReaderState` (pure): `pageIndex`, `zoom: Float (1f..4f)`, `offset: Offset`; `fun onPagerPage(i)`: resets zoom/offset (Review Focus 4); `fun tapZoneAction(x: Float, width: Float, rtl: Boolean): TapAction` where `TapAction = PrevPage | NextPage | ToggleChrome` (Review Focus 3: left 40% / center 20% / right 40%, mirrored in RTL).
- ReaderViewModel(nativeId, chapterId): loads `ChapterPages`; exposes pages, index, chromeVisible, error+retry; `turnPage(delta)` clamps and auto-advances chapter at ends using `prev_chapter_id`/`next_chapter_id` (via nav to new reader route).
- ReaderScreen: `HorizontalPager` (`LayoutDirection.Rtl`), `AsyncImage(page.url)` full-res (no size constraint — zoom pipeline), pinch (`detectTransformGestures`: scale 1–4, pan clamped) + double-tap toggles 1f/2f (instant, no animation), tap-zone overlay `Modifier.pointerInput` calling `tapZoneAction`, chrome = minimal top bar (chapter title, page i/n, back), `FLAG_KEEP_SCREEN_ON` while resumed, system back works.
- Page model built via `PageImages.modelFor(pageRef)`.

- [ ] **Step 1: Failing tests** — ReaderStateTest (tap-zone boundaries incl. mirrored RTL, zoom reset on page change, clamp); ReaderViewModelTest (fake service: load ok, load fail → Error+retry clears error; 0 pages → error state — Review Focus 5; advance at last page with next id). RED → implement → GREEN.
- [ ] **Step 2: Screen**; assembleDebug.
- [ ] **Step 3: Commit** — `feat: streaming RTL reader with tap zones and zoom`.

### Task 7: E-ink polish pass + device checklist + docs

**Files:**
- Modify: theme (`LocalIndication` = no-op indication, colors black/white only), `AppNav` (no transitions)
- Create: `docs/device-checklist.md`, README updates
- Test: existing suites stay green; no new tests.

**Interfaces:** none — polish + documentation.

- [ ] **Step 1:** Audit every screen: no ripple, no animated transitions, monochrome; dark-mode palette swap via `isSystemInDarkTheme` (white-on-black). `./gradlew testDebugUnitTest assembleDebug` green.
- [ ] **Step 2:** `docs/device-checklist.md` — the on-device gate (from the design + session): sideload via adb; browse 7/15/30 + search + read a chapter; ghosting observations; physical page-turn button keycodes (`adb logcat` filter for dispatched keys — informational logging added in ReaderScreen `onKeyEvent`); RAM with a long chapter; keep-screen-on; battery-optimization prompt NOT in this phase (phase 2).
- [ ] **Step 3: Commit** — `chore: e-ink polish, device checklist, docs`; tag `v0.1.0-phase1`.

## Out of scope (later phases per spec)

Bookmarks/notifications/WorkManager (phase 2, with Room + keys/prefetch/progress-resume), offline downloads + export + foreground service (phase 3), settings beyond hard-coded defaults, LTR toggle UI, genre chips.
