# Bible E-Ink Reader — development workflow

Android Bible reader for e-ink devices (ONYX BOOX Darwin 3). Pure Java, no AppCompat/AndroidX,
minSdk 17 (Android 4.2). Priorities: speed on old hardware and as few screen refreshes as possible.

## Feature cycle

1. **Change the code.** Small, focused change. No new dependencies.
2. **Build.**
   ```bash
   JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug
   ```
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
- In split mode the second panel follows after a page turn and after navigation.

Additionally, when the relevant area was touched:
- ◀ ▶ buttons move between chapters, including across book boundaries.
- Book grid and chapter grid open full screen; picking navigates to the right place.
- Translation list opens, switching works, the current one is marked.
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
  reference x≈438, ◀ x≈628, ▶ x≈673, split x≈728. Toolbar y≈30, or y≈100 when the system
  status bar is visible. Take a screenshot before tapping.
- `am force-stop` skips `onPause`, so state is not saved. Press HOME (`keyevent 3`) first.
- Single taps are occasionally dropped on e-ink: if the screen did not change, tap again.

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
