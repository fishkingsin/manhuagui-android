# Manhuagui Android

A minimal Android manga reader shell with a Rust core linked through UniFFI.

## Build prerequisites

Install JDK 21, Android SDK Platform 35, Android Build Tools 35.0.0, and NDK 27.2.12479018. Install Rust and the Android targets:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
```

Set the Android SDK paths before building:

```sh
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.8-zulu"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

Build the debug APK and run the Rust tests from the repository root:

```sh
./gradlew assembleDebug
cargo test --manifest-path ffi/Cargo.toml
```

Install the APK on a connected Android device with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
