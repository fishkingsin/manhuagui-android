# BOOX Leaf 5 and Leaf 5C device checklist

Run each check on both devices when they are available.

## Install the debug APK

1. Build the debug APK from the repository root.
2. Connect the BOOX device with USB debugging enabled.
3. Install or replace the app:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. Open Manhuagui and confirm that the Latest screen appears.

## Verify browse

1. Select the `7d` update window and confirm that the list refreshes.
2. Select the `15d` update window and confirm that the list refreshes.
3. Select the `30d` update window and confirm that the list refreshes.
4. Select `All` and tap **Load more**. Confirm that another page loads while more results remain.

## Verify search and comic details

1. Open Search and enter a comic title.
2. Submit the search from the keyboard and from the Search button.
3. Open a result and confirm that its title, cover, details, and chapters appear.
4. Confirm that chapter groups sort by descending group size, then by ascending group name. Confirm that chapters within each group sort by descending order.

## Verify the reader

1. Open a chapter and confirm that pages follow right-to-left reading order.
2. Tap the left page zone. Confirm that the reader advances one page.
3. Tap the center zone. Confirm that the reader controls appear or disappear.
4. Tap the right page zone. Confirm that the reader returns one page.
5. Double-tap a page. Confirm that zoom changes from 1x to 2x.
6. Double-tap again. Confirm that zoom returns to 1x.
7. Pinch to zoom and drag the page. Confirm that zoom stays within its limits and panning stops at the image edges.

## Observe e-ink contrast and ghosting

1. Set the device to light mode. Confirm that screens use black content on white backgrounds.
2. Set the device to dark mode. Confirm that screens use white content on black backgrounds.
3. Tap buttons, cards, and update windows. Confirm that taps produce no ripple or shaded animation.
4. Navigate between screens. Confirm that route changes have no transition animation.
5. Record any ghosting or contrast issues on each device.

## Capture physical keycodes

Start the log capture while the reader is open:

```sh
adb logcat -s ReaderKey:I
```

Press each BOOX page-turn key and volume key. Record the logged action and keycode values. This log is informational. Physical key-to-page bindings are out of scope for Phase 1.

## Check memory with a long chapter

Open a long chapter and move through its pages. Run this command while the reader is open:

```sh
adb shell dumpsys meminfo com.fishkingsin.manhuagui
```

Record the memory values and check for sustained growth as you read.

## Check keep-screen-on behavior

1. Open a chapter and leave the reader resumed past the normal display timeout. Confirm that the screen stays on.
2. Press Home or switch to another app to pause Manhuagui.
3. Wait past the normal display timeout. Confirm that the paused reader no longer keeps the screen on.

## Verify bookmarks and update checks

1. Open a comic, add a bookmark, and return to Latest.
2. Open **Bookmarks**. Confirm that the comic title, cover, latest chapter, and any update badge appear.
3. Tap **Check updates**. Confirm that the button disables while the check runs and that its result appears when the check ends.
4. Remove the bookmark. Confirm that it disappears from the list.
5. Open **Bookmarks** with no saved comics. Confirm that **No bookmarks yet.** appears.
6. On Android 13 or later, confirm that Manhuagui requests notification permission when it starts. Allow notifications, then check for updates after a bookmarked comic gains chapters.
7. Tap an update notification. Confirm that Manhuagui opens the Bookmarks screen.
8. With a bookmarked comic that gains chapters, leave network access available and allow the six-hour background check to run. Confirm that Manhuagui posts an update notification.
9. If the battery hint appears, tap **Battery settings**. Confirm that Android opens the battery-optimization settings page.
10. Confirm that screens use the device's monochrome theme, buttons have 48dp tap targets, and route changes have no transition animation.

Real BOOX Leaf 5 and Leaf 5C verification is pending.
