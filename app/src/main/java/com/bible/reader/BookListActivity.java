package com.bible.reader;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

public class BookListActivity extends ListActivity {

    private List<int[]> books;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_books);

        String title = getIntent().getStringExtra("title");
        ((TextView) findViewById(R.id.header)).setText(title != null ? title : "");

        DatabaseHelper db = BibleApplication.getDb();
        books = db.getBooks();
        setListAdapter(new BookAdapter());
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        int bookNumber = books.get(position)[0];
        DatabaseHelper db = BibleApplication.getDb();
        String bookName = db.getBookName(bookNumber);

        Intent intent = new Intent(this, ChapterListActivity.class);
        intent.putExtra("book_number", bookNumber);
        intent.putExtra("book_name", bookName);
        startActivity(intent);
    }

    private class BookAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return books.size();
        }

        @Override
        public Object getItem(int position) {
            return books.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_book, parent, false);
            }
            int bookNumber = books.get(position)[0];
            String name = BibleApplication.getDb().getBookName(bookNumber);
            ((TextView) convertView.findViewById(R.id.book_name)).setText(name);
            return convertView;
        }
    }
}
