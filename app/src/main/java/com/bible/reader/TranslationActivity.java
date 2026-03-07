package com.bible.reader;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class TranslationActivity extends ListActivity {

    private static final String[][] BUILTIN_MODULES = {
            {"UBIO'88.SQLite3", "Біблія Огієнка 1988", "Українська"},
            {"CUV'23.SQLite3", "Сучасний переклад", "Українська"},
            {"KJV+.SQLite3", "King James Version", "English"},
            {"BDC'24.SQLite3", "Biblia Cornilescu 2024", "Română"},
    };

    // Each entry: [0]=filename, [1]=display name, [2]=subtitle, [3]=type ("builtin"/"downloaded"/"action")
    private List<String[]> items = new ArrayList<>();
    private TranslationAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translation);
        adapter = new TranslationAdapter();
        setListAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildList();
    }

    private void rebuildList() {
        items.clear();

        // Built-in modules
        for (String[] m : BUILTIN_MODULES) {
            items.add(new String[]{m[0], m[1], m[2], "builtin"});
        }

        // Downloaded modules
        List<String> downloaded = BibleApplication.getDb().listDownloadedModules();
        for (String filename : downloaded) {
            String desc = BibleApplication.getDb().getModuleDescription(filename);
            items.add(new String[]{filename, desc, "Завантажено", "downloaded"});
        }

        // "Download more..." action
        items.add(new String[]{"", "Завантажити ще...", "", "action"});

        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        String[] item = items.get(position);
        String type = item[3];

        if ("action".equals(type)) {
            startActivity(new Intent(this, DownloadLanguagesActivity.class));
            return;
        }

        String moduleFile = item[0];
        String moduleName = item[1];
        BibleApplication.getDb().openModule(moduleFile);

        Intent intent = new Intent(this, BookListActivity.class);
        intent.putExtra("title", moduleName);
        startActivity(intent);
    }

    private class TranslationAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_translation, parent, false);
            }
            String[] item = items.get(position);
            TextView name = (TextView) convertView.findViewById(R.id.translation_name);
            TextView lang = (TextView) convertView.findViewById(R.id.translation_lang);
            name.setText(item[1]);
            lang.setText(item[2]);
            return convertView;
        }
    }
}
