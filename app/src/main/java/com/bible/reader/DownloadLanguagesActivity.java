package com.bible.reader;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class DownloadLanguagesActivity extends ListActivity {

    private ProgressBar progress;
    private TextView status;
    private List<RegistryManager.Language> languages = new ArrayList<>();
    private LanguageAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_languages);

        progress = (ProgressBar) findViewById(R.id.progress);
        status = (TextView) findViewById(R.id.status);

        adapter = new LanguageAdapter();
        setListAdapter(adapter);

        Button refresh = (Button) findViewById(R.id.btn_refresh);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadRegistry(true);
            }
        });

        loadRegistry(false);
    }

    private void loadRegistry(boolean forceRefresh) {
        progress.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText(forceRefresh ? "Завантаження каталогу..." : "Завантаження...");

        RegistryManager.loadRegistry(this, forceRefresh, new RegistryManager.Callback() {
            @Override
            public void onSuccess(List<RegistryManager.Language> result) {
                progress.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                languages = result;
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onError(String message) {
                progress.setVisibility(View.GONE);
                status.setText("Помилка: " + message);
                status.setVisibility(View.VISIBLE);
                Toast.makeText(DownloadLanguagesActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        RegistryManager.Language lang = languages.get(position);
        Intent intent = new Intent(this, DownloadModulesActivity.class);
        intent.putExtra("language_name", lang.displayName);
        intent.putExtra("language_code", lang.code);
        // Pass modules as serializable arrays
        ArrayList<String> abbrs = new ArrayList<>();
        ArrayList<String> descs = new ArrayList<>();
        ArrayList<String> files = new ArrayList<>();
        ArrayList<String> sizes = new ArrayList<>();
        ArrayList<String> urlStrs = new ArrayList<>();
        for (RegistryManager.BibleModule m : lang.modules) {
            abbrs.add(m.abbreviation);
            descs.add(m.description);
            files.add(m.file);
            sizes.add(m.size);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < m.urls.size(); i++) {
                if (i > 0) sb.append("|");
                sb.append(m.urls.get(i));
            }
            urlStrs.add(sb.toString());
        }
        intent.putStringArrayListExtra("abbrs", abbrs);
        intent.putStringArrayListExtra("descs", descs);
        intent.putStringArrayListExtra("files", files);
        intent.putStringArrayListExtra("sizes", sizes);
        intent.putStringArrayListExtra("urls", urlStrs);
        startActivity(intent);
    }

    private class LanguageAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return languages.size();
        }

        @Override
        public Object getItem(int position) {
            return languages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_language, parent, false);
            }
            RegistryManager.Language lang = languages.get(position);
            ((TextView) convertView.findViewById(R.id.language_name)).setText(lang.displayName);
            ((TextView) convertView.findViewById(R.id.language_count)).setText(String.valueOf(lang.modules.size()));
            return convertView;
        }
    }
}
