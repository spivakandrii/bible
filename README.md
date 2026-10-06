# Bible Reader for E-Ink

Lightweight Android Bible reader optimized for e-ink devices (ONYX BOOX Darwin 3 and similar).

Zero dependencies. Pure Android SDK. Instant launch.

## Features

- **Continuous reading** — chapters flow seamlessly into each other, no page boundaries
- **Volume key navigation** — Volume Up/Down turn pages instantly, no animation: a 2/3-screen jump in single view, a verse-aligned page in split view
- **Split screen in step** — two translations stacked; page turns start both panels at the same verse and the longer text sets the pace so nothing is skipped; the other panel also follows navigation, touch scrolls and translation switches
- **Real-verse matching** — panels, bookmarks and chapter jumps are matched by the actual verse, not the printed number, across Hebrew/English chapter breaks, Septuagint psalm numbering and numbered psalm titles (see below)
- **Bookmark** — long-press a verse and tap ★ to save it; tap ★ with nothing selected to jump back (single slot, overwritten on save)
- **Inline translation switching** — tap translation name, pick from list, stays on the same verse
- **Book & chapter grid** — tap reference to pick book (6-col grid) then chapter (adaptive grid), full screen
- **Prev/Next navigation** — arrow buttons for quick chapter browsing (crosses book boundaries)
- **Infinite scroll** — lazy loading forward and backward, from Genesis to Revelation
- **2800+ downloadable Bibles** in 900+ languages via MyBible module repository
- **State persistence** — remembers modules, chapter, scroll position, split mode and bookmark across launches
- **Ultra-fast** — ~33 MB APK (all of it the bundled Bibles), direct SQLite access, no frameworks

## E-Ink Optimization (BOOX)

The app uses ONYX BOOX `EpdController` API via reflection for flicker-free rendering:

- **Global GU mode** — partial screen updates (similar to SNOW Field) enabled at app startup via `setSystemUpdateModeAndScheme(GU, QUEUE_AND_MERGE)`. Eliminates full black-to-white screen refresh.
- **GU_FAST for page turns** — even faster partial update mode activated during volume key page jumps
- **No animations** — all window animations disabled, dialog dimming removed
- **Inline UI** — translation picker, book/chapter grids render inside the same view (no new windows = no extra screen refresh)
- **Instant jumps** — `setSelectionFromTop()` instead of `smoothScrollBy()` for single-frame page turns
- **Nothing expensive on scroll** — verse text is parsed once and cached, book metadata is cached per module, so scrolling and paging never touch SQLite or the HTML parser

Falls back gracefully on non-BOOX devices (reflection failures are silently ignored).

## Bundled Translations

| Module | Translation | Language | Numbering |
|--------|-------------|----------|-----------|
| UBIO'88 | Біблія в пер. Івана Огієнка, 1988 | Українська | Hebrew verses, Septuagint psalm grouping |
| CUV'23 | БІБЛІЯ Сучасний переклад (УБТ, 2020-2023) | Українська | Hebrew |
| KJV+ | King James Version with Strong's numbers | English | English |
| BDC'24 | Biblia Dumitru Cornilescu (ediția centenară, 2024) | Română | English |

## One verse, several numbers

Translations do not agree on chapter and verse numbers:

- Hebrew vs English chapter breaks in about 30 Old Testament books (Joel has 4 chapters or 3, Nahum 2:1 is 1:15, Malachi 3:19 is 4:1, Jonah 2:1 is 1:17 ...)
- Septuagint psalm numbering: Psalm 9 holds Masoretic 9 and 10, 113 holds 114 and 115, 116 and 147 are split in two
- Psalm titles counted as verse 1 (and sometimes 1-2) or left unnumbered
- A dozen verses split or merged inside a chapter (1 Samuel 20:42/21:1, 1 Kings 22:43-44, Nehemiah 7:68-69, Isaiah 63:19/64:1, Acts 19:40-41, 2 Corinthians 13:12-13, Revelation 12:18/13:1 ...)

`VerseMapper` converts a reference between any two modules using only their per-chapter verse counts plus two small tables (Masoretic psalm sizes and the in-chapter splits). A moved chapter boundary is recognised as two adjacent chapters whose counts differ by the same amount in opposite directions; inside such a pair the verse ordinal is exact. No per-translation configuration is needed, so downloaded modules work too.

## Architecture

```
ReaderActivity (LAUNCHER)
  ├── ReaderPanel ×2        — one translation each; panel 2 is created on the first split
  │     ├── Continuous list — ListView of chapter headers + verses, lazy-loaded both ways
  │     ├── Toolbar         — translation, reference, ◀ ▶, ★ bookmark, split toggle
  │     ├── Inline pickers  — translation list, book grid (6 cols), chapter grid (adaptive cols)
  │     └── Selection       — long-press a verse; ★ saves it as the bookmark
  ├── Split sync            — verse-aligned page turns, follow on scroll/navigation, all via VerseMapper
  └── State                 — modules, chapter, scroll position, split, bookmark (SharedPreferences)

DownloadLanguagesActivity → DownloadModulesActivity  — browse & download modules

DatabaseHelper     — SQLite access (MyBible format); caches books and chapter sizes per module
VerseMapper        — converts a verse reference between two modules' numbering systems
TextCleaner        — strips MyBible markup (<S>, <f>, <pb/>); skips Html.fromHtml when no tags remain
RegistryManager    — download/cache module catalog from 5 mirrors
ModuleDownloader   — download & extract .zip modules
EinkHelper         — BOOX EpdController via reflection (graceful fallback)
```

## Data Format

Uses [MyBible](https://mybible.zone/) SQLite3 modules:

| Table | Columns | Purpose |
|-------|---------|---------|
| `books` | `book_number`, `short_name`, `long_name` | Book metadata |
| `verses` | `book_number`, `chapter`, `verse`, `text` | Scripture text (HTML) |
| `info` | `name`, `value` | Module metadata (language, description) |

The optional `.SQLite3.search` index files are not bundled: the app has no full-text search.

## Build

Requirements: Android SDK, JDK 17+

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `./gradlew clean assembleDebug` to measure APK size: incremental packaging leaves dead space after assets are removed.

## Testing

- **On the device** — see [CLAUDE.md](CLAUDE.md) for the build → USB → verify → commit cycle, the checklist, and an adb cheat sheet for Android 4.2 (screenshots, taps, long press through the monkey server).
- **VerseMapper on the JVM** — `tools/versemapper-test/run.sh` maps every verse of every bundled module pair there and back and checks fixed expectations for each numbering hotspot.

## Tech Stack

- **Language**: Java 8
- **Min SDK**: 17 (Android 4.2)
- **Target SDK**: 34
- **Build**: Gradle 8.9 + AGP 8.5.2
- **UI**: native `ListView`, `GridView`, `Activity`
- **DB**: `android.database.sqlite`
- **Dependencies**: none (zero external libraries)
