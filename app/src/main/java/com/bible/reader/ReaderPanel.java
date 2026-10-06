package com.bible.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-contained Bible reading panel. One panel = one translation + verse list + pickers.
 * Multiple panels can coexist for split-screen.
 */
public class ReaderPanel {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_VERSE = 1;

    /** Chapters loaded ahead on a fresh load, and per lazy-load step while scrolling. */
    private static final int INITIAL_LOOKAHEAD = 5;
    private static final int SCROLL_LOOKAHEAD = 3;

    private static final String[][] BUILTIN_MODULES = {
            {"UBIO'88.SQLite3", "UBIO'88"},
            {"BDC'24.SQLite3", "BDC'24"},
            {"KJV+.SQLite3", "KJV+"},
            {"CUV'23.SQLite3", "CUV'23"},
    };

    private final Activity activity;
    final View root;
    private final DatabaseHelper db;

    private TextView btnTranslation, btnReference, btnPrev, btnNext, btnBookmark, btnSplit;
    private Drawable btnBookmarkBg;
    private ListView verseList, translationList;
    private GridView bookGrid, chapterGrid;

    private boolean translationPickerVisible, bookPickerVisible, chapterPickerVisible;

    String currentModule;
    int currentBook;
    int currentChapter;

    private List<ReadingItem> items = new ArrayList<>();
    private ReadingAdapter adapter;
    private int lastLoadedBook = -1, lastLoadedChapter = -1;
    private int firstLoadedBook = -1, firstLoadedChapter = -1;
    private boolean loading = false;

    private OnScrollSyncListener syncListener;

    /** Packed verse keys (see verseKey); -1 = none. Selection is per panel, the bookmark is shared. */
    private int selectedKey = -1;
    private int bookmarkKey = -1;
    private boolean userScrolling;

    static class ReadingItem {
        int type, bookNumber, chapter, verse;
        String text1, text2;
        /** Verse number + parsed text, built on first display and reused by recycled views. */
        CharSequence rendered;
        ReadingItem(int type, int bookNumber, int chapter, String text1, String text2) {
            this.type = type; this.bookNumber = bookNumber; this.chapter = chapter;
            this.text1 = text1; this.text2 = text2;
            this.verse = (type == TYPE_VERSE) ? parseVerse(text1) : 0;
        }
        int key() { return verseKey(bookNumber, chapter, verse); }
    }

    public interface OnScrollSyncListener {
        void onSplitToggle();
        void onPickerOpened(ReaderPanel source);
        void onPickerClosed(ReaderPanel source);
        void onNavigated(ReaderPanel source, int bookNumber, int chapter);
        void onBookmarkButton(ReaderPanel source);
        /** The user finished a touch scroll or fling in this panel. */
        void onUserScrollIdle(ReaderPanel source);
        /** This panel now shows another translation, loaded at the same chapter number. */
        void onTranslationSwitched(ReaderPanel source);
    }

    public ReaderPanel(Activity activity, View root) {
        this.activity = activity;
        this.root = root;
        this.db = new DatabaseHelper(activity);

        btnTranslation = (TextView) root.findViewById(R.id.btn_translation);
        btnReference = (TextView) root.findViewById(R.id.btn_reference);
        btnPrev = (TextView) root.findViewById(R.id.btn_prev);
        btnNext = (TextView) root.findViewById(R.id.btn_next);
        btnSplit = (TextView) root.findViewById(R.id.btn_split);
        btnBookmark = (TextView) root.findViewById(R.id.btn_bookmark);
        btnBookmarkBg = btnBookmark.getBackground();
        verseList = (ListView) root.findViewById(R.id.verse_list);
        translationList = (ListView) root.findViewById(R.id.translation_list);
        bookGrid = (GridView) root.findViewById(R.id.book_grid);
        chapterGrid = (GridView) root.findViewById(R.id.chapter_grid);

        adapter = new ReadingAdapter();
        verseList.setAdapter(adapter);

        EinkHelper.setPartialUpdate(verseList);
        EinkHelper.setPartialUpdate(translationList);
        EinkHelper.setPartialUpdate(bookGrid);
        EinkHelper.setPartialUpdate(chapterGrid);

        verseList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                // Only touch scrolls and flings change the state; programmatic setSelectionFromTop
                // does not, so this cannot loop between the two panels.
                boolean idle = scrollState == SCROLL_STATE_IDLE;
                if (userScrolling && idle && syncListener != null) syncListener.onUserScrollIdle(ReaderPanel.this);
                userScrolling = !idle;
            }
            @Override
            public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                pendingTop = null; // called at the end of every layout pass: the list is where it should be
                if (firstVisible >= 0 && firstVisible < items.size()) {
                    ReadingItem item = items.get(firstVisible);
                    if (item.bookNumber != currentBook || item.chapter != currentChapter) {
                        currentBook = item.bookNumber;
                        currentChapter = item.chapter;
                        updateToolbar();
                    }
                }
                // Loading is posted, never done here: onScroll runs inside ListView.layoutChildren
                // with layout requests blocked, so a notifyDataSetChanged at this point leaves the
                // list flagged as changed with no layout pending, and it ignores touches until the
                // next unrelated layout (long-press stopped working right after navigation).
                if (!loading && totalCount > 0 && firstVisible + visibleCount >= totalCount - 10) {
                    loading = true;
                    verseList.post(appendRunnable);
                }
                if (!loading && firstVisible <= 5 && firstLoadedBook != -1) {
                    loading = true;
                    verseList.post(prependRunnable);
                }
            }
        });

        btnTranslation.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTranslationPicker(); }
        });
        btnReference.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showBookPicker(); }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { navigatePrev(); }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { navigateNext(); }
        });
        btnSplit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (syncListener != null) syncListener.onSplitToggle();
            }
        });
        btnBookmark.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (syncListener != null) syncListener.onBookmarkButton(ReaderPanel.this);
            }
        });
        // Long-press selects a verse (gray row); the star button then saves it as the bookmark.
        verseList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> par, View v, int p, long id) {
                if (p < 0 || p >= items.size()) return false;
                ReadingItem item = items.get(p);
                if (item.type != TYPE_VERSE) return false;
                int key = item.key(), old = selectedKey;
                selectedKey = (selectedKey == key) ? -1 : key;
                rebindRows(old, key);
                updateBookmarkButton();
                return true;
            }
        });
    }

    public void setSyncListener(OnScrollSyncListener listener) { this.syncListener = listener; }

    public void setSplitButtonText(String text) { btnSplit.setText(text); }

    DatabaseHelper db() { return db; }

    // --- Selection & bookmark ---

    static int verseKey(int book, int chapter, int verse) { return book * 1000000 + chapter * 1000 + verse; }

    static int verseKey(int[] ref) { return verseKey(ref[0], ref[1], ref[2]); }

    static int parseVerse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Verse selected by long-press as [book, chapter, verse], or null. */
    public int[] getSelectedVerse() {
        if (selectedKey == -1) return null;
        return new int[]{selectedKey / 1000000, (selectedKey / 1000) % 1000, selectedKey % 1000};
    }

    public void clearSelection() {
        if (selectedKey == -1) return;
        int old = selectedKey;
        selectedKey = -1;
        rebindRows(old, -1);
        updateBookmarkButton();
    }

    /** Marks the bookmarked verse with a star (book <= 0 clears). Re-renders only the two affected rows. */
    public void setBookmark(int book, int chapter, int verse) {
        int newKey = (book <= 0) ? -1 : verseKey(book, chapter, verse);
        if (newKey == bookmarkKey) return;
        int oldKey = bookmarkKey;
        bookmarkKey = newKey;
        for (ReadingItem it : items) {
            if (it.type == TYPE_VERSE && it.rendered != null) {
                int k = it.key();
                if (k == oldKey || k == newKey) it.rendered = null;
            }
        }
        rebindRows(oldKey, newKey);
    }

    /**
     * Re-binds the visible rows of the two verse keys in place. notifyDataSetChanged would do
     * the same job but, if it lands between a loadFrom and the next layout, the ListView
     * restores the first position of the previous list and the panel ends up on a random verse.
     */
    private void rebindRows(int keyA, int keyB) {
        int first = verseList.getFirstVisiblePosition();
        for (int i = 0; i < verseList.getChildCount(); i++) {
            int pos = first + i;
            if (pos < 0 || pos >= items.size()) continue;
            ReadingItem it = items.get(pos);
            if (it.type != TYPE_VERSE) continue;
            int k = it.key();
            if (k == keyA || k == keyB) adapter.getView(pos, verseList.getChildAt(i), verseList);
        }
    }

    /** Inverted while a verse is selected: pressing the star now saves instead of jumping. */
    private void updateBookmarkButton() {
        if (selectedKey != -1) {
            btnBookmark.setBackgroundColor(0xFF000000);
            btnBookmark.setTextColor(0xFFFFFFFF);
        } else {
            btnBookmark.setBackground(btnBookmarkBg);
            btnBookmark.setTextColor(0xFF000000);
        }
    }

    public void init(String module, int book, int chapter) {
        currentModule = module;
        currentBook = book;
        currentChapter = chapter;
        db.openModule(currentModule);
        loadFrom(currentBook, currentChapter);
    }

    // --- List position ---
    // The panel remembers where it told the list to go until the list has laid out, because
    // getFirstVisiblePosition() is stale in between and a notifyDataSetChanged in that window
    // makes the ListView snap back to that stale position.

    /** {position, offset} requested by moveTo and not yet laid out; null otherwise. */
    private int[] pendingTop;

    private void moveTo(int position, int offset) {
        pendingTop = new int[]{position, offset};
        verseList.setSelectionFromTop(position, offset);
    }

    /** The position that is, or is about to be, at the top of the list. */
    private int topPosition() {
        return pendingTop != null ? pendingTop[0] : verseList.getFirstVisiblePosition();
    }

    private int topOffset() {
        if (pendingTop != null) return pendingTop[1];
        View fc = verseList.getChildAt(0);
        return fc != null ? fc.getTop() - verseList.getPaddingTop() : 0;
    }

    /** {position, offset} to persist and hand back to restorePosition. */
    public int[] getTopPosition() { return new int[]{topPosition(), topOffset()}; }

    public void restorePosition(int position, int offset) {
        if (position >= 0 && position < items.size()) moveTo(position, offset);
    }

    // --- Continuous reading ---

    public void loadFrom(int bookNumber, int chapter) {
        items.clear();
        selectedKey = -1;
        updateBookmarkButton();
        lastLoadedBook = -1; lastLoadedChapter = -1;
        firstLoadedBook = bookNumber; firstLoadedChapter = chapter;
        appendChapter(bookNumber, chapter);
        appendFollowingChapters(INITIAL_LOOKAHEAD);
        adapter.notifyDataSetChanged();
        moveTo(0, 0);
        currentBook = bookNumber; currentChapter = chapter;
        updateToolbar();
    }

    private void appendChapter(int bookNumber, int chapter) {
        String bookName = db.getBookName(bookNumber);
        items.add(new ReadingItem(TYPE_HEADER, bookNumber, chapter, bookName + " " + chapter, null));
        List<String[]> verses = db.getVerses(bookNumber, chapter);
        for (String[] v : verses) items.add(new ReadingItem(TYPE_VERSE, bookNumber, chapter, v[0], v[1]));
        lastLoadedBook = bookNumber; lastLoadedChapter = chapter;
    }

    /** Appends up to n chapters after the last loaded one. Does not notify the adapter. */
    private int appendFollowingChapters(int n) {
        int added = 0;
        while (added < n && lastLoadedBook != -1) {
            int nextBook = lastLoadedBook, nextChapter = lastLoadedChapter + 1;
            if (nextChapter > db.getChapterCount(lastLoadedBook)) {
                nextBook = db.getNextBook(lastLoadedBook);
                if (nextBook == -1) break;
                nextChapter = 1;
            }
            appendChapter(nextBook, nextChapter);
            added++;
        }
        return added;
    }

    /** Scroll-triggered lazy load forward: several chapters, one adapter notification, one layout pass. */
    private final Runnable appendRunnable = new Runnable() {
        @Override public void run() {
            if (appendFollowingChapters(SCROLL_LOOKAHEAD) > 0) {
                adapter.notifyDataSetChanged();
                // notify makes the list re-sync to its stale first position; re-assert a pending move
                if (pendingTop != null) verseList.setSelectionFromTop(pendingTop[0], pendingTop[1]);
            }
            loading = false;
        }
    };

    /** Scroll-triggered lazy load backward; keeps the top row exactly where it is (or is going). */
    private final Runnable prependRunnable = new Runnable() {
        @Override public void run() {
            int first = topPosition(), offset = topOffset();
            int added = prependPreviousChapter();
            if (added > 0) moveTo(first + added, offset);
            loading = false;
        }
    };

    private int prependPreviousChapter() {
        if (firstLoadedBook == -1) return 0;
        int prevBook = firstLoadedBook, prevChapter = firstLoadedChapter - 1;
        if (prevChapter < 1) {
            prevBook = db.getPrevBook(firstLoadedBook);
            if (prevBook == -1) return 0;
            prevChapter = db.getChapterCount(prevBook);
        }
        String bookName = db.getBookName(prevBook);
        List<String[]> verses = db.getVerses(prevBook, prevChapter);
        List<ReadingItem> newItems = new ArrayList<>();
        newItems.add(new ReadingItem(TYPE_HEADER, prevBook, prevChapter, bookName + " " + prevChapter, null));
        for (String[] v : verses) newItems.add(new ReadingItem(TYPE_VERSE, prevBook, prevChapter, v[0], v[1]));
        items.addAll(0, newItems);
        firstLoadedBook = prevBook; firstLoadedChapter = prevChapter;
        adapter.notifyDataSetChanged();
        return newItems.size();
    }

    // --- Page turn ---

    public void pageDown() {
        EinkHelper.setPageTurnMode();
        int firstVisible = verseList.getFirstVisiblePosition();
        int listHeight = verseList.getHeight();
        int targetScroll = listHeight * 2 / 3;
        int accumulated = 0;
        View firstChild = verseList.getChildAt(0);
        accumulated = -(firstChild != null ? firstChild.getTop() : 0);
        int childCount = verseList.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = verseList.getChildAt(i);
            if (child == null) continue;
            accumulated += child.getHeight();
            if (accumulated >= targetScroll) {
                moveTo(firstVisible + i, -(accumulated - targetScroll));
                return;
            }
        }
        int avgH = listHeight / Math.max(1, childCount);
        int skip = targetScroll / Math.max(1, avgH);
        moveTo(Math.min(firstVisible + skip, items.size() - 1), 0);
    }

    public void pageUp() {
        EinkHelper.setPageTurnMode();
        int firstVisible = verseList.getFirstVisiblePosition();
        View firstChild = verseList.getChildAt(0);
        int firstOffset = (firstChild != null) ? firstChild.getTop() : 0;
        int listHeight = verseList.getHeight();
        int targetScroll = listHeight * 2 / 3;
        int childCount = verseList.getChildCount();
        int totalH = 0;
        for (int i = 0; i < childCount; i++) { View c = verseList.getChildAt(i); if (c != null) totalH += c.getHeight(); }
        int avgH = totalH / Math.max(1, childCount);
        int skip = targetScroll / Math.max(1, avgH);
        int newPos = Math.max(0, firstVisible - skip);
        moveTo(newPos, firstOffset + targetScroll - (skip * avgH));
    }

    // --- Sync: scroll to a specific verse ---

    public void syncToVerse(int[] ref) { syncToVerse(ref[0], ref[1], ref[2]); }

    public void syncToVerse(int bookNumber, int chapter, int verse) {
        if (scrollToVerse(bookNumber, chapter, verse)) return;
        // Chapter not loaded: reload from it and try again
        loadFrom(bookNumber, chapter);
        scrollToVerse(bookNumber, chapter, verse);
    }

    /**
     * Puts the verse at the top. A verse number missing from this translation falls back to the
     * next one in the chapter, or the last one; returns false only if the chapter is not loaded.
     */
    private boolean scrollToVerse(int bookNumber, int chapter, int verse) {
        int next = -1, last = -1;
        for (int i = 0; i < items.size(); i++) {
            ReadingItem it = items.get(i);
            if (it.type != TYPE_VERSE || it.bookNumber != bookNumber || it.chapter != chapter) continue;
            if (it.verse == verse) { moveTo(i, 0); return true; }
            if (it.verse > verse && next == -1) next = i;
            last = i;
        }
        int pos = next != -1 ? next : last;
        if (pos == -1) return false;
        moveTo(pos, 0);
        return true;
    }

    // --- Visible verses and verse arithmetic (split-screen paging) ---

    /** First verse whose row is at least partly visible, as {book, chapter, verse}; null if none. */
    public int[] getFirstVisibleVerse() {
        int pos = topPosition();
        for (int i = pos; i < Math.min(pos + 5, items.size()); i++) {
            ReadingItem item = items.get(i);
            if (item.type == TYPE_VERSE) return ref(item);
        }
        return null;
    }

    /** Last verse whose row lies entirely inside the list; null if no verse row fits. */
    public int[] getLastFullyVisibleVerse() {
        int first = verseList.getFirstVisiblePosition();
        for (int i = verseList.getChildCount() - 1; i >= 0; i--) {
            if (!rowFullyVisible(verseList.getChildAt(i))) continue;
            int pos = first + i;
            if (pos < 0 || pos >= items.size()) continue;
            ReadingItem item = items.get(pos);
            if (item.type == TYPE_VERSE) return ref(item);
        }
        return null;
    }

    /** Number of verse rows lying entirely inside the list: how many verses a page holds here. */
    public int countFullyVisibleVerses() {
        int first = verseList.getFirstVisiblePosition(), n = 0;
        for (int i = 0; i < verseList.getChildCount(); i++) {
            int pos = first + i;
            if (pos >= 0 && pos < items.size() && items.get(pos).type == TYPE_VERSE
                    && rowFullyVisible(verseList.getChildAt(i))) n++;
        }
        return n;
    }

    private boolean rowFullyVisible(View row) {
        return row != null && row.getTop() >= verseList.getPaddingTop()
                && row.getBottom() <= verseList.getHeight() - verseList.getPaddingBottom();
    }

    /** The verse after ref, crossing chapter and book boundaries; null after the last verse. */
    public int[] verseAfter(int[] ref) {
        int idx = indexOfVerse(ref);
        if (idx >= 0) {
            for (int j = idx + 1; j < items.size(); j++) {
                if (items.get(j).type == TYPE_VERSE) return ref(items.get(j));
            }
        }
        // Not loaded: step by chapter sizes
        int book = ref[0], chapter = ref[1], verse = ref[2];
        if (verse < versesIn(book, chapter)) return new int[]{book, chapter, verse + 1};
        if (chapter < db.getChapterCount(book)) return new int[]{book, chapter + 1, 1};
        int next = db.getNextBook(book);
        return next == -1 ? null : new int[]{next, 1, 1};
    }

    /** The verse n positions before ref, crossing chapter and book boundaries; stops at the first verse. */
    public int[] verseBefore(int[] ref, int n) {
        int idx = indexOfVerse(ref);
        int[] cur = ref;
        if (idx >= 0) {
            for (int j = idx - 1; j >= 0 && n > 0; j--) {
                if (items.get(j).type == TYPE_VERSE) { cur = ref(items.get(j)); n--; }
            }
        }
        // Whatever is left runs past the loaded items: step by chapter sizes
        int book = cur[0], chapter = cur[1], verse = cur[2];
        while (n-- > 0) {
            if (verse > 1) { verse--; continue; }
            if (chapter > 1) { chapter--; verse = Math.max(1, versesIn(book, chapter)); continue; }
            int prev = db.getPrevBook(book);
            if (prev == -1) break;
            book = prev;
            chapter = Math.max(1, db.getChapterCount(book));
            verse = Math.max(1, versesIn(book, chapter));
        }
        return new int[]{book, chapter, verse};
    }

    private int versesIn(int book, int chapter) {
        int[] counts = db.getChapterVerseCounts(book);
        return chapter < counts.length ? counts[chapter] : 0;
    }

    private int indexOfVerse(int[] ref) {
        int key = verseKey(ref);
        for (int i = 0; i < items.size(); i++) {
            ReadingItem it = items.get(i);
            if (it.type == TYPE_VERSE && it.key() == key) return i;
        }
        return -1;
    }

    private static int[] ref(ReadingItem item) {
        return new int[]{item.bookNumber, item.chapter, item.verse > 0 ? item.verse : 1};
    }

    // --- Navigation ---

    private void navigatePrev() {
        int prevBook = currentBook, prevChapter = currentChapter - 1;
        if (prevChapter < 1) {
            prevBook = db.getPrevBook(currentBook);
            if (prevBook == -1) return;
            prevChapter = db.getChapterCount(prevBook);
        }
        loadFrom(prevBook, prevChapter);
        if (syncListener != null) syncListener.onNavigated(this, currentBook, currentChapter);
    }

    private void navigateNext() {
        int nextBook = currentBook, nextChapter = currentChapter + 1;
        if (nextChapter > db.getChapterCount(currentBook)) {
            nextBook = db.getNextBook(currentBook);
            if (nextBook == -1) return;
            nextChapter = 1;
        }
        loadFrom(nextBook, nextChapter);
        if (syncListener != null) syncListener.onNavigated(this, currentBook, currentChapter);
    }

    private void updateToolbar() {
        btnTranslation.setText(currentModule.replace(".SQLite3", ""));
        String shortName = db.getBookShortName(currentBook);
        if (shortName.isEmpty()) shortName = db.getBookName(currentBook);
        btnReference.setText(shortName + " " + currentChapter);
    }

    // --- Translation picker ---

    private void showTranslationPicker() {
        if (translationPickerVisible) { hideAllPickers(); return; }
        final List<String[]> modules = new ArrayList<>();
        for (String[] m : BUILTIN_MODULES) modules.add(new String[]{m[0], m[1]});
        List<String> downloaded = db.listDownloadedModules();
        for (String f : downloaded) modules.add(new String[]{f, f.replace(".SQLite3", "")});
        modules.add(new String[]{"__download__", "Завантажити ще..."});

        translationList.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return modules.size(); }
            @Override public Object getItem(int p) { return modules.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View cv, ViewGroup par) {
                if (cv == null) cv = activity.getLayoutInflater().inflate(R.layout.item_translation, par, false);
                ((TextView) cv.findViewById(R.id.translation_name)).setText(modules.get(p)[1]);
                ((TextView) cv.findViewById(R.id.translation_lang)).setText(modules.get(p)[0].equals(currentModule) ? " ✓" : "");
                return cv;
            }
        });
        translationList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> par, View v, int p, long id) {
                String[] sel = modules.get(p);
                hideAllPickers();
                if ("__download__".equals(sel[0])) {
                    activity.startActivity(new Intent(activity, DownloadLanguagesActivity.class));
                    return;
                }
                switchTranslation(sel[0]);
            }
        });
        verseList.setVisibility(View.GONE); bookGrid.setVisibility(View.GONE); chapterGrid.setVisibility(View.GONE);
        translationList.setVisibility(View.VISIBLE);
        translationPickerVisible = true; bookPickerVisible = false; chapterPickerVisible = false;
        if (syncListener != null) syncListener.onPickerOpened(this);
    }

    private void switchTranslation(String moduleFile) {
        if (moduleFile.equals(currentModule)) return;
        currentModule = moduleFile;
        db.openModule(currentModule);
        if (!db.hasBook(currentBook)) {
            List<int[]> books = db.getBooks();
            if (!books.isEmpty()) currentBook = books.get(0)[0];
            currentChapter = 1;
        } else {
            int max = db.getChapterCount(currentBook);
            if (currentChapter > max) currentChapter = max;
        }
        loadFrom(currentBook, currentChapter);
        if (syncListener != null) syncListener.onTranslationSwitched(this);
    }

    // --- Book/Chapter picker ---

    public void hideAllPickers() {
        translationList.setVisibility(View.GONE); bookGrid.setVisibility(View.GONE); chapterGrid.setVisibility(View.GONE);
        verseList.setVisibility(View.VISIBLE);
        translationPickerVisible = false; bookPickerVisible = false; chapterPickerVisible = false;
        if (syncListener != null) syncListener.onPickerClosed(this);
    }

    /**
     * Cell height so that {@code rows} rows fill the picker exactly. Pickers take the whole
     * screen (the activity hides the other panel), so measure the panels' container rather
     * than this panel, which is only half-height in split mode.
     */
    private int pickerCellHeight(GridView grid, int rows) {
        float density = activity.getResources().getDisplayMetrics().density;
        View container = (View) root.getParent();
        int totalH = (container != null && container.getHeight() > 0)
                ? container.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        int toolbarH = root.findViewById(R.id.panel_toolbar).getHeight();
        if (toolbarH <= 0) toolbarH = (int) (40 * density);
        int availH = totalH - toolbarH - grid.getPaddingTop() - grid.getPaddingBottom()
                - (rows - 1) * grid.getVerticalSpacing();
        return Math.max((int) (36 * density), availH / Math.max(1, rows));
    }

    /** Chapter numbers grow with the cell (16sp at 36dp up to 32sp) so short books are not dotted with tiny digits. */
    private float chapterTextSize(int cellHeightPx) {
        float cellDp = cellHeightPx / activity.getResources().getDisplayMetrics().density;
        return Math.max(16f, Math.min(32f, cellDp / 3.5f));
    }

    /** GridView sizes cells from their LayoutParams, so TextView.setHeight() alone has no effect. */
    private static void setCellHeight(View cell, int height) {
        ViewGroup.LayoutParams lp = cell.getLayoutParams();
        if (lp != null && lp.height != height) { lp.height = height; cell.setLayoutParams(lp); }
    }

    private void showBookPicker() {
        if (bookPickerVisible) { hideAllPickers(); return; }
        final List<int[]> books = db.getBooks();
        int bRows = (books.size() + 5) / 6;
        final int bCellH = pickerCellHeight(bookGrid, bRows);

        bookGrid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return books.size(); }
            @Override public Object getItem(int p) { return books.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View cv, ViewGroup par) {
                if (cv == null) cv = activity.getLayoutInflater().inflate(R.layout.item_grid_cell, par, false);
                int bn = books.get(p)[0];
                String sn = db.getBookShortName(bn); if (sn.isEmpty()) sn = db.getBookName(bn);
                TextView tv = (TextView) cv.findViewById(R.id.cell_text);
                tv.setText(sn); setCellHeight(cv, bCellH);
                tv.setBackgroundColor(bn == currentBook ? 0xFFCCCCCC : 0xFFF0F0F0);
                return cv;
            }
        });
        bookGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> par, View v, int p, long id) { showChapterPicker(books.get(p)[0]); }
        });
        verseList.setVisibility(View.GONE); translationList.setVisibility(View.GONE); chapterGrid.setVisibility(View.GONE);
        bookGrid.setVisibility(View.VISIBLE);
        translationPickerVisible = false; bookPickerVisible = true; chapterPickerVisible = false;
        if (syncListener != null) syncListener.onPickerOpened(this);
    }

    private void showChapterPicker(final int bookNumber) {
        final int chapterCount = db.getChapterCount(bookNumber);
        int cols = chapterCount <= 20 ? 5 : chapterCount <= 50 ? 6 : chapterCount <= 80 ? 8 : 10;
        chapterGrid.setNumColumns(cols);
        int rows = (chapterCount + cols - 1) / cols;
        final int cellH = pickerCellHeight(chapterGrid, rows);
        final float textSp = chapterTextSize(cellH);

        chapterGrid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return chapterCount; }
            @Override public Object getItem(int p) { return p + 1; }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View cv, ViewGroup par) {
                if (cv == null) cv = activity.getLayoutInflater().inflate(R.layout.item_grid_cell, par, false);
                int ch = p + 1;
                TextView tv = (TextView) cv.findViewById(R.id.cell_text);
                tv.setText(String.valueOf(ch)); setCellHeight(cv, cellH); tv.setTextSize(textSp);
                tv.setBackgroundColor(bookNumber == currentBook && ch == currentChapter ? 0xFFCCCCCC : 0xFFF0F0F0);
                return cv;
            }
        });
        chapterGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> par, View v, int p, long id) {
                currentBook = bookNumber; currentChapter = p + 1;
                hideAllPickers(); loadFrom(currentBook, currentChapter);
                if (syncListener != null) syncListener.onNavigated(ReaderPanel.this, currentBook, currentChapter);
            }
        });
        bookGrid.setVisibility(View.GONE); chapterGrid.setVisibility(View.VISIBLE);
        bookPickerVisible = false; chapterPickerVisible = true;
    }

    public boolean onBackPressed() {
        if (chapterPickerVisible) { showBookPicker(); return true; }
        if (selectedKey != -1 && isVerseListVisible()) { clearSelection(); return true; }
        if (bookPickerVisible || translationPickerVisible) { hideAllPickers(); return true; }
        return false;
    }

    public boolean isVerseListVisible() { return verseList.getVisibility() == View.VISIBLE; }

    public void close() { db.close(); }

    // --- Adapter ---

    private class ReadingAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return items.get(p).type; }
        @Override
        public View getView(int position, View cv, ViewGroup parent) {
            ReadingItem item = items.get(position);
            if (item.type == TYPE_HEADER) {
                if (cv == null) cv = activity.getLayoutInflater().inflate(R.layout.item_chapter_header, parent, false);
                ((TextView) cv.findViewById(R.id.header_text)).setText(item.text1);
                return cv;
            }
            if (cv == null) cv = activity.getLayoutInflater().inflate(R.layout.item_verse, parent, false);
            // Regex cleanup + Html.fromHtml are expensive; do them once per verse, not on every bind.
            if (item.rendered == null) item.rendered = renderVerse(item);
            ((TextView) cv.findViewById(R.id.verse_text)).setText(item.rendered);
            if (selectedKey != -1 && item.key() == selectedKey) cv.setBackgroundColor(0xFFDDDDDD);
            else cv.setBackground(null);
            return cv;
        }

        private CharSequence renderVerse(ReadingItem item) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (bookmarkKey != -1 && item.key() == bookmarkKey) sb.append("★ ");
            int start = sb.length();
            sb.append(item.text1).append(' ');
            int numEnd = start + item.text1.length();
            sb.setSpan(new SuperscriptSpan(), start, numEnd, 0);
            sb.setSpan(new RelativeSizeSpan(0.7f), start, numEnd, 0);
            sb.append(TextCleaner.toSpanned(item.text2));
            return sb;
        }
    }
}
