package com.bible.reader;

import android.app.Application;

public class BibleApplication extends Application {

    private static DatabaseHelper dbHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new DatabaseHelper(this);
    }

    public static DatabaseHelper getDb() {
        return dbHelper;
    }
}
