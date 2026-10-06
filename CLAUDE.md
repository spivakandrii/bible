# Bible E-Ink Reader — development workflow

Android Bible reader for e-ink devices (ONYX BOOX Darwin 3). Pure Java, no AppCompat/AndroidX,
minSdk 17 (Android 4.2). Priorities: speed on old hardware and as few screen refreshes as possible.
Feature list, architecture and the numbering problem are described in README.md.

## Feature cycle

1. **Change the code.** Small, focused change. No new dependencies.
2. **Build.**
   ```bash
   JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug
   ```
   If `VerseMapper` changed, also run `tools/versemapper-test/run.sh` before deploying.
3. **Deploy over USB.**
   ```bash
   ~/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. **Launch and verify on the device** (checklist and adb cheat sheet below): watch logcat,
   take screenshots, drive the UI with adb taps and key events.
5. **If everything is OK, commit.** One logical commit per change, English message, subject line
   under ~70 characters, body says what and why. Never commit `.claude/settings.local.json`.
   Push only when asked.
6. If something is off, fix it and go back to step 2. Never commit unverified changes.

## Device checklist

Minimum for any change:
- App starts, `am start -W` reports TotalTime (~500 ms is normal), no `AndroidRuntime:E` in logcat.
- Volume Down / Volume Up turn pages.
- In split mode both panels start the new page at the same verse after a page turn, and the
  second panel follows after navigation, after a touch scroll stops, and after a translation
  switch. With different translations check a numbering hotspot: CUV Nahum 2:1 must sit next
  to KJV Nahum 1:15, CUV Psalm 10:1 next to UBIO Psalm 9:22.

Additionally, when the relevant area was touched:
- ◀ ▶ buttons move between chapters, including across book boundaries.
- Book grid and chapter grid open full screen; picking navigates to the right place.
- Translation list opens, switching works, the current one is marked.
- Long-press a verse: row turns gray, ★ inverts. ★ saves the bookmark (★ marker on the verse);
  ★ with nothing selected jumps to it in both panels; Back clears the selection.
- Turn split off, press HOME, restart: single panel; "+" creates the second panel and syncs it.
- Reading position is restored after a restart.

After testing, put the device back into the state it was in (split, translation, chapter).

## Driving the device with adb

Device: BOOX Darwin 3, Android 4.2, logical screen 758×1024 portrait, 240 dpi.
All commands run in Git Bash with `ADB=~/AppData/Local/Android/Sdk/platform-tools/adb.exe`.

- Start with `export MSYS_NO_PATHCONV=1`, otherwise Git Bash rewrites `/sdcard/...` into a Windows path.
- `adb exec-out` and `wm size` do not exist on Android 4.2. Screenshot:
  ```bash
  adb shell screencap -p /sdcard/shot.png && adb pull /sdcard/shot.png ./shot.png && adb shell rm /sdcard/shot.png
  ```
  The PNG comes out rotated 90°: logical point (x, y) = (758 − py, px) for image pixel (px, py).
- Timed launch: `adb shell am start -W -n com.bible.reader/.ReaderActivity`.
- Errors: `adb logcat -c` before the test, then `adb logcat -d -s AndroidRuntime:E`.
- Keys: `adb shell input keyevent 25` (Volume Down), `24` (Volume Up), `4` (Back), `3` (HOME).
- Taps: `adb shell input tap X Y` in logical coordinates. Panel 1 toolbar: translation x≈48,
  reference x≈438, ◀ x≈568, ▶ x≈618, ★ x≈673, split x≈728. Toolbar y≈30, or y≈100 when the
  system status bar is visible. Take a screenshot before tapping.
- `adb shell` on Android 4.2 does not return the remote exit code, so a `&&` chain keeps going
  after a failed device command. Check the output, not the status.
- Long press: `input swipe` on 4.2 takes no duration and raw `sendevent` touches never reach the
  app. Use the monkey server instead: `adb shell monkey --port 1080 &`, then
  `adb forward tcp:1080 tcp:1080`, then over TCP (bash: `exec 3<>/dev/tcp/127.0.0.1/1080`) send
  `touch down X Y`, wait ~1 s, `touch up X Y`. It also takes `tap X Y`, `press KEYCODE_BACK` and
  `quit`. Each command replies `OK`.
- `am force-stop` skips `onPause`, so state is not saved. Press HOME (`keyevent 3`) first.
- Single taps are occasionally dropped on e-ink: if the screen did not change, tap again.
- If `adb devices` shows the reader as `offline`, `adb kill-server && adb start-server` usually
  brings it back; a command waiting on an offline device blocks until then.

## Build and APK size

- After removing assets or resources, Gradle's incremental packaging leaves dead space in the APK
  and the size does not drop. Only `./gradlew.bat clean assembleDebug` shows the real size.
- Debug APK is ≈33 MB, all of it the four bundled translations in `assets/modules/`.
- Do not add MyBible `.SQLite3.search` files (full-text index): the app has no search.

## Code rules

- No SQLite calls in the scroll listener or in `getView()`: book metadata is cached in
  `DatabaseHelper`, rendered verse text is cached in `ReadingItem.rendered`.
- Call `Html.fromHtml` only when tags or `&` remain after cleanup.
- No animations, dialogs or new windows: every picker is inline inside the same panel.
- E-ink modes go through `EinkHelper` only (reflection, silently no-op on non-BOOX devices).
- `GridView` cells get their height via `LayoutParams`, not `TextView.setHeight()`.
- `setSelectionFromTop` takes an offset relative to the list padding, not to the list edge.
- Never call `notifyDataSetChanged` or `setSelectionFromTop` from inside `OnScrollListener`:
  it runs during `layoutChildren` with layout requests blocked, the list stays flagged dirty and
  ignores touches until an unrelated layout. Post the work with `verseList.post()`.
- Move the verse list only through `ReaderPanel.moveTo()`: it records the pending position until
  the next layout. `getFirstVisiblePosition()` is stale in between, and a `notifyDataSetChanged`
  in that window makes the ListView snap back to the stale position. Marker and selection
  changes therefore re-bind visible rows in place (`rebindRows`) instead of notifying.
- Every cross-panel reference goes through `VerseMapper`; translations differ in chapter
  breaks, psalm grouping and numbered psalm titles. Bookmarks store the module they were taken
  in for the same reason. Verse splits that counts cannot locate are listed in
  `VerseMapper.SPLITS`. After touching VerseMapper or adding a bundled module run
  `tools/versemapper-test/run.sh`: it maps every verse of every module pair there and back on
  the JVM; only psalm titles and the listed splits may fail to round-trip.
