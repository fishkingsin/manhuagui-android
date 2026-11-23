#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

require_text() {
    local file="$1"
    local text="$2"
    grep -Fq -- "$text" "$file" || fail "$file is missing: $text"
}

theme="app/src/main/java/com/fishkingsin/manhuagui/ui/theme/Theme.kt"
require_text "$theme" 'background = Color.White'
require_text "$theme" 'onBackground = Color.Black'
require_text "$theme" 'surface = Color.White'
require_text "$theme" 'onSurface = Color.Black'
require_text "$theme" 'primary = Color.Black'
require_text "$theme" 'onPrimary = Color.White'
require_text "$theme" 'outline = Color.Black'
require_text "$theme" 'background = Color.Black'
require_text "$theme" 'onBackground = Color.White'
require_text "$theme" 'surface = Color.Black'
require_text "$theme" 'onSurface = Color.White'
require_text "$theme" 'primary = Color.White'
require_text "$theme" 'onPrimary = Color.Black'
require_text "$theme" 'outline = Color.White'
require_text "$theme" 'darkTheme: Boolean = isSystemInDarkTheme()'
require_text "$theme" 'CompositionLocalProvider(LocalIndication provides NoOpIndication)'

screens=(
    app/src/main/java/com/fishkingsin/manhuagui/ui/comic/ComicCard.kt
    app/src/main/java/com/fishkingsin/manhuagui/ui/latest/LatestScreen.kt
    app/src/main/java/com/fishkingsin/manhuagui/ui/search/SearchScreen.kt
    app/src/main/java/com/fishkingsin/manhuagui/ui/detail/DetailScreen.kt
    app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderScreen.kt
)
for file in "${screens[@]}"; do
    require_text "$file" 'MaterialTheme.colorScheme.background'
    require_text "$file" 'MaterialTheme.colorScheme.onBackground'
    if grep -Eq 'Color\.(White|Black)' "$file"; then
        fail "$file contains a hardcoded monochrome color"
    fi
done

nav="app/src/main/java/com/fishkingsin/manhuagui/ui/AppNav.kt"
[[ "$(grep -Fc 'enterTransition = { EnterTransition.None }' "$nav")" -eq 1 ]] || fail 'AppNav must disable enter transitions'
[[ "$(grep -Fc 'exitTransition = { ExitTransition.None }' "$nav")" -eq 1 ]] || fail 'AppNav must disable exit transitions'
[[ "$(grep -Fc 'popEnterTransition = { EnterTransition.None }' "$nav")" -eq 1 ]] || fail 'AppNav must disable pop enter transitions'
[[ "$(grep -Fc 'popExitTransition = { ExitTransition.None }' "$nav")" -eq 1 ]] || fail 'AppNav must disable pop exit transitions'

reader="app/src/main/java/com/fishkingsin/manhuagui/ui/reader/ReaderScreen.kt"
require_text "$reader" '.focusRequester(focusRequester)'
require_text "$reader" '.focusable()'
require_text "$reader" '.onKeyEvent { event ->'
require_text "$reader" 'Log.i('
require_text "$reader" 'action=${event.nativeKeyEvent.action} keyCode=${event.nativeKeyEvent.keyCode}'
require_text "$reader" 'focusRequester.requestFocus()'

detail="app/src/main/java/com/fishkingsin/manhuagui/ui/detail/DetailScreen.kt"
require_text "$detail" 'if (chapters.isEmpty())'
require_text "$detail" 'No chapters available.'

readme="README.md"
require_text "$readme" 'JDK 17 or JDK 21'
require_text "$readme" 'Android SDK Platform 35'
require_text "$readme" 'Android SDK Build Tools 35.0.0'
require_text "$readme" 'NDK 27.2.12479018'
require_text "$readme" 'JAVA_HOME'
require_text "$readme" 'ANDROID_HOME'
require_text "$readme" 'aarch64-linux-android x86_64-linux-android'
require_text "$readme" './gradlew testDebugUnitTest'
require_text "$readme" './gradlew assembleDebug'
require_text "$readme" 'cargo test --manifest-path ffi/Cargo.toml'
require_text "$readme" 'docs/device-checklist.md'
if grep -Eq '/Users/|\.sdkman|Library/Android/sdk' "$readme"; then
    fail "$readme contains a machine-specific installation path"
fi

device="docs/device-checklist.md"
require_text "$device" 'adb install -r app/build/outputs/apk/debug/app-debug.apk'
require_text "$device" '7d'
require_text "$device" '15d'
require_text "$device" '30d'
require_text "$device" 'Load more'
require_text "$device" 'Submit the search from the keyboard and from the Search button.'
require_text "$device" 'right-to-left reading order'
require_text "$device" 'light mode'
require_text "$device" 'dark mode'
require_text "$device" 'no ripple or shaded animation'
require_text "$device" 'no transition animation'
require_text "$device" 'adb logcat -s ReaderKey:I'
require_text "$device" 'adb shell dumpsys meminfo com.fishkingsin.manhuagui'
require_text "$device" 'battery-optimization prompt'
require_text "$device" 'WorkManager background checks'
require_text "$device" 'Room bookmarks'
require_text "$device" 'Real BOOX Leaf 5 and Leaf 5C hardware verification is pending.'
if grep -Eq '[()]|—' "$device"; then
    fail "$device contains parentheses or an em dash"
fi

for file in "$theme" "$nav" "${screens[@]}"; do
    if grep -Eq '^[[:space:]]*//|/\*|\*/' "$file"; then
        fail "$file contains a Kotlin comment"
    fi
done

printf 'Task 7 requirements check passed.\n'
