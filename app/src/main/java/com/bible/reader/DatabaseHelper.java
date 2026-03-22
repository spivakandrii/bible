package com.bible.reader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper {

    private static final int BUFFER_SIZE = 8192;

    private final Context context;
    private SQLiteDatabase db;
    private String currentModule;

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

    public List<int[]> getBooks() {
        List<int[]> books = new ArrayList<>();
        if (db == null) return books;
        Cursor c = db.rawQuery(
                "SELECT book_number FROM books ORDER BY book_number", null);
        try {
            while (c.moveToNext()) {
                books.add(new int[]{c.getInt(0)});
            }
        } finally {
            c.close();
        }
        return books;
    }

    public String getBookName(int bookNumber) {
        if (db == null) return "";
        Cursor c = db.rawQuery(
                "SELECT long_name FROM books WHERE book_number=?",
                new String[]{String.valueOf(bookNumber)});
        try {
            if (c.moveToFirst()) return c.getString(0);
            return "";
        } finally {
            c.close();
        }
    }

    public String getBookShortName(int bookNumber) {
        if (db == null) return "";
        Cursor c = db.rawQuery(
                "SELECT short_name FROM books WHERE book_number=?",
                new String[]{String.valueOf(bookNumber)});
        try {
            if (c.moveToFirst()) return c.getString(0);
            return "";
        } finally {
            c.close();
        }
    }

    public int getChapterCount(int bookNumber) {
        if (db == null) return 0;
        Cursor c = db.rawQuery(
                "SELECT MAX(chapter) FROM verses WHERE book_number=?",
                new String[]{String.valueOf(bookNumber)});
        try {
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        } finally {
            c.close();
        }
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
     * Read description from a downloaded module's info table.
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
        if (db == null) return false;
        Cursor c = db.rawQuery(
                "SELECT 1 FROM books WHERE book_number=? LIMIT 1",
                new String[]{String.valueOf(bookNumber)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
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
    }
}
