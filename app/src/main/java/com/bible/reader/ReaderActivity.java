package com.bible.reader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.view.View;
import android.view.ViewGroup;
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
    private static final int DEFAULT_BOOK = 10; // Genesis
    private static final int DEFAULT_CHAPTER = 1;

    // Builtin modules: [0]=filename, [1]=display name
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

    private List<String[]> verses = new ArrayList<>();
    private VerseAdapter adapter;

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

        adapter = new VerseAdapter();
        verseList.setAdapter(adapter);

        // E-ink optimization: use partial update (less flashing)
        EinkHelper.setPartialUpdate(verseList);
        EinkHelper.setPartialUpdate(translationList);
        EinkHelper.setPartialUpdate(bookGrid);
        EinkHelper.setPartialUpdate(chapterGrid);

        // Restore saved state
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentModule = prefs.getString(PREF_MODULE, DEFAULT_MODULE);
        currentBook = prefs.getInt(PREF_BOOK, DEFAULT_BOOK);
        currentChapter = prefs.getInt(PREF_CHAPTER, DEFAULT_CHAPTER);

        // Open module and load verses
        BibleApplication.getDb().openModule(currentModule);
        loadChapter();
        restoreScrollPosition();

        btnTranslation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTranslationPicker();
            }
        });

        btnReference.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBookPicker();
            }
        });

        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigatePrev();
            }
        });

        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigateNext();
            }
        });
    }

    /** Hide all pickers and show verse list */
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
        if (bookPickerVisible) {
            hideAllPickers();
            return;
        }

        final DatabaseHelper db = BibleApplication.getDb();
        final List<int[]> books = db.getBooks();

        // Dynamic cell height: fill screen evenly, 6 columns
        int bRows = (books.size() + 5) / 6;
        int bAvailH = getResources().getDisplayMetrics().heightPixels
                - (int)(54 * getResources().getDisplayMetrics().density);
        final int bCellH = bAvailH / bRows;

        bookGrid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() { return books.size(); }
            @Override
            public Object getItem(int pos) { return books.get(pos); }
            @Override
            public long getItemId(int pos) { return pos; }
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                if (cv == null) {
                    cv = getLayoutInflater().inflate(R.layout.item_grid_cell, parent, false);
                }
                int bookNum = books.get(pos)[0];
                String shortName = db.getBookShortName(bookNum);
                if (shortName.isEmpty()) shortName = db.getBookName(bookNum);
                TextView tv = (TextView) cv.findViewById(R.id.cell_text);
                tv.setText(shortName);
                tv.setHeight(bCellH);
                tv.setTextSize(18);
                // Highlight current book
                if (bookNum == currentBook) {
                    tv.setBackgroundColor(0xFFCCCCCC);
                } else {
                    tv.setBackgroundColor(0xFFF0F0F0);
                }
                return cv;
            }
        });

        bookGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                int selectedBook = books.get(pos)[0];
                showChapterPicker(selectedBook);
            }
        });

        // Show book grid, hide everything else
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

        // Dynamic: pick columns so grid fills screen without scrolling
        // More columns for more chapters, then calculate cell height to fill available space
        int cols;
        if (chapterCount <= 20) cols = 5;
        else if (chapterCount <= 50) cols = 6;
        else if (chapterCount <= 80) cols = 8;
        else cols = 10;
        chapterGrid.setNumColumns(cols);

        int rows = (chapterCount + cols - 1) / cols;
        // Available height in pixels: screen minus toolbar
        int availableH = getResources().getDisplayMetrics().heightPixels
                - (int)(54 * getResources().getDisplayMetrics().density);
        // Cell height: fill all available space evenly
        final int cellH = availableH / rows;

        chapterGrid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() { return chapterCount; }
            @Override
            public Object getItem(int pos) { return pos + 1; }
            @Override
            public long getItemId(int pos) { return pos; }
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                if (cv == null) {
                    cv = getLayoutInflater().inflate(R.layout.item_grid_cell, parent, false);
                }
                int ch = pos + 1;
                TextView tv = (TextView) cv.findViewById(R.id.cell_text);
                tv.setText(String.valueOf(ch));
                // Dynamic height
                tv.setHeight(cellH);
                // Highlight current chapter if same book
                if (bookNumber == currentBook && ch == currentChapter) {
                    tv.setBackgroundColor(0xFFCCCCCC);
                } else {
                    tv.setBackgroundColor(0xFFF0F0F0);
                }
                return cv;
            }
        });

        chapterGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                int selectedChapter = pos + 1;
                currentBook = bookNumber;
                currentChapter = selectedChapter;
                hideAllPickers();
                loadChapter();
                verseList.setSelectionFromTop(0, 0);
                saveState();
            }
        });

        // Show chapter grid, hide book grid
        bookGrid.setVisibility(View.GONE);
        chapterGrid.setVisibility(View.VISIBLE);
        bookPickerVisible = false;
        chapterPickerVisible = true;
    }

    private void showTranslationPicker() {
        if (translationPickerVisible) {
            hideAllPickers();
            return;
        }

        // Build list: builtin + downloaded + "Завантажити ще..."
        final List<String[]> modules = new ArrayList<>(); // [0]=filename, [1]=display
        for (String[] m : BUILTIN_MODULES) {
            modules.add(new String[]{m[0], m[1]});
        }
        List<String> downloaded = BibleApplication.getDb().listDownloadedModules();
        for (String filename : downloaded) {
            String name = filename.replace(".SQLite3", "");
            modules.add(new String[]{filename, name});
        }
        modules.add(new String[]{"__download__", "Завантажити ще..."});

        translationList.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() { return modules.size(); }
            @Override
            public Object getItem(int pos) { return modules.get(pos); }
            @Override
            public long getItemId(int pos) { return pos; }
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                if (cv == null) {
                    cv = getLayoutInflater().inflate(R.layout.item_translation, parent, false);
                }
                String[] item = modules.get(pos);
                ((TextView) cv.findViewById(R.id.translation_name)).setText(item[1]);
                // Mark current translation
                String marker = item[0].equals(currentModule) ? " ✓" : "";
                ((TextView) cv.findViewById(R.id.translation_lang)).setText(marker);
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

        // Show translation list, hide everything else
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

        // Keep same book/chapter if possible
        DatabaseHelper db = BibleApplication.getDb();
        if (!db.hasBook(currentBook)) {
            // Fallback to first book
            List<int[]> books = db.getBooks();
            if (!books.isEmpty()) {
                currentBook = books.get(0)[0];
            }
            currentChapter = 1;
        } else {
            int maxChapter = db.getChapterCount(currentBook);
            if (currentChapter > maxChapter) {
                currentChapter = maxChapter;
            }
        }
        loadChapter();
        verseList.setSelectionFromTop(0, 0);
        saveState();
    }

    private void loadChapter() {
        DatabaseHelper db = BibleApplication.getDb();
        verses.clear();
        verses.addAll(db.getVerses(currentBook, currentChapter));
        adapter.notifyDataSetChanged();
        updateToolbar();
    }

    private void updateToolbar() {
        DatabaseHelper db = BibleApplication.getDb();
        // Translation name: strip extension
        String moduleName = currentModule.replace(".SQLite3", "");
        btnTranslation.setText(moduleName);

        // Reference: short book name + chapter
        String shortName = db.getBookShortName(currentBook);
        if (shortName.isEmpty()) {
            shortName = db.getBookName(currentBook);
        }
        btnReference.setText(shortName + " " + currentChapter);
    }

    private void navigatePrev() {
        DatabaseHelper db = BibleApplication.getDb();
        if (currentChapter > 1) {
            currentChapter--;
        } else {
            // Go to previous book, last chapter
            List<int[]> books = db.getBooks();
            int prevBook = -1;
            for (int[] b : books) {
                if (b[0] == currentBook) break;
                prevBook = b[0];
            }
            if (prevBook != -1) {
                currentBook = prevBook;
                currentChapter = db.getChapterCount(currentBook);
            } else {
                return; // Already at the very beginning
            }
        }
        loadChapter();
        verseList.setSelectionFromTop(0, 0);
        saveState();
    }

    private void navigateNext() {
        DatabaseHelper db = BibleApplication.getDb();
        int maxChapter = db.getChapterCount(currentBook);
        if (currentChapter < maxChapter) {
            currentChapter++;
        } else {
            // Go to next book, first chapter
            List<int[]> books = db.getBooks();
            boolean foundCurrent = false;
            for (int[] b : books) {
                if (foundCurrent) {
                    currentBook = b[0];
                    currentChapter = 1;
                    foundCurrent = false;
                    break;
                }
                if (b[0] == currentBook) foundCurrent = true;
            }
            if (foundCurrent) return; // Was last book, no next
        }
        loadChapter();
        verseList.setSelectionFromTop(0, 0);
        saveState();
    }

    /** Navigate to a specific location. Called from translation picker and book/chapter navigator. */
    public void navigateTo(String module, int bookNumber, int chapter) {
        if (!module.equals(currentModule)) {
            currentModule = module;
            BibleApplication.getDb().openModule(currentModule);
        }
        currentBook = bookNumber;
        currentChapter = chapter;
        loadChapter();
        verseList.setSelectionFromTop(0, 0);
        saveState();
    }

    public String getCurrentModule() { return currentModule; }
    public int getCurrentBook() { return currentBook; }
    public int getCurrentChapter() { return currentChapter; }

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
        if (firstChild != null) {
            offset = firstChild.getTop() - verseList.getPaddingTop();
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(PREF_SCROLL_POS, pos)
                .putInt(PREF_SCROLL_OFFSET, offset)
                .apply();
    }

    private void restoreScrollPosition() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int pos = prefs.getInt(PREF_SCROLL_POS, 0);
        int offset = prefs.getInt(PREF_SCROLL_OFFSET, 0);
        verseList.setSelectionFromTop(pos, offset);
    }

    @Override
    public void onBackPressed() {
        if (chapterPickerVisible) {
            // Back from chapters → show books
            showBookPicker();
            return;
        }
        if (bookPickerVisible || translationPickerVisible) {
            hideAllPickers();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveState();
        saveScrollPosition();
    }

    private class VerseAdapter extends BaseAdapter {
        @Override
        public int getCount() { return verses.size(); }

        @Override
        public Object getItem(int position) { return verses.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_verse, parent, false);
            }
            String[] verse = verses.get(position);
            String verseNum = verse[0];
            Spanned verseText = TextCleaner.toSpanned(verse[1]);

            SpannableStringBuilder sb = new SpannableStringBuilder();
            int start = sb.length();
            sb.append(verseNum);
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
