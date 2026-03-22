# Bible Reader for E-Ink

Lightweight Android Bible reader optimized for e-ink devices (ONYX BOOX Darwin 3 and similar).

Zero dependencies. Pure Android SDK. Instant launch.

## Features

- **Continuous reading** — chapters flow seamlessly into each other, no page boundaries
- **Volume key navigation** — Volume Up/Down for instant page turns (2/3 screen jump, no animation)
- **Inline translation switching** — tap translation name, pick from list, stays on same book/chapter
- **Book & chapter grid** — tap reference to pick book (6-col grid) then chapter (adaptive grid)
- **Prev/Next navigation** — arrow buttons for quick chapter browsing (crosses book boundaries)
- **Infinite scroll** — lazy loading forward and backward, from Genesis to Revelation
- **2800+ downloadable Bibles** in 900+ languages via MyBible module repository
- **State persistence** — remembers module, book, chapter, and scroll position across launches
- **Ultra-fast** — ~43MB APK, direct SQLite access, no frameworks

## E-Ink Optimization (BOOX)

The app uses ONYX BOOX `EpdController` API via reflection for flicker-free rendering:

- **Global GU mode** — partial screen updates (similar to SNOW Field) enabled at app startup via `setSystemUpdateModeAndScheme(GU, QUEUE_AND_MERGE)`. Eliminates full black-to-white screen refresh.
- **GU_FAST for page turns** — even faster partial update mode activated during volume key page jumps
- **No animations** — all window animations disabled, dialog dimming removed
- **Inline UI** — translation picker, book/chapter grids render inside the same view (no new windows = no extra screen refresh)
- **Instant jumps** — `setSelectionFromTop()` instead of `smoothScrollBy()` for single-frame page turns

Falls back gracefully on non-BOOX devices (reflection failures are silently ignored).

## Bundled Translations

| Module | Translation | Language |
|--------|-------------|----------|
| UBIO'88 | Біблія в пер. Івана Огієнка, 1988 | Українська |
| CUV'23 | БІБЛІЯ Сучасний переклад (УБТ, 2020-2023) | Українська |
| KJV+ | King James Version with Strong's numbers | English |
| BDC'24 | Biblia Dumitru Cornilescu (ediția centenară, 2024) | Română |

## Architecture

```
ReaderActivity (LAUNCHER)
  ├── Continuous reader   — ListView with mixed item types (headers + verses)
  │     ├── Chapter headers  — "Book Chapter" between sections
  │     ├── Verses           — superscript numbers + cleaned HTML text
  │     └── Lazy loading     — forward + backward, auto-loads on scroll
  ├── Translation picker  — inline ListView (replaces verses)
  ├── Book picker         — inline 6-column GridView
  ├── Chapter picker      — inline adaptive GridView (5-10 cols)
  └── Prev/Next + Volume  — chapter navigation + page-turn scrolling

DownloadLanguagesActivity → DownloadModulesActivity  — browse & download modules

DatabaseHelper     — SQLite access (MyBible format)
RegistryManager    — download/cache module catalog from 5 mirrors
ModuleDownloader   — download & extract .zip modules
TextCleaner        — strip MyBible HTML markup (<S>, <f>, <pb/>)
EinkHelper         — BOOX EpdController via reflection (graceful fallback)
```

## Data Format

Uses [MyBible](https://mybible.zone/) SQLite3 modules:

| Table | Columns | Purpose |
|-------|---------|---------|
| `books` | `book_number`, `short_name`, `long_name` | Book metadata |
| `verses` | `book_number`, `chapter`, `verse`, `text` | Scripture text (HTML) |
| `info` | `name`, `value` | Module metadata (language, description) |

## Build

Requirements: Android SDK, JDK 17+

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tech Stack

- **Language**: Java 8
- **Min SDK**: 17 (Android 4.2)
- **Target SDK**: 34
- **Build**: Gradle 8.9 + AGP 8.5.2
- **UI**: native `ListView`, `GridView`, `Activity`
- **DB**: `android.database.sqlite`
- **Dependencies**: none (zero external libraries)
