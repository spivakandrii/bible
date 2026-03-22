package com.bible.reader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

public class ReaderActivity extends Activity implements ReaderPanel.OnScrollSyncListener {

    private static final String PREFS_NAME = "bible_reader";
    private static final String PREF_MODULE = "module";
    private static final String PREF_BOOK = "book_number";
    private static final String PREF_CHAPTER = "chapter";
    private static final String PREF_SCROLL_POS = "scroll_pos";
    private static final String PREF_SCROLL_OFFSET = "scroll_offset";
    private static final String PREF_SPLIT = "split_enabled";
    private static final String PREF_MODULE2 = "module2";

    private static final String DEFAULT_MODULE = "UBIO'88.SQLite3";
    private static final String DEFAULT_MODULE2 = "KJV+.SQLite3";
    private static final int DEFAULT_BOOK = 10;
    private static final int DEFAULT_CHAPTER = 1;

    private ReaderPanel panel1;
    private ReaderPanel panel2;
    private View panel2Root;
    private View panelDivider;
    private boolean splitMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        View panel1Root = findViewById(R.id.panel1);
        panel2Root = findViewById(R.id.panel2);
        panelDivider = findViewById(R.id.panel_divider);

        panel1 = new ReaderPanel(this, panel1Root);
        panel2 = new ReaderPanel(this, panel2Root);

        panel1.setSyncListener(this);
        panel2.setSyncListener(this);

        // Restore state
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String module1 = prefs.getString(PREF_MODULE, DEFAULT_MODULE);
        int book = prefs.getInt(PREF_BOOK, DEFAULT_BOOK);
        int chapter = prefs.getInt(PREF_CHAPTER, DEFAULT_CHAPTER);
        String module2 = prefs.getString(PREF_MODULE2, DEFAULT_MODULE2);
        splitMode = prefs.getBoolean(PREF_SPLIT, false);

        panel1.init(module1, book, chapter);
        panel2.init(module2, book, chapter);

        // Restore scroll position for panel1
        int pos = prefs.getInt(PREF_SCROLL_POS, 0);
        int offset = prefs.getInt(PREF_SCROLL_OFFSET, 0);
        // Post to ensure ListView is laid out
        final int fPos = pos, fOffset = offset;
        panel1.root.post(new Runnable() {
            @Override
            public void run() {
                // Access verse list to set position - use findById on root
                android.widget.ListView vl = (android.widget.ListView) panel1.root.findViewById(R.id.verse_list);
                if (vl != null && fPos < vl.getCount()) vl.setSelectionFromTop(fPos, fOffset);
            }
        });

        if (splitMode) showSplit(); else hideSplit();
    }

    // --- Split toggle ---

    @Override
    public void onSplitToggle() {
        if (splitMode) hideSplit(); else showSplit();
    }

    private void showSplit() {
        splitMode = true;
        panel2Root.setVisibility(View.VISIBLE);
        panelDivider.setVisibility(View.VISIBLE);
        panel1.setSplitButtonText("✕");
        panel2.setSplitButtonText("✕");
        panel2.syncToVerse(panel1.currentBook, panel1.currentChapter, "1");
    }

    private void hideSplit() {
        splitMode = false;
        panel2Root.setVisibility(View.GONE);
        panelDivider.setVisibility(View.GONE);
        panel1.setSplitButtonText("+");
    }

    // --- Picker fullscreen: hide other panel while picking ---

    @Override
    public void onPickerOpened(ReaderPanel source) {
        if (splitMode) {
            // Temporarily hide the other panel so picker gets full screen
            if (source == panel1) {
                panel2Root.setVisibility(View.GONE);
                panelDivider.setVisibility(View.GONE);
            } else {
                findViewById(R.id.panel1).setVisibility(View.GONE);
                panelDivider.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public void onPickerClosed(ReaderPanel source) {
        if (splitMode) {
            // Restore both panels
            findViewById(R.id.panel1).setVisibility(View.VISIBLE);
            panel2Root.setVisibility(View.VISIBLE);
            panelDivider.setVisibility(View.VISIBLE);
        }
    }

    // --- Navigation sync: when one panel navigates, sync the other ---

    @Override
    public void onNavigated(ReaderPanel source, int bookNumber, int chapter) {
        if (!splitMode) return;
        ReaderPanel target = (source == panel1) ? panel2 : panel1;
        target.syncToVerse(bookNumber, chapter, "1");
    }

    // --- Scroll sync ---

    @Override
    public void onVerseChanged(ReaderPanel source, int bookNumber, int chapter, String verseNum) {
        if (!splitMode) return;
        ReaderPanel target = (source == panel1) ? panel2 : panel1;
        target.syncToVerse(bookNumber, chapter, verseNum);
    }

    // --- Volume keys ---

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean isVerseVisible = panel1.isVerseListVisible();
        if (!isVerseVisible) return super.onKeyDown(keyCode, event);

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            panel1.pageDown();
            if (splitMode) panel2.pageDown();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            panel1.pageUp();
            if (splitMode) panel2.pageUp();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (panel1.isVerseListVisible()) return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // --- Back ---

    @Override
    public void onBackPressed() {
        if (panel1.onBackPressed()) return;
        if (splitMode && panel2.onBackPressed()) return;
        super.onBackPressed();
    }

    // --- State ---

    @Override
    protected void onPause() {
        super.onPause();

        // Save panel1 scroll position
        android.widget.ListView vl = (android.widget.ListView) panel1.root.findViewById(R.id.verse_list);
        int pos = vl.getFirstVisiblePosition();
        int offset = 0;
        View fc = vl.getChildAt(0);
        if (fc != null) offset = fc.getTop() - vl.getPaddingTop();

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_MODULE, panel1.currentModule)
                .putInt(PREF_BOOK, panel1.currentBook)
                .putInt(PREF_CHAPTER, panel1.currentChapter)
                .putInt(PREF_SCROLL_POS, pos)
                .putInt(PREF_SCROLL_OFFSET, offset)
                .putBoolean(PREF_SPLIT, splitMode)
                .putString(PREF_MODULE2, panel2.currentModule)
                .apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        panel1.close();
        panel2.close();
    }
}
