package com.bible.reader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.TextView;

public class ChapterListActivity extends Activity {

    private int bookNumber;
    private String bookName;
    private int chapterCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chapters);

        bookNumber = getIntent().getIntExtra("book_number", 10);
        bookName = getIntent().getStringExtra("book_name");
        if (bookName == null) bookName = "";

        ((TextView) findViewById(R.id.header)).setText(bookName);

        chapterCount = BibleApplication.getDb().getChapterCount(bookNumber);

        GridView grid = (GridView) findViewById(R.id.grid);
        grid.setAdapter(new ChapterAdapter());
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                int chapter = position + 1;
                Intent intent = new Intent(ChapterListActivity.this, VerseActivity.class);
                intent.putExtra("book_number", bookNumber);
                intent.putExtra("book_name", bookName);
                intent.putExtra("chapter", chapter);
                startActivity(intent);
            }
        });
    }

    private class ChapterAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return chapterCount;
        }

        @Override
        public Object getItem(int position) {
            return position + 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_chapter, parent, false);
            }
            ((TextView) convertView.findViewById(R.id.chapter_number)).setText(String.valueOf(position + 1));
            return convertView;
        }
    }
}
