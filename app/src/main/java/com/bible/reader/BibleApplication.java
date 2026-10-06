package com.bible.reader;

import android.app.Application;

public class BibleApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Enable e-ink partial update mode globally (BOOX devices)
        EinkHelper.enableGlobalPartialUpdate();
    }
}
