package com.bible.reader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.view.KeyEvent;
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

public class ReaderActivity extends Activity {

    private static final String PREFS_NAME = "bible_reader";
    private static final String PREF_MODULE = "module";
    private static final String PREF_BOOK = "book_number";
    private static final String PREF_CHAPTER = "chapter";
    private static final String PREF_SCROLL_POS = "scroll_pos";
    private static final String PREF_SCROLL_OFFSET = "scroll_offset";

    private static final String DEFAULT_MODULE = "UBIO'88.SQLite3";
    private static final int DEFAULT_BOOK = 10;
    private static final int DEFAULT_CHAPTER = 1;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_VERSE = 1;

    private static final String[][] BUILTIN_MODULES = {
            {"UBIO'88.SQLite3", "UBIO'88"},
            {"BDC'24.SQLite3", "BDC'24"},
            {"KJV+.SQLite3", "KJV+"},
            {"CUV'23.SQLite3", "CUV'23"},
    };

    private TextView btnTranslation;
    private TextView btnReference;
    private TextView btnPrev;
    private TextView btnNext;
    private ListView verseList;
    private ListView translationList;
    private GridView bookGrid;
    private GridView chapterGrid;
    private boolean translationPickerVisible = false;
    private boolean bookPickerVisible = false;
    private boolean chapterPickerVisible = false;

    private String currentModule;
    private int currentBook;
    private int currentChapter;

    // Continuous reading data
    private List<ReadingItem> items = new ArrayList<>();
    private ReadingAdapter adapter;
    private int lastLoadedBook = -1;
    private int lastLoadedChapter = -1;
    private int firstLoadedBook = -1;
    private int firstLoadedChapter = -1;
    private boolean loading = false;

    /** Item in the continuous reading list */
    static class ReadingItem {
        int type; // TYPE_HEADER or TYPE_VERSE
        int bookNumber;
        int chapter;
        String text1; // header: full title, verse: verse number
        String text2; // verse: verse text (null for header)

        ReadingItem(int type, int bookNumber, int chapter, String text1, String text2) {
            this.type = type;
            this.bookNumber = bookNumber;
            this.chapter = chapter;
            this.text1 = text1;
            this.text2 = text2;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        btnTranslation = (TextView) findViewById(R.id.btn_translation);
        btnReference = (TextView) findViewById(R.id.btn_reference);
        btnPrev = (TextView) findViewById(R.id.btn_prev);
        btnNext = (TextView) findViewById(R.id.btn_next);
        verseList = (ListView) findViewById(R.id.verse_list);
        translationList = (ListView) findViewById(R.id.translation_list);
        bookGrid = (GridView) findViewById(R.id.book_grid);
        chapterGrid = (GridView) findViewById(R.id.chapter_grid);

        adapter = new ReadingAdapter();
        verseList.setAdapter(adapter);

        EinkHelper.setPartialUpdate(verseList);
        EinkHelper.setPartialUpdate(translationList);
        EinkHelper.setPartialUpdate(bookGrid);
        EinkHelper.setPartialUpdate(chapterGrid);

        // Restore saved state
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentModule = prefs.getString(PREF_MODULE, DEFAULT_MODULE);
        currentBook = prefs.getInt(PREF_BOOK, DEFAULT_BOOK);
        currentChapter = prefs.getInt(PREF_CHAPTER, DEFAULT_CHAPTER);

        BibleApplication.getDb().openModule(currentModule);
        loadFrom(currentBook, currentChapter);
        restoreScrollPosition();

        // Scroll listener: lazy load more chapters + update toolbar
        verseList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {}

            @Override
            public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                // Update toolbar based on first visible item
                if (firstVisible >= 0 && firstVisible < items.size()) {
                    ReadingItem item = items.get(firstVisible);
                    if (item.bookNumber != currentBook || item.chapter != currentChapter) {
                        currentBook = item.bookNumber;
                        currentChapter = item.chapter;
                        updateToolbar();
                    }
                }
                // Lazy load forward: when near the end
                if (!loading && totalCount > 0 && firstVisible + visibleCount >= totalCount - 10) {
                    for (int i = 0; i < 3; i++) appendNextChapter();
                }
                // Lazy load backward: when near the top
                if (!loading && firstVisible <= 5 && firstLoadedBook != -1) {
                    View fc = view.getChildAt(0);
                    int offset = (fc != null) ? fc.getTop() : 0;
                    int added = prependPreviousChapter();
                    if (added > 0) {
                        // Correct scroll position so user doesn't see a jump
                        verseList.setSelectionFromTop(firstVisible + added, offset);
                    }
                }
            }
        });

        btnTranslation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showTranslationPicker(); }
        });

        btnReference.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showBookPicker(); }
        });

        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { navigatePrev(); }
        });

        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { navigateNext(); }
        });
    }

    // --- Continuous reading ---

    /** Clear and load starting from given book/chapter, plus a few ahead */
    private void loadFrom(int bookNumber, int chapter) {
        items.clear();
        lastLoadedBook = -1;
        lastLoadedChapter = -1;
        firstLoadedBook = bookNumber;
        firstLoadedChapter = chapter;
        appendChapter(bookNumber, chapter);
        // Pre-load more chapters ahead
        for (int i = 0; i < 5; i++) appendNextChapter();
        adapter.notifyDataSetChanged();
        currentBook = bookNumber;
        currentChapter = chapter;
        updateToolbar();
    }

    /** Append one chapter (header + verses) to the items list */
    private void appendChapter(int bookNumber, int chapter) {
        DatabaseHelper db = BibleApplication.getDb();
        String bookName = db.getBookName(bookNumber);
        String shortName = db.getBookShortName(bookNumber);
        if (shortName.isEmpty()) shortName = bookName;

        // Header
        items.add(new ReadingItem(TYPE_HEADER, bookNumber, chapter,
                bookName + " " + chapter, null));

        // Verses
        List<String[]> verses = db.getVerses(bookNumber, chapter);
        for (String[] v : verses) {
            items.add(new ReadingItem(TYPE_VERSE, bookNumber, chapter, v[0], v[1]));
        }

        lastLoadedBook = bookNumber;
        lastLoadedChapter = chapter;
    }

    /** Append the next chapter after lastLoadedBook/lastLoadedChapter */
    private void appendNextChapter() {
        if (lastLoadedBook == -1) return;
        loading = true;

        DatabaseHelper db = BibleApplication.getDb();
        int nextBook = lastLoadedBook;
        int nextChapter = lastLoadedChapter + 1;

        int maxChapter = db.getChapterCount(lastLoadedBook);
        if (nextChapter > maxChapter) {
            // Move to next book
            List<int[]> books = db.getBooks();
            boolean foundCurrent = false;
            nextBook = -1;
            for (int[] b : books) {
                if (foundCurrent) {
                    nextBook = b[0];
                    break;
                }
                if (b[0] == lastLoadedBook) foundCurrent = true;
            }
            if (nextBook == -1) {
                loading = false;
                return; // End of Bible
            }
            nextChapter = 1;
        }

        appendChapter(nextBook, nextChapter);
        adapter.notifyDataSetChanged();
        loading = false;
    }

    /** Prepend previous chapter before firstLoadedBook/firstLoadedChapter.
     *  Returns number of items added (for scroll position correction). */
    private int prependPreviousChapter() {
        if (firstLoadedBook == -1) return 0;
        loading = true;

        DatabaseHelper db = BibleApplication.getDb();
        int prevBook = firstLoadedBook;
        int prevChapter = firstLoadedChapter - 1;

        if (prevChapter < 1) {
            // Move to previous book, last chapter
            List<int[]> books = db.getBooks();
            int pb = -1;
            for (int[] b : books) {
                if (b[0] == firstLoadedBook) break;
                pb = b[0];
            }
            if (pb == -1) {
                loading = false;
                return 0; // Beginning of Bible
            }
            prevBook = pb;
            prevChapter = db.getChapterCount(prevBook);
        }

        // Build items for this chapter
        String bookName = db.getBookName(prevBook);
        List<String[]> verses = db.getVerses(prevBook, prevChapter);

        List<ReadingItem> newItems = new ArrayList<>();
        newItems.add(new ReadingItem(TYPE_HEADER, prevBook, prevChapter,
                bookName + " " + prevChapter, null));
        for (String[] v : verses) {
            newItems.add(new ReadingItem(TYPE_VERSE, prevBook, prevChapter, v[0], v[1]));
        }

        // Insert at beginning
        items.addAll(0, newItems);
        firstLoadedBook = prevBook;
        firstLoadedChapter = prevChapter;

        adapter.notifyDataSetChanged();
        loading = false;
        return newItems.size();
    }

    // --- Volume key scrolling ---

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (verseList.getVisibility() != View.VISIBLE) {
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            pageDown();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            pageUp();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Instant page down: jump 2/3 of visible height, no e-ink flash */
    private void pageDown() {
        EinkHelper.setPageTurnMode();
        int firstVisible = verseList.getFirstVisiblePosition();
        int listHeight = verseList.getHeight();
        int targetScroll = listHeight * 2 / 3;

        // Find which item will be at the top after scrolling 2/3 down
        int accumulated = 0;
        View firstChild = verseList.getChildAt(0);
        int firstOffset = (firstChild != null) ? firstChild.getTop() : 0;
        accumulated = -firstOffset; // how much of first item is already scrolled past

        int childCount = verseList.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = verseList.getChildAt(i);
            if (child == null) continue;
            accumulated += child.getHeight();
            if (accumulated >= targetScroll) {
                int newPos = firstVisible + i;
                int newOffset = accumulated - targetScroll;
                verseList.setSelectionFromTop(newPos, -newOffset);
                return;
            }
        }
        // If we ran out of visible children, jump by estimated items
        int avgHeight = listHeight / Math.max(1, childCount);
        int itemsToSkip = targetScroll / Math.max(1, avgHeight);
        int newPos = Math.min(firstVisible + itemsToSkip, items.size() - 1);
        verseList.setSelectionFromTop(newPos, 0);
    }

    /** Instant page up: jump 2/3 of visible height backwards, no e-ink flash */
    private void pageUp() {
        EinkHelper.setPageTurnMode();
        int firstVisible = verseList.getFirstVisiblePosition();
        View firstChild = verseList.getChildAt(0);
        int firstOffset = (firstChild != null) ? firstChild.getTop() : 0;

        int listHeight = verseList.getHeight();
        int targetScroll = listHeight * 2 / 3;

        // Estimate: average item height from visible children
        int childCount = verseList.getChildCount();
        int totalChildHeight = 0;
        for (int i = 0; i < childCount; i++) {
            View child = verseList.getChildAt(i);
            if (child != null) totalChildHeight += child.getHeight();
        }
        int avgHeight = totalChildHeight / Math.max(1, childCount);
        int itemsToSkip = targetScroll / Math.max(1, avgHeight);

        int newPos = Math.max(0, firstVisible - itemsToSkip);
        int newOffset = firstOffset + targetScroll - (itemsToSkip * avgHeight);
        verseList.setSelectionFromTop(newPos, newOffset);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Consume key up too so volume popup doesn't appear
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (verseList.getVisibility() == View.VISIBLE) return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // --- Navigation ---

    private void navigatePrev() {
        DatabaseHelper db = BibleApplication.getDb();
        int prevBook = currentBook;
        int prevChapter = currentChapter - 1;

        if (prevChapter < 1) {
            List<int[]> books = db.getBooks();
            int pb = -1;
            for (int[] b : books) {
                if (b[0] == currentBook) break;
                pb = b[0];
            }
            if (pb == -1) return;
            prevBook = pb;
            prevChapter = db.getChapterCount(prevBook);
        }

        loadFrom(prevBook, prevChapter);
        verseList.setSelectionFromTop(0, 0);
        saveState();
    }

    private void navigateNext() {
        DatabaseHelper db = BibleApplication.getDb();
        int nextBook = currentBook;
        int nextChapter = currentChapter + 1;

        int maxChapter = db.getChapterCount(currentBook);
        if (nextChapter > maxChapter) {
            List<int[]> books = db.getBooks();
            boolean found = false;
            nextBook = -1;
            for (int[] b : books) {
                if (found) { nextBook = b[0]; break; }
                if (b[0] == currentBook) found = true;
            }
            if (nextBook == -1) return;
            nextChapter = 1;
        }

        loadFrom(nextBook, nextChapter);
        verseList.setSelectionFromTop(0, 0);
        saveState();
    }

    private void updateToolbar() {
        DatabaseHelper db = BibleApplication.getDb();
        String moduleName = currentModule.replace(".SQLite3", "");
        btnTranslation.setText(moduleName);

        String shortName = db.getBookShortName(currentBook);
        if (shortName.isEmpty()) shortName = db.getBookName(currentBook);
        btnReference.setText(shortName + " " + currentChapter);
    }

    // --- Translation picker ---

    private void showTranslationPicker() {
        if (translationPickerVisible) { hideAllPickers(); return; }

        final List<String[]> modules = new ArrayList<>();
        for (String[] m : BUILTIN_MODULES) modules.add(new String[]{m[0], m[1]});
        List<String> downloaded = BibleApplication.getDb().listDownloadedModules();
        for (String filename : downloaded) modules.add(new String[]{filename, filename.replace(".SQLite3", "")});
        modules.add(new String[]{"__download__", "Завантажити ще..."});

        translationList.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return modules.size(); }
            @Override public Object getItem(int pos) { return modules.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_translation, parent, false);
                String[] item = modules.get(pos);
                ((TextView) cv.findViewById(R.id.translation_name)).setText(item[1]);
                ((TextView) cv.findViewById(R.id.translation_lang)).setText(item[0].equals(currentModule) ? " ✓" : "");
                return cv;
            }
        });

        translationList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                String[] selected = modules.get(pos);
                hideAllPickers();
                if ("__download__".equals(selected[0])) {
                    startActivity(new Intent(ReaderActivity.this, DownloadLanguagesActivity.class));
                    return;
                }
                switchTranslation(selected[0]);
            }
        });

        verseList.setVisibility(View.GONE);
        bookGrid.setVisibility(View.GONE);
        chapterGrid.setVisibility(View.GONE);
        translationList.setVisibility(View.VISIBLE);
        translationPickerVisible = true;
        bookPickerVisible = false;
        chapterPickerVisible = false;
    }

    private void switchTranslation(String moduleFile) {
        if (moduleFile.equals(currentModule)) return;
        currentModule = moduleFile;
        BibleApplication.getDb().openModule(currentModule);

        DatabaseHelper db = BibleApplication.getDb();
        if (!db.hasBook(currentBook)) {
            List<int[]> books = db.getBooks();
            if (!books.isEmpty()) currentBook = books.get(0)[0];
            currentChapter = 1;
        } else {
            int maxChapter = db.getChapterCount(currentBook);
            if (currentChapter > maxChapter) currentChapter = maxChapter;
        }
        loadFrom(currentBook, currentChapter);
        verseList.setSelectionFromTop(0, 0);
        saveState();
    }

    // --- Book/Chapter picker ---

    private void hideAllPickers() {
        translationList.setVisibility(View.GONE);
        bookGrid.setVisibility(View.GONE);
        chapterGrid.setVisibility(View.GONE);
        verseList.setVisibility(View.VISIBLE);
        translationPickerVisible = false;
        bookPickerVisible = false;
        chapterPickerVisible = false;
    }

    private void showBookPicker() {
        if (bookPickerVisible) { hideAllPickers(); return; }

        final DatabaseHelper db = BibleApplication.getDb();
        final List<int[]> books = db.getBooks();

        int bRows = (books.size() + 5) / 6;
        int bAvailH = getResources().getDisplayMetrics().heightPixels
                - (int)(54 * getResources().getDisplayMetrics().density);
        final int bCellH = bAvailH / bRows;

        bookGrid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return books.size(); }
            @Override public Object getItem(int pos) { return books.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_grid_cell, parent, false);
                int bookNum = books.get(pos)[0];
                String shortName = db.getBookShortName(bookNum);
                if (shortName.isEmpty()) shortName = db.getBookName(bookNum);
                TextView tv = (TextView) cv.findViewById(R.id.cell_text);
                tv.setText(shortName);
                tv.setHeight(bCellH);
                tv.setTextSize(18);
                tv.setBackgroundColor(bookNum == currentBook ? 0xFFCCCCCC : 0xFFF0F0F0);
                return cv;
            }
        });

        bookGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                showChapterPicker(books.get(pos)[0]);
            }
        });

        verseList.setVisibility(View.GONE);
        translationList.setVisibility(View.GONE);
        chapterGrid.setVisibility(View.GONE);
        bookGrid.setVisibility(View.VISIBLE);
        translationPickerVisible = false;
        bookPickerVisible = true;
        chapterPickerVisible = false;
    }

    private void showChapterPicker(final int bookNumber) {
        final DatabaseHelper db = BibleApplication.getDb();
        final int chapterCount = db.getChapterCount(bookNumber);

        int cols;
        if (chapterCount <= 20) cols = 5;
        else if (chapterCount <= 50) cols = 6;
        else if (chapterCount <= 80) cols = 8;
        else cols = 10;
        chapterGrid.setNumColumns(cols);

        int rows = (chapterCount + cols - 1) / cols;
        int availableH = getResources().getDisplayMetrics().heightPixels
                - (int)(54 * getResources().getDisplayMetrics().density);
        final int cellH = availableH / rows;

        chapterGrid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return chapterCount; }
            @Override public Object getItem(int pos) { return pos + 1; }
            @Override public long getItemId(int pos) { return pos; }
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_grid_cell, parent, false);
                int ch = pos + 1;
                TextView tv = (TextView) cv.findViewById(R.id.cell_text);
                tv.setText(String.valueOf(ch));
                tv.setHeight(cellH);
                tv.setBackgroundColor(bookNumber == currentBook && ch == currentChapter ? 0xFFCCCCCC : 0xFFF0F0F0);
                return cv;
            }
        });

        chapterGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                currentBook = bookNumber;
                currentChapter = pos + 1;
                hideAllPickers();
                loadFrom(currentBook, currentChapter);
                verseList.setSelectionFromTop(0, 0);
                saveState();
            }
        });

        bookGrid.setVisibility(View.GONE);
        chapterGrid.setVisibility(View.VISIBLE);
        bookPickerVisible = false;
        chapterPickerVisible = true;
    }

    // --- State ---

    private void saveState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_MODULE, currentModule)
                .putInt(PREF_BOOK, currentBook)
                .putInt(PREF_CHAPTER, currentChapter)
                .apply();
    }

    private void saveScrollPosition() {
        int pos = verseList.getFirstVisiblePosition();
        int offset = 0;
        View firstChild = verseList.getChildAt(0);
        if (firstChild != null) offset = firstChild.getTop() - verseList.getPaddingTop();

        // Save the book/chapter of first visible item for correct restoration
        if (pos >= 0 && pos < items.size()) {
            ReadingItem item = items.get(pos);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putInt(PREF_BOOK, item.bookNumber)
                    .putInt(PREF_CHAPTER, item.chapter)
                    .putInt(PREF_SCROLL_POS, pos)
                    .putInt(PREF_SCROLL_OFFSET, offset)
                    .apply();
        }
    }

    private void restoreScrollPosition() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int pos = prefs.getInt(PREF_SCROLL_POS, 0);
        int offset = prefs.getInt(PREF_SCROLL_OFFSET, 0);
        // pos is relative to the items list which starts from the saved chapter
        if (pos < items.size()) {
            verseList.setSelectionFromTop(pos, offset);
        }
    }

    @Override
    public void onBackPressed() {
        if (chapterPickerVisible) { showBookPicker(); return; }
        if (bookPickerVisible || translationPickerVisible) { hideAllPickers(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveScrollPosition();
        saveState();
    }

    // --- Adapter ---

    private class ReadingAdapter extends BaseAdapter {
        @Override
        public int getCount() { return items.size(); }

        @Override
        public Object getItem(int position) { return items.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public int getViewTypeCount() { return 2; }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ReadingItem item = items.get(position);

            if (item.type == TYPE_HEADER) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.item_chapter_header, parent, false);
                }
                ((TextView) convertView.findViewById(R.id.header_text)).setText(item.text1);
                return convertView;
            }

            // TYPE_VERSE
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_verse, parent, false);
            }

            Spanned verseText = TextCleaner.toSpanned(item.text2);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int start = sb.length();
            sb.append(item.text1); // verse number
            sb.append(" ");
            int end = sb.length();
            sb.setSpan(new SuperscriptSpan(), start, end - 1, 0);
            sb.setSpan(new RelativeSizeSpan(0.7f), start, end - 1, 0);
            sb.append(verseText);

            ((TextView) convertView.findViewById(R.id.verse_text)).setText(sb);
            return convertView;
        }
    }
}
