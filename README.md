# Manhuagui Android

A minimal Android manga reader shell with a Rust core linked through UniFFI.

## Build prerequisites

Install JDK 17 or JDK 21. Install Android SDK Platform 35 and Android SDK Build Tools 35.0.0. Install NDK 27.2.12479018.

Install Rust and add the Android targets:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
```

Set `JAVA_HOME` and `ANDROID_HOME` to the paths on your machine:

```sh
export JAVA_HOME="/path/to/your/jdk"
export ANDROID_HOME="/path/to/your/android-sdk"
```

## Run tests

Run the Android unit tests from the repository root:

```sh
./gradlew testDebugUnitTest
```

Run the Rust FFI tests:

```sh
cargo test --manifest-path ffi/Cargo.toml
```

## Build the debug APK

Build the debug APK from the repository root:

```sh
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Verify on a device

Use the [device checklist](docs/device-checklist.md) to verify browse, search, reader, bookmarks, update notifications, and e-ink behavior on BOOX Leaf 5 or Leaf 5C hardware.

## Phase 2 checks

Use the device checklist to verify bookmark creation and removal, manual and periodic background checks, and update notifications. It also covers the Android 13 notification permission prompt, routing to Bookmarks, and the battery-optimization settings link. Real BOOX Leaf 5 and Leaf 5C verification is pending.

## Phase 3 offline reading

Chapter downloads live in the app's private storage at `files/downloaded/{comicId}/{chapterId}/`. The comic and chapter directories contain metadata files and downloaded page images. Download and export actions are available from each comic's detail page. The foreground service shows download progress while a queue runs.

Export a chapter as CBZ or PDF with the Android create-document picker. Export all downloaded chapters to a folder with the Android folder picker. Android writes the exported files to the location you choose.

Settings lets you choose RTL or LTR reading, a 7-, 15-, or 30-day update window or All, a 1-, 6-, 12-, or 24-hour bookmark-check interval, offline-only mode, a prefetch count of 0, 2, 3, or 5, and a 256, 512, or 1024 MB image-cache size. The Browse tab uses the saved update window. The bookmark worker uses the saved interval. The reader prefers downloaded page images and asks before streaming an undownloaded chapter when offline-only mode is on.

Use the [device checklist](docs/device-checklist.md) to verify downloads, exports, settings, foreground progress, and offline reading on BOOX Leaf 5 or Leaf 5C hardware.
