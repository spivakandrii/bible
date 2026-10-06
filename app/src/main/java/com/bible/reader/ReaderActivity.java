package com.bible.reader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;

public class ReaderActivity extends Activity implements ReaderPanel.OnScrollSyncListener {

    private static final String PREFS_NAME = "bible_reader";
    private static final String PREF_MODULE = "module";
    private static final String PREF_BOOK = "book_number";
    private static final String PREF_CHAPTER = "chapter";
    private static final String PREF_SCROLL_POS = "scroll_pos";
    private static final String PREF_SCROLL_OFFSET = "scroll_offset";
    private static final String PREF_SPLIT = "split_enabled";
    private static final String PREF_MODULE2 = "module2";
    private static final String PREF_BM_BOOK = "bm_book";
    private static final String PREF_BM_CHAPTER = "bm_chapter";
    private static final String PREF_BM_VERSE = "bm_verse";
    private static final String PREF_BM_MODULE = "bm_module";

    private static final String DEFAULT_MODULE = "UBIO'88.SQLite3";
    private static final String DEFAULT_MODULE2 = "KJV+.SQLite3";
    private static final int DEFAULT_BOOK = 10;
    private static final int DEFAULT_CHAPTER = 1;

    private ReaderPanel panel1;
    /** Created on first split only: saves a second DB open and six chapter loads at launch. */
    private ReaderPanel panel2;
    /** Module for panel2 until it is created (then panel2.currentModule is authoritative). */
    private String module2;
    private View panel1Root;
    private View panel2Root;
    private View panelDivider;
    private boolean splitMode = false;

    /**
     * The single bookmark, stored in the numbering of the module it was taken in (bmModule);
     * bmBook <= 0 means none. Saved to prefs immediately on change.
     */
    private int bmBook = -1, bmChapter, bmVerse;
    private String bmModule;
    /** Opened only when the bookmark's module is in neither panel, to map its numbering. */
    private DatabaseHelper bmDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        panel1Root = findViewById(R.id.panel1);
        panel2Root = findViewById(R.id.panel2);
        panelDivider = findViewById(R.id.panel_divider);

        panel1 = new ReaderPanel(this, panel1Root);
        panel1.setSyncListener(this);

        // Restore state
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String module1 = prefs.getString(PREF_MODULE, DEFAULT_MODULE);
        int book = prefs.getInt(PREF_BOOK, DEFAULT_BOOK);
        int chapter = prefs.getInt(PREF_CHAPTER, DEFAULT_CHAPTER);
        module2 = prefs.getString(PREF_MODULE2, DEFAULT_MODULE2);
        splitMode = prefs.getBoolean(PREF_SPLIT, false);
        bmBook = prefs.getInt(PREF_BM_BOOK, -1);
        bmChapter = prefs.getInt(PREF_BM_CHAPTER, 1);
        bmVerse = prefs.getInt(PREF_BM_VERSE, 1);
        bmModule = prefs.getString(PREF_BM_MODULE, null);

        panel1.init(module1, book, chapter);
        applyBookmarkMarkers();

        // Restore scroll position for panel1
        int pos = prefs.getInt(PREF_SCROLL_POS, 0);
        int offset = prefs.getInt(PREF_SCROLL_OFFSET, 0);
        // Post to ensure ListView is laid out
        final int fPos = pos, fOffset = offset;
        panel1.root.post(new Runnable() {
            @Override
            public void run() {
                panel1.restorePosition(fPos, fOffset);
                if (splitMode) alignPanel2ToPanel1();
            }
        });

        if (splitMode) showSplit(); else hideSplit();
    }

    // --- Split toggle ---

    @Override
    public void onSplitToggle() {
        if (splitMode) hideSplit(); else showSplit();
    }

    private void ensurePanel2() {
        if (panel2 != null) return;
        panel2 = new ReaderPanel(this, panel2Root);
        panel2.setSyncListener(this);
        panel2.init(module2, panel1.currentBook, panel1.currentChapter);
        applyBookmarkMarkers();
    }

    private void showSplit() {
        splitMode = true;
        ensurePanel2();
        panel2Root.setVisibility(View.VISIBLE);
        panelDivider.setVisibility(View.VISIBLE);
        panel1.setSplitButtonText("✕");
        panel2.setSplitButtonText("✕");
        alignPanel2ToPanel1();
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
            } else {
                panel1Root.setVisibility(View.GONE);
            }
            panelDivider.setVisibility(View.GONE);
        }
    }

    @Override
    public void onPickerClosed(ReaderPanel source) {
        if (splitMode) {
            // Restore both panels
            panel1Root.setVisibility(View.VISIBLE);
            panel2Root.setVisibility(View.VISIBLE);
            panelDivider.setVisibility(View.VISIBLE);
        }
    }

    // --- Keeping the two panels on the same verse ---
    // Translations number chapters and verses differently (Hebrew vs English chapter breaks,
    // Septuagint psalms, numbered psalm titles), so every cross-panel reference goes through
    // VerseMapper and the panels agree on the real verse, not on the printed number.

    private ReaderPanel other(ReaderPanel panel) { return panel == panel1 ? panel2 : panel1; }

    /** Scrolls target to the verse ref, given in source numbering, converted to target numbering. */
    private static void syncMapped(ReaderPanel source, ReaderPanel target, int[] ref) {
        int[] mapped = VerseMapper.map(source.db(), target.db(), ref);
        if (mapped != null) target.syncToVerse(mapped);
    }

    /** Puts panel2 on the verse panel1 shows at the top. */
    private void alignPanel2ToPanel1() {
        if (panel2 == null) return;
        int[] top = panel1.getFirstVisibleVerse();
        if (top == null) top = new int[]{panel1.currentBook, panel1.currentChapter, 1};
        syncMapped(panel1, panel2, top);
    }

    /** Same, once panel1 has actually laid out whatever it was just told to show. */
    private void alignAfterLayout() {
        final ViewTreeObserver vto = panel1.root.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                ViewTreeObserver live = panel1.root.getViewTreeObserver();
                if (live.isAlive()) live.removeOnGlobalLayoutListener(this);
                alignPanel2ToPanel1();
            }
        });
    }

    @Override
    public void onNavigated(ReaderPanel source, int bookNumber, int chapter) {
        if (!splitMode) return;
        syncMapped(source, other(source), new int[]{bookNumber, chapter, 1});
    }

    @Override
    public void onTranslationSwitched(ReaderPanel source) {
        applyBookmarkMarkers(); // the star moves to this translation's numbering
        if (!splitMode) return;
        // The panel that changed translation follows the other one, which did not move
        ReaderPanel other = other(source);
        int[] top = other.getFirstVisibleVerse();
        if (top != null) syncMapped(other, source, top);
    }

    @Override
    public void onUserScrollIdle(ReaderPanel source) {
        if (!splitMode) return;
        int[] top = source.getFirstVisibleVerse();
        if (top != null) syncMapped(source, other(source), top);
    }

    // --- Page turns in split mode: both panels start the new page at the same verse ---

    private static int cmp(int[] a, int[] b) {
        return Integer.compare(ReaderPanel.verseKey(a), ReaderPanel.verseKey(b));
    }

    private int[] inPanel1Numbering(int[] ref2) {
        return ref2 == null ? null : VerseMapper.map(panel2.db(), panel1.db(), ref2);
    }

    /** True when panel2's top verse lies on panel1's current page, i.e. the panels are in step. */
    private boolean panelsOverlap(int[] first1, int[] last1) {
        int[] top2 = inPanel1Numbering(panel2.getFirstVisibleVerse());
        return top2 != null && cmp(top2, first1) >= 0 && cmp(top2, last1) <= 0;
    }

    private void splitPageDown() {
        int[] first1 = panel1.getFirstVisibleVerse();
        int[] last1 = panel1.getLastFullyVisibleVerse();
        if (first1 == null || last1 == null) {
            // A single verse taller than the panel: fall back to pixel paging
            panel1.pageDown();
            alignAfterLayout();
            return;
        }
        int[] stop = last1;
        if (panelsOverlap(first1, last1)) {
            // The translation with the longer text sets the pace, so nothing is skipped in either panel
            int[] last2 = inPanel1Numbering(panel2.getLastFullyVisibleVerse());
            if (last2 != null && cmp(last2, first1) >= 0 && cmp(last2, stop) < 0) stop = last2;
        }
        int[] next = panel1.verseAfter(stop);
        if (next == null) return;
        EinkHelper.setPageTurnMode();
        panel1.syncToVerse(next);
        syncMapped(panel1, panel2, next);
    }

    private void splitPageUp() {
        int[] first1 = panel1.getFirstVisibleVerse();
        if (first1 == null) {
            panel1.pageUp();
            alignAfterLayout();
            return;
        }
        // Go back one page of verses; the panel that fits fewer verses decides how many
        int n = panel1.countFullyVisibleVerses();
        int[] last1 = panel1.getLastFullyVisibleVerse();
        if (last1 != null && panelsOverlap(first1, last1)) n = Math.min(n, panel2.countFullyVisibleVerses());
        int[] top = panel1.verseBefore(first1, Math.max(1, n));
        EinkHelper.setPageTurnMode();
        panel1.syncToVerse(top);
        syncMapped(panel1, panel2, top);
    }

    // --- Volume keys ---

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean isVerseVisible = panel1.isVerseListVisible();
        if (!isVerseVisible) return super.onKeyDown(keyCode, event);

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (splitMode) splitPageDown(); else panel1.pageDown();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (splitMode) splitPageUp(); else panel1.pageUp();
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

    // --- Bookmark: a single slot. Star with a selected verse saves it; star without a selection jumps to it ---

    @Override
    public void onBookmarkButton(ReaderPanel source) {
        int[] sel = source.getSelectedVerse();
        if (sel != null) {
            bmBook = sel[0]; bmChapter = sel[1]; bmVerse = sel[2];
            bmModule = source.currentModule;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putInt(PREF_BM_BOOK, bmBook)
                    .putInt(PREF_BM_CHAPTER, bmChapter)
                    .putInt(PREF_BM_VERSE, bmVerse)
                    .putString(PREF_BM_MODULE, bmModule)
                    .apply();
            source.clearSelection();
            applyBookmarkMarkers();
        } else if (bmBook > 0) {
            DatabaseHelper src = bookmarkSourceDb();
            int[] ref = {bmBook, bmChapter, bmVerse};
            int[] m1 = VerseMapper.map(src, panel1.db(), ref);
            if (m1 != null) panel1.syncToVerse(m1);
            if (splitMode) {
                int[] m2 = VerseMapper.map(src, panel2.db(), ref);
                if (m2 != null) panel2.syncToVerse(m2);
            }
        }
    }

    /** DatabaseHelper holding the module the bookmark was taken in, for numbering conversion. */
    private DatabaseHelper bookmarkSourceDb() {
        if (bmModule == null || bmModule.equals(panel1.currentModule)) return panel1.db();
        if (panel2 != null && bmModule.equals(panel2.currentModule)) return panel2.db();
        if (bmDb == null) bmDb = new DatabaseHelper(this);
        if (!bmModule.equals(bmDb.getCurrentModule())) {
            try {
                bmDb.openModule(bmModule);
            } catch (RuntimeException e) {
                return panel1.db(); // module gone: use the numbers as they are
            }
        }
        return bmDb;
    }

    /** Shows the star on the bookmarked verse in each panel, converted to that panel's numbering. */
    private void applyBookmarkMarkers() {
        if (bmBook <= 0) {
            panel1.setBookmark(-1, 0, 0);
            if (panel2 != null) panel2.setBookmark(-1, 0, 0);
            return;
        }
        DatabaseHelper src = bookmarkSourceDb();
        setMarker(panel1, src);
        if (panel2 != null) setMarker(panel2, src);
    }

    private void setMarker(ReaderPanel panel, DatabaseHelper src) {
        int[] m = VerseMapper.map(src, panel.db(), bmBook, bmChapter, bmVerse);
        if (m == null) panel.setBookmark(-1, 0, 0); else panel.setBookmark(m[0], m[1], m[2]);
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
        int[] top = panel1.getTopPosition();

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_MODULE, panel1.currentModule)
                .putInt(PREF_BOOK, panel1.currentBook)
                .putInt(PREF_CHAPTER, panel1.currentChapter)
                .putInt(PREF_SCROLL_POS, top[0])
                .putInt(PREF_SCROLL_OFFSET, top[1])
                .putBoolean(PREF_SPLIT, splitMode)
                .putString(PREF_MODULE2, panel2 != null ? panel2.currentModule : module2)
                .apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        panel1.close();
        if (panel2 != null) panel2.close();
        if (bmDb != null) bmDb.close();
    }
}
