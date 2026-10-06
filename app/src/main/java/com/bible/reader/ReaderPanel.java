package com.bible.reader;

import android.app.Activity;
import android.content.Intent;
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

    private TextView btnTranslation, btnReference, btnPrev, btnNext, btnSplit;
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

    static class ReadingItem {
        int type, bookNumber, chapter;
        String text1, text2;
        /** Verse number + parsed text, built on first display and reused by recycled views. */
        CharSequence rendered;
        ReadingItem(int type, int bookNumber, int chapter, String text1, String text2) {
            this.type = type; this.bookNumber = bookNumber; this.chapter = chapter;
            this.text1 = text1; this.text2 = text2;
        }
    }

    public interface OnScrollSyncListener {
        void onSplitToggle();
        void onPickerOpened(ReaderPanel source);
        void onPickerClosed(ReaderPanel source);
        void onNavigated(ReaderPanel source, int bookNumber, int chapter);
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
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {}
            @Override
            public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                if (firstVisible >= 0 && firstVisible < items.size()) {
                    ReadingItem item = items.get(firstVisible);
                    if (item.bookNumber != currentBook || item.chapter != currentChapter) {
                        currentBook = item.bookNumber;
                        currentChapter = item.chapter;
                        updateToolbar();
                    }
                }
                if (!loading && totalCount > 0 && firstVisible + visibleCount >= totalCount - 10) {
                    appendNextChapters(SCROLL_LOOKAHEAD);
                }
                if (!loading && firstVisible <= 5 && firstLoadedBook != -1) {
                    View fc = view.getChildAt(0);
                    int offset = (fc != null) ? fc.getTop() - view.getPaddingTop() : 0;
                    int added = prependPreviousChapter();
                    if (added > 0) verseList.setSelectionFromTop(firstVisible + added, offset);
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
    }

    public void setSyncListener(OnScrollSyncListener listener) { this.syncListener = listener; }

    public void setSplitButtonText(String text) { btnSplit.setText(text); }

    public void init(String module, int book, int chapter) {
        currentModule = module;
        currentBook = book;
        currentChapter = chapter;
        db.openModule(currentModule);
        loadFrom(currentBook, currentChapter);
    }

    // --- Continuous reading ---

    public void loadFrom(int bookNumber, int chapter) {
        items.clear();
        lastLoadedBook = -1; lastLoadedChapter = -1;
        firstLoadedBook = bookNumber; firstLoadedChapter = chapter;
        appendChapter(bookNumber, chapter);
        appendFollowingChapters(INITIAL_LOOKAHEAD);
        adapter.notifyDataSetChanged();
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

    /** Scroll-triggered lazy load: several chapters, one adapter notification, one layout pass. */
    private void appendNextChapters(int n) {
        loading = true;
        if (appendFollowingChapters(n) > 0) adapter.notifyDataSetChanged();
        loading = false;
    }

    private int prependPreviousChapter() {
        if (firstLoadedBook == -1) return 0;
        loading = true;
        int prevBook = firstLoadedBook, prevChapter = firstLoadedChapter - 1;
        if (prevChapter < 1) {
            prevBook = db.getPrevBook(firstLoadedBook);
            if (prevBook == -1) { loading = false; return 0; }
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
        loading = false;
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
                verseList.setSelectionFromTop(firstVisible + i, -(accumulated - targetScroll));
                return;
            }
        }
        int avgH = listHeight / Math.max(1, childCount);
        int skip = targetScroll / Math.max(1, avgH);
        verseList.setSelectionFromTop(Math.min(firstVisible + skip, items.size() - 1), 0);
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
        verseList.setSelectionFromTop(newPos, firstOffset + targetScroll - (skip * avgH));
    }

    // --- Sync: scroll to specific verse ---

    public void syncToVerse(int bookNumber, int chapter, String verseNum) {
        if (scrollToVerse(bookNumber, chapter, verseNum)) return;
        // Chapter not loaded, reload from this chapter and try again
        loadFrom(bookNumber, chapter);
        scrollToVerse(bookNumber, chapter, verseNum);
    }

    private boolean scrollToVerse(int bookNumber, int chapter, String verseNum) {
        for (int i = 0; i < items.size(); i++) {
            ReadingItem it = items.get(i);
            if (it.type == TYPE_VERSE && it.bookNumber == bookNumber && it.chapter == chapter
                    && it.text1.equals(verseNum)) {
                verseList.setSelectionFromTop(i, 0);
                return true;
            }
        }
        return false;
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
        verseList.setSelectionFromTop(0, 0);
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
        verseList.setSelectionFromTop(0, 0);
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
        verseList.setSelectionFromTop(0, 0);
        if (syncListener != null) syncListener.onNavigated(this, currentBook, currentChapter);
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
                hideAllPickers(); loadFrom(currentBook, currentChapter); verseList.setSelectionFromTop(0, 0);
                if (syncListener != null) syncListener.onNavigated(ReaderPanel.this, currentBook, currentChapter);
            }
        });
        bookGrid.setVisibility(View.GONE); chapterGrid.setVisibility(View.VISIBLE);
        bookPickerVisible = false; chapterPickerVisible = true;
    }

    public boolean onBackPressed() {
        if (chapterPickerVisible) { showBookPicker(); return true; }
        if (bookPickerVisible || translationPickerVisible) { hideAllPickers(); return true; }
        return false;
    }

    public boolean isVerseListVisible() { return verseList.getVisibility() == View.VISIBLE; }

    /** Get the first visible verse info: [bookNumber, chapter, verseNum]. Returns null if no verse visible. */
    public int[] getFirstVisibleVerseInfo() {
        int pos = verseList.getFirstVisiblePosition();
        // Scan from first visible position to find a verse (skip headers)
        for (int i = pos; i < Math.min(pos + 5, items.size()); i++) {
            ReadingItem item = items.get(i);
            if (item.type == TYPE_VERSE) {
                try {
                    return new int[]{item.bookNumber, item.chapter, Integer.parseInt(item.text1)};
                } catch (NumberFormatException e) {
                    return new int[]{item.bookNumber, item.chapter, 1};
                }
            }
        }
        return null;
    }

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
            return cv;
        }

        private CharSequence renderVerse(ReadingItem item) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(item.text1).append(' ');
            int numEnd = item.text1.length();
            sb.setSpan(new SuperscriptSpan(), 0, numEnd, 0);
            sb.setSpan(new RelativeSizeSpan(0.7f), 0, numEnd, 0);
            sb.append(TextCleaner.toSpanned(item.text2));
            return sb;
        }
    }
}
