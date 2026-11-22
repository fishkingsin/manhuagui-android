# manhuagui-android Phase 1 Implementation Plan (Skeleton + Minimal Reader)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A debuggable Android APK that browses manhuagui's Japanese-comic updates (7/15/30-day windows), searches, opens comic details, and reads a chapter streamed live with RTL paging and tap zones — all over the shared Rust core via UniFFI, styled for e-ink.

**Architecture:** Kotlin + Jetpack Compose single-`:app` app; `ffi/` Rust crate (`manhuagui-ffi`) in this repo depends on the fork's core crates (git tag) and exposes one `AndroidLibrary` object via UniFFI (async, tokio); `source-api` gains an optional `uniffi` feature on the simple `UpdateWindow` enum and `SourceError`; `ffi` defines small UniFFI DTO records instead of deriving across core storage types — a small fork-side change tagged `v0.1.1-core`. Coil 3 loads FFI `ImagePage`s with per-page headers. No DB, no DI framework, no WorkManager in this phase.

**Tech Stack:** AGP 8.13.2 + Gradle 8.13, Kotlin 2.2.10, Compose BOM (Material3), Navigation-Compose, Coil 3.x (Compose), OkHttp via Coil; Rust via `org.mozilla.rust-android-gradle.rust-android` 0.10.0 (targets `arm64` + `x86_64`); UniFFI 0.28.3 proc-macro + Kotlin bindgen. JDK 17 in CI; JDK 21 supported locally.

**Spec:** `docs/superpowers/specs/2026-11-22-manhuagui-android-ereader-design.md` (§Architecture, §Phasing phase 1)

## Global Constraints

- Use minSdk 29 and targetSdk/compileSdk 35. Pin AGP 8.13.2, Gradle 8.13, and Kotlin Gradle plugin 2.2.10. Use one `:app` module and one `ffi/` Cargo crate.
- Commit the Gradle wrapper files for version 8.13. Run builds with `./gradlew`.
- Install Rust targets `aarch64-linux-android` and `x86_64-linux-android`. Configure the Gradle plugin with target names `arm64` and `x86_64`. Pin NDK 27.2.12479018.
- E-ink style is law: pure black-on-white, no ripples (neutral `LocalIndication`), no NavHost transitions, no crossfades, no elevation shadows; 1dp borders; system font; large tap targets.
- UI language English; comic/chapter titles render as-is (CJK).
- Reader defaults RTL (`LayoutDirection.Rtl`); pages decode at full source resolution (zoom is in-pipeline from day one).
- When FFI consumes Rust core crates, dependencies use fork git tags, never local path deps. Task 1 is an isolated FFI stub; Task 2 adds `source-api`, `source-manhuagui`, and `library` dependencies pinned to the fork's `v0.1.1-core` tag.
- Kotlin code never sees site specifics: it consumes `AndroidLibrary`, FFI `ImagePage.headers`, and mapped sealed errors only.
- Interactive Latest window queries use `max_pages = 10` (anti-ban pacing: ~10 requests per window query); `All` mode paginates one page at a time on user demand.
- Every task ends with `./gradlew assembleDebug` green (and `cargo test --manifest-path ffi/Cargo.toml` where Rust changed).

## Review Focus

1. **Rate-limit burst on cold open** — Latest with a 30-day window walks ≤10 pages sequentially; a UI recomposition must not re-trigger the query (StateFlow + `loading` guard). Test: LatestViewModel test — second `refresh()` while loading is a no-op.
2. **Error text is English, structured** — `SourceError` maps to sealed `AppError` (Network/RateLimited/ParseFailed/NotFound/Cancelled) with English `userMessage`; raw site/eyre strings never reach UI. Test: mapping unit test per variant.
3. **RTL tap-zone mirroring** — `tapZoneAction(x, width, rtl)` pure fn: in RTL, left 40% = NEXT page, right 40% = PREV (mirrored), center = chrome; in LTR unreversed. Test: boundary values x=0, 0.39w, 0.5w, 0.61w, w.
4. **Zoom state isolation** — scale/offset reset when page index changes; pager composes only the visible page (no beyondBounds). Test: ReaderState pure class unit test.
5. **Empty/failed chapter** — 0 pages or decrypt failure → error state with retry, never a crash or blank screen. Test: ViewModel with fake service returning SourceError.ParseFailed → Error state.

---

### Task 1: Repo scaffold — Gradle + Rust build + CI

**Files:**
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/fishkingsin/manhuagui/MainActivity.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/theme/Theme.kt`, `ffi/Cargo.toml`, `ffi/src/lib.rs`, `ffi/uniffi-bindgen.rs`, `.github/workflows/android-ci.yml`, `.gitignore`, `README.md`
- Modify: `docs/superpowers/plans/2026-11-22-android-phase1.md` to record preflight decisions and exact build commands

**Interfaces:**
- Produces: an APK with a placeholder Compose screen and `libmanhuagui_ffi.so` for both Android ABIs. The Rust function `core_version() -> String` returns `"ffi-stub"` in Task 1 and `"v0.1.1-core"` after Task 2 links the core.
- Gradle task graph:
  - `cargoBuild` cross-compiles the Rust crate for Android.
  - `buildHostFfi` runs `cargo build --manifest-path ffi/Cargo.toml --lib` for the host.
  - `generateUniffiBindings` depends on `buildHostFfi` and runs `cargo run --manifest-path ffi/Cargo.toml --features uniffi-bindgen --bin uniffi-bindgen -- generate --library <host-cdylib> --language kotlin --out-dir app/build/generated/source/uniffi`.
  - `compileDebugKotlin` depends on generated sources and Android `cargoBuild`.

UniFFI must inspect a host cdylib. macOS cannot load the Android `.so` to read UniFFI metadata.

- [ ] **Step 1: Scaffold the Gradle project.** Pin the wrapper to Gradle 8.13, AGP to 8.13.2, and the Kotlin Gradle plugin to 2.2.10. Add Compose BOM, Coil 3, and Navigation-Compose. Set `minSdk 29` and `targetSdk 35`. Add the JNA 5.12.0 AAR required by generated UniFFI Kotlin bindings. Create `MainActivity` that imports `uniffi.manhuagui_ffi.coreVersion` and renders `Text("manhuagui ${coreVersion()}")`.
- [ ] **Step 2: Configure Android Rust builds.** Apply plugin `org.mozilla.rust-android-gradle.rust-android` version `0.10.0`. In `app/build.gradle.kts`, set `cargo.module` to `../ffi`, `cargo.libname` to `manhuagui_ffi`, `cargo.targets` to `listOf("arm64", "x86_64")`, and `cargo.profile` to `debug`. In `ffi/Cargo.toml`, set `[lib] name = "manhuagui_ffi", crate-type = ["cdylib", "rlib"]`.
- [ ] **Step 3: Write and run the failing Rust test.** Add a unit test in `ffi/src/lib.rs` that asserts `core_version()` returns `"ffi-stub"`. Run `cargo test --manifest-path ffi/Cargo.toml`. Confirm it fails because the function is missing.
- [ ] **Step 4: Implement the Rust smoke function.** Add `uniffi = { version = "0.28.3", features = ["tokio"] }`, the Cargo feature `uniffi-bindgen = ["uniffi/cli"]`, and a `[[bin]]` named `uniffi-bindgen`. Its `main` calls `uniffi::uniffi_bindgen_main()`. Call `uniffi::setup_scaffolding!("manhuagui_ffi")` and implement `core_version() -> String` to return `"ffi-stub"`. Task 2 changes this value to `"v0.1.1-core"` after linking the core. Re-run the Rust test and confirm it passes.
- [ ] **Step 5: Generate UniFFI bindings.** Build a host cdylib before running `uniffi-bindgen generate --library`; do not pass an Android `.so` to the host tool. Add the generated source directory to the app source set. Keep generated Kotlin under `app/build/`; do not commit it.
- [ ] **Step 6: Verify the APK.** Run `./gradlew assembleDebug`. Confirm the APK includes `libmanhuagui_ffi.so` for both `arm64-v8a` and `x86_64`. Install and launch it on an emulator or device if one is available. From the repo root, run `cargo test --manifest-path ffi/Cargo.toml`.
- [ ] **Step 7: Add CI and build instructions.** Pin JDK 17, Android platform/build-tools 35, and NDK 27.2.12479018 in CI. Install Rust targets `aarch64-linux-android` and `x86_64-linux-android`. Run `./gradlew assembleDebug` and `cargo test --manifest-path ffi/Cargo.toml`. Document the JDK, Android SDK, and Rust prerequisites and the `adb install` command in README.
- [ ] **Step 8: Commit.** Use `feat: android scaffold with Rust and UniFFI build`.

### Task 2: Fork-side `uniffi` feature + `AndroidLibrary` FFI surface

**Files:**
- Modify (FORK repo `fishkingsin/manhuagui-downloader`, new branch `uniffi-feature`): `core/source-api/Cargo.toml`, `core/source-api/src/{source,error,types}.rs`
- Create: `ffi/src/{library.rs,types.rs}` (android repo)
- Test: fork `core/source-api/tests/uniffi_record_test.rs`; android `ffi/tests/library_test.rs`

**Interfaces:**
- Fork: `uniffi = { version = "0.28.3", optional = true }` feature on source-api; only `UpdateWindow` gets `#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]` and `SourceError` gets `#[cfg_attr(feature = "uniffi", derive(uniffi::Error))]` (variants carry `message: String`). Do not derive UniFFI on `Comic`, `ChapterInfo`, `SearchResult`, or date-bearing core records: they contain `PathBuf` / `chrono::NaiveDate` storage-domain fields. Tag `v0.1.1-core` (merge to fork main, push tag).
- `ffi/src/types.rs` defines the UniFFI API records. `ComicListItem` contains `comic_id`, `title`, `cover_url`, `status_text`, `update_text: Option<String>`, and `rating: Option<f32>`.
- `BrowsePage` contains `items: Vec<ComicListItem>`, `page`, and `total_pages`. `SearchPage` contains `items: Vec<ComicListItem>`, `current`, and `total`.
- `ChapterItem` contains `chapter_id`, `chapter_title`, `chapter_size`, `group_name`, `group_size`, and `order: f64`.
- `ComicDetails` contains the comic's display metadata and `chapters: Vec<ChapterItem>`.
- `ImagePage` contains `url` and `headers: HashMap<String, String>`. `ChapterPagesResult` contains `pages: Vec<ImagePage>`, `prev_chapter_id`, and `next_chapter_id`.
- `AndroidLibrary` maps core domain objects to these records at the FFI boundary.
- Android `ffi`: `pub struct AndroidLibrary { inner: Library }` with `#[uniffi::export(async_runtime = "tokio")]`:
  - `#[uniffi::constructor] fn new() -> AndroidLibrary` (builds `CoreConfig::default()`, sets mobile timeouts to 15s API / 30s image, keeps cookie empty and system proxy, registers `ManhuaguiSource`; v1 has no login or proxy UI)
  - Private Rust test seam `fn with_library(library: Library) -> AndroidLibrary` permits fixture-backed `MockSource` tests; it is not exported through UniFFI.
  - `async fn browse_within(&self, window: UpdateWindow, start_page: i32, max_pages: i32) -> Result<Vec<ComicListItem>, SourceError>`
  - `async fn browse_latest_page(&self, page: i32) -> Result<BrowsePage, SourceError>`
  - `async fn search(&self, keyword: String, page: i32) -> Result<SearchPage, SourceError>`
  - `async fn get_comic(&self, native_id: i64) -> Result<ComicDetails, SourceError>`
  - `async fn get_chapter_pages(&self, native_id: i64, chapter_id: i64) -> Result<ChapterPagesResult, SourceError>`

- [ ] **Step 1 (fork):** failing feature-enabled compile test — `UpdateWindow` and `SourceError` compile with UniFFI attrs and retain serde round-trip behavior; RED → add optional dependency/feature and cfg_attrs → GREEN (`cargo test -p source-api --features uniffi`); `cargo test --workspace` with feature off still green; branch `uniffi-feature`, merge to fork main, tag `v0.1.1-core`, push.
- [ ] **Step 2 (android):** add ffi git dependencies to `source-api`, `source-manhuagui`, and `library`, all pinned to `v0.1.1-core`; failing Rust tests — UniFFI DTOs compile and conversions preserve user-visible fields; inject a registered `MockSource` through a non-FFI test constructor: `browse_within(Days7, 1, 10)` returns filtered items; `browse_latest_page(1)` returns `BrowsePage` metadata; search/get_comic/get_chapter_pages map through; error variants propagate. RED → implement `library.rs` + `types.rs` → GREEN (`cargo test --manifest-path ffi/Cargo.toml`). Regenerate bindings; assembleDebug green.
- [ ] **Step 3: Commit** — `feat(ffi): AndroidLibrary surface over core (fork v0.1.1-core)`.

### Task 3: Kotlin service layer + Coil ImagePage pipeline

**Files:**
- Create: `app/src/main/java/com/fishkingsin/manhuagui/core/LibraryService.kt`, `app/src/main/java/com/fishkingsin/manhuagui/core/AppError.kt`, `app/src/main/java/com/fishkingsin/manhuagui/core/PageImages.kt`, `app/src/main/java/com/fishkingsin/manhuagui/App.kt` (Application: ImageLoader + AppContainer)
- Test: `app/src/test/java/com/fishkingsin/manhuagui/core/AppErrorTest.kt`, `app/src/test/java/com/fishkingsin/manhuagui/core/PageImagesTest.kt`

**Interfaces:**
- Define `sealed interface AppError` with `userMessage: String` and data classes `Network`, `RateLimited`, `ParseFailed`, `NotFound`, `Cancelled`, and `Unknown`. Each data class carries a `detail: String`.
- `SourceError.toAppError()` maps each variant to a fixed English message. Use "Couldn't reach the site", "The site is rate-limiting. Try later.", "The site format changed. Update the app.", "Not found.", or "Cancelled." Raw site error text must not appear in UI copy.
- `LibraryService` wraps `AndroidLibrary` calls in `withContext(io)` and returns `Result<T>`.
- Its suspend methods are `browseWithin(window, startPage, maxPages): Result<List<ComicListItem>>`, `browseLatestPage(page): Result<BrowsePage>`, `search(keyword, page): Result<SearchPage>`, `getComic(nativeId): Result<ComicDetails>`, and `getChapterPages(nativeId, chapterId): Result<ChapterPagesResult>`.
- `object PageImages`: `fun modelFor(page: ImagePage): ImageRequest.Builder.() -> Unit` applying all `ImagePage.headers` per request and `memoryCacheKey(page.url)`; Coil 3 `ImageLoader` built in `App` with `diskCache` 512MB, `crossfade(false)`.
- Consumes: generated `uniffi.manhuagui_ffi.*` types.

- [ ] **Step 1: Failing tests** — AppErrorTest: each SourceError variant → expected English userMessage (fake the generated types by constructing them — generated classes are constructible); PageImagesTest: ImagePage with Referer header → HttpHeaders contains it; url key stable.
- [ ] **Step 2: RED → implement → GREEN**; `./gradlew testDebugUnitTest` green.
- [ ] **Step 3: Commit** — `feat: service layer + page image pipeline`.

### Task 4: Latest screen (window-filtered browse)

**Files:**
- Create: `app/src/main/java/com/fishkingsin/manhuagui/ui/latest/LatestViewModel.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/latest/LatestScreen.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/comic/ComicCard.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/AppNav.kt`
- Test: `app/src/test/java/com/fishkingsin/manhuagui/ui/latest/LatestViewModelTest.kt`

**Interfaces:**
- `LatestViewModel(service)` — state: `data class LatestUiState(window: UpdateWindow = Days7, loading: Boolean, items: List<ComicListItem>, error: AppError?, nextPage: Int, totalPages: Int, endReached: Boolean)`; `refresh()` (guarded by `loading` — Review Focus 1), `setWindow(w)`, `loadMore()` (All mode only: appends `browse_latest_page(nextPage)` using the returned page metadata; Days modes load once via `browse_within(window, 1, 10)`).
- Nav routes: `"latest"`, `"search"`, `"comic/{nativeId}"`, `"reader/{nativeId}/{chapterId}"`.
- `ComicCard`: consumes `ComicListItem`; cover (Coil, no crossfade), title (2-line ellipsis), status_text + optional update_text chip; no elevation; 1dp border.

- [ ] **Step 1: Failing ViewModel tests** — fake `LibraryService`: setWindow(Days30) → loading then items; refresh while loading = no second service call; loadMore in All appends the next `BrowsePage.items`, advances `nextPage`, and sets `endReached` when `page >= totalPages`; error → AppError surfaced, items cleared. RED → implement → GREEN.
- [ ] **Step 2: Screen + nav wired**; assembleDebug; manual emulator smoke (window chips switch, cards scroll).
- [ ] **Step 3: Commit** — `feat: latest screen with 7/15/30-day windows`.

### Task 5: Search + Detail screens

**Files:**
- Create: `app/src/main/java/com/fishkingsin/manhuagui/ui/search/SearchViewModel.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/search/SearchScreen.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/detail/DetailViewModel.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/detail/DetailScreen.kt`
- Test: `app/src/test/java/com/fishkingsin/manhuagui/ui/search/SearchViewModelTest.kt` and `app/src/test/java/com/fishkingsin/manhuagui/ui/detail/DetailViewModelTest.kt`

**Interfaces:**
- SearchViewModel: `query`, `results: SearchPage?`, `loading`, `error`; `submit()` → `service.search(query, 1)`; `SearchPage.items` reuse the shared `ComicCard` UI → comic route.
- DetailViewModel(nativeId): `comic: ComicDetails?`, `chapters: List<ChapterItem>` displayed newest-first (reverse the adapter's flattened/site order), `loading/error`; tap chapter → reader route. No bookmark button yet (phase 2).

- [ ] **Step 1: Failing tests** (fake service; newest-first ordering assertion; error paths) → RED → implement → GREEN.
- [ ] **Step 2: Screens + nav**; assembleDebug; emulator smoke.
- [ ] **Step 3: Commit** — `feat: search and comic detail screens`.

### Task 6: Reader screen (streaming, RTL, tap zones, zoom)

**Files:**
- Create: `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderState.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderViewModel.kt`, `app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderScreen.kt`
- Test: `app/src/test/java/com/fishkingsin/manhuagui/ui/reader/ReaderStateTest.kt` and `app/src/test/java/com/fishkingsin/manhuagui/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- `ReaderState` (pure): `pageIndex`, `zoom: Float (1f..4f)`, `offset: Offset`; `fun onPagerPage(i)`: resets zoom/offset (Review Focus 4); `fun tapZoneAction(x: Float, width: Float, rtl: Boolean): TapAction` where `TapAction = PrevPage | NextPage | ToggleChrome` (Review Focus 3: left 40% / center 20% / right 40%, mirrored in RTL).
- ReaderViewModel(nativeId, chapterId): loads `ChapterPagesResult`; exposes `ImagePage`s, index, chromeVisible, error+retry; `turnPage(delta)` clamps and emits `ReaderEffect.OpenChapter(id)` at chapter ends using `prev_chapter_id`/`next_chapter_id`. `ReaderScreen` collects effects and navigates to the new reader route; ViewModel has no NavController dependency.
- ReaderScreen: `HorizontalPager` (`LayoutDirection.Rtl`), `AsyncImage(page.url)` full-res (no size constraint — zoom pipeline), pinch (`detectTransformGestures`: scale 1–4, pan clamped) + double-tap toggles 1f/2f (instant, no animation), tap-zone overlay `Modifier.pointerInput` calling `tapZoneAction`, chrome = minimal top bar (chapter title, page i/n, back), `FLAG_KEEP_SCREEN_ON` while resumed, system back works.
- Page model built via `PageImages.modelFor(imagePage: ImagePage)`.

- [ ] **Step 1: Failing tests** — ReaderStateTest (tap-zone boundaries incl. mirrored RTL, zoom reset on page change, clamp); ReaderViewModelTest (fake service: load ok, load fail → Error+retry clears error; 0 pages → error state — Review Focus 5; last-page turn emits OpenChapter when next id exists). RED → implement → GREEN.
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
