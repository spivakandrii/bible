package com.bible.reader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.SparseArray;
import android.util.SparseIntArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DatabaseHelper {

    private static final int BUFFER_SIZE = 8192;

    private final Context context;
    private SQLiteDatabase db;
    private String currentModule;

    // Book metadata cache for the open module. The books table is ~66 rows, but it used to be
    // queried on every scroll event, every grid cell and every chapter append.
    private List<int[]> books = Collections.emptyList();
    private final SparseArray<String> longNames = new SparseArray<>();
    private final SparseArray<String> shortNames = new SparseArray<>();
    private final SparseIntArray chapterCounts = new SparseIntArray();

    public DatabaseHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public void openModule(String moduleFileName) {
        if (moduleFileName.equals(currentModule) && db != null && db.isOpen()) {
            return;
        }
        close();

        // First check if module exists in downloaded modules dir
        File downloaded = new File(ModuleDownloader.getModulesDir(context), moduleFileName);
        if (downloaded.exists()) {
            db = SQLiteDatabase.openDatabase(downloaded.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        } else {
            // Each asset module gets its own copy (not shared current.db)
            File dbFile = context.getDatabasePath("asset_" + moduleFileName);
            if (!dbFile.exists()) {
                copyFromAssets(moduleFileName, dbFile);
            }
            db = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        }
        currentModule = moduleFileName;
        loadBookCache();
    }

    private void loadBookCache() {
        List<int[]> list = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT book_number, long_name, short_name FROM books ORDER BY book_number", null);
        try {
            while (c.moveToNext()) {
                int bn = c.getInt(0);
                list.add(new int[]{bn});
                longNames.put(bn, c.isNull(1) ? "" : c.getString(1));
                shortNames.put(bn, c.isNull(2) ? "" : c.getString(2));
            }
        } finally {
            c.close();
        }
        books = Collections.unmodifiableList(list);
    }

    private void clearBookCache() {
        books = Collections.emptyList();
        longNames.clear();
        shortNames.clear();
        chapterCounts.clear();
    }

    private void copyFromAssets(String moduleFileName, File targetFile) {
        targetFile.getParentFile().mkdirs();

        try {
            InputStream in = context.getAssets().open("modules/" + moduleFileName);
            FileOutputStream out = new FileOutputStream(targetFile);
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            out.close();
            in.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy database: " + moduleFileName, e);
        }
    }

    public String getInfo(String key) {
        if (db == null) return "";
        Cursor c = db.rawQuery("SELECT value FROM info WHERE name=?", new String[]{key});
        try {
            if (c.moveToFirst()) return c.getString(0);
            return "";
        } finally {
            c.close();
        }
    }

    /** Book numbers of the open module in canonical order. Read-only, cached. */
    public List<int[]> getBooks() {
        return books;
    }

    /** Book number following the given one, or -1 if it is the last (or unknown). */
    public int getNextBook(int bookNumber) {
        for (int i = 0; i < books.size() - 1; i++) {
            if (books.get(i)[0] == bookNumber) return books.get(i + 1)[0];
        }
        return -1;
    }

    /** Book number preceding the given one, or -1 if it is the first (or unknown). */
    public int getPrevBook(int bookNumber) {
        for (int i = 1; i < books.size(); i++) {
            if (books.get(i)[0] == bookNumber) return books.get(i - 1)[0];
        }
        return -1;
    }

    public String getBookName(int bookNumber) {
        String name = longNames.get(bookNumber);
        return name != null ? name : "";
    }

    public String getBookShortName(int bookNumber) {
        String name = shortNames.get(bookNumber);
        return name != null ? name : "";
    }

    public int getChapterCount(int bookNumber) {
        int cached = chapterCounts.get(bookNumber, -1);
        if (cached >= 0) return cached;
        if (db == null) return 0;
        Cursor c = db.rawQuery(
                "SELECT MAX(chapter) FROM verses WHERE book_number=?",
                new String[]{String.valueOf(bookNumber)});
        int count = 0;
        try {
            if (c.moveToFirst()) count = c.getInt(0);
        } finally {
            c.close();
        }
        chapterCounts.put(bookNumber, count);
        return count;
    }

    public List<String[]> getVerses(int bookNumber, int chapter) {
        List<String[]> verses = new ArrayList<>();
        if (db == null) return verses;
        Cursor c = db.rawQuery(
                "SELECT verse, text FROM verses WHERE book_number=? AND chapter=? ORDER BY verse",
                new String[]{String.valueOf(bookNumber), String.valueOf(chapter)});
        try {
            while (c.moveToNext()) {
                verses.add(new String[]{c.getString(0), c.getString(1)});
            }
        } finally {
            c.close();
        }
        return verses;
    }

    /**
     * List downloaded module files (from getFilesDir/modules/).
     * Returns list of filenames like "UBIO.SQLite3".
     */
    public List<String> listDownloadedModules() {
        List<String> result = new ArrayList<>();
        File dir = ModuleDownloader.getModulesDir(context);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".SQLite3")) {
                    result.add(f.getName());
                }
            }
        }
        return result;
    }

    /**
     * Read the description from the info table of a downloaded module.
     */
    public String getModuleDescription(String moduleFileName) {
        File file = new File(ModuleDownloader.getModulesDir(context), moduleFileName);
        if (!file.exists()) return moduleFileName;
        SQLiteDatabase tempDb = null;
        try {
            tempDb = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor c = tempDb.rawQuery("SELECT value FROM info WHERE name='description'", null);
            try {
                if (c.moveToFirst()) return c.getString(0);
            } finally {
                c.close();
            }
        } catch (Exception e) {
            // ignore
        } finally {
            if (tempDb != null) tempDb.close();
        }
        return moduleFileName;
    }

    /**
     * Check if a book exists in the currently open module.
     */
    public boolean hasBook(int bookNumber) {
        return longNames.indexOfKey(bookNumber) >= 0;
    }

    public String getCurrentModule() {
        return currentModule;
    }

    public void close() {
        if (db != null && db.isOpen()) {
            db.close();
            db = null;
        }
        currentModule = null;
        clearBookCache();
    }
}
