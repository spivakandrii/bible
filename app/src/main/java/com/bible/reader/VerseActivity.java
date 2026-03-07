package com.bible.reader;

import android.app.ListActivity;
import android.os.Bundle;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

public class VerseActivity extends ListActivity {

    private List<String[]> verses;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_verse);

        int bookNumber = getIntent().getIntExtra("book_number", 10);
        String bookName = getIntent().getStringExtra("book_name");
        int chapter = getIntent().getIntExtra("chapter", 1);

        if (bookName == null) bookName = "";
        ((TextView) findViewById(R.id.header)).setText(bookName + " " + chapter);

        verses = BibleApplication.getDb().getVerses(bookNumber, chapter);
        setListAdapter(new VerseAdapter());
    }

    private class VerseAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return verses.size();
        }

        @Override
        public Object getItem(int position) {
            return verses.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

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
