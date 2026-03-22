# Bible Reader for E-Ink

Lightweight Android Bible reader optimized for e-ink devices.

Built with pure Android SDK (no AndroidX/AppCompat), targeting API 17+ (Android 4.2).

## Features

- **4 bundled translations** — UBIO'88 (Ukrainian), BDC'24 (Ukrainian), KJV+ (English), CUV'23 (Chinese)
- **2800+ downloadable Bibles** in 900+ languages via MyBible module repository
- **Book → Chapter → Verse** navigation
- **E-ink optimized UI** — no animations, high contrast B&W theme, large fonts, hardware acceleration disabled
- **Offline-first** — bundled translations work without internet, downloaded modules cached locally
- **Ultra-fast** — minimal memory footprint, SQLite direct access, no heavy frameworks

## Screenshots

*Coming soon*

## Architecture

```
TranslationActivity          — pick a translation or download new ones
  ├── BookListActivity       — list of books (Genesis, Exodus, ...)
  │     └── ChapterListActivity  — grid of chapter numbers
  │           └── VerseActivity  — full chapter text with verse numbers
  ├── DownloadLanguagesActivity  — browse languages (sorted by module count)
  │     └── DownloadModulesActivity  — download a Bible module
  └── DatabaseHelper         — SQLite access (MyBible format)
      RegistryManager        — download/cache module catalog
      ModuleDownloader       — download & extract .zip modules
      TextCleaner            — strip MyBible HTML markup
```

## Data format

Uses [MyBible](https://mybible.zone/) SQLite3 format:

| Table | Columns | Purpose |
|-------|---------|---------|
| `books` | `book_number`, `short_name`, `long_name` | Book metadata |
| `verses` | `book_number`, `chapter`, `verse`, `text` | Scripture text (HTML) |
| `info` | `name`, `value` | Module metadata (language, description) |

## Build

Requirements: Android SDK, JDK 17+

```bash
# Set JAVA_HOME to Android Studio's bundled JBR (or any JDK 17+)
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"

# Build debug APK
./gradlew assembleDebug

# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tech stack

- **Language**: Java 8
- **Min SDK**: 17 (Android 4.2)
- **Target SDK**: 34
- **Build**: Gradle 8.9 + AGP 8.5.2
- **UI**: native `ListView`, `GridView`, `Activity`
- **DB**: `android.database.sqlite`
- **Dependencies**: none (zero external libraries)

## License

MIT
