#!/usr/bin/env bash
# Runs VerseMapper on the JVM against the real verse counts of the bundled modules.
# Usage: tools/versemapper-test/run.sh   (from the repo root; needs sqlite3 and a JDK)
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SQLITE3="${SQLITE3:-$HOME/AppData/Local/Android/Sdk/platform-tools/sqlite3.exe}"
JAVA_BIN="${JAVA_BIN:-C:/Program Files/Android/Android Studio/jbr/bin}"
OUT="$ROOT/build/versemapper-test"
mkdir -p "$OUT"
for f in "$ROOT"/app/src/main/assets/modules/*.SQLite3; do
  n=$(basename "$f" .SQLite3 | tr -d "'+")
  "$SQLITE3" "$f" "SELECT book_number||' '||chapter||' '||COUNT(*) FROM verses GROUP BY book_number, chapter ORDER BY book_number, chapter;" > "$OUT/counts_$n.txt"
done
"$JAVA_BIN/javac" -d "$OUT" "$ROOT/tools/versemapper-test/DatabaseHelper.java" "$ROOT/tools/versemapper-test/VerseMapperTest.java" "$ROOT/app/src/main/java/com/bible/reader/VerseMapper.java"
"$JAVA_BIN/java" -cp "$OUT" com.bible.reader.VerseMapperTest "$OUT"
