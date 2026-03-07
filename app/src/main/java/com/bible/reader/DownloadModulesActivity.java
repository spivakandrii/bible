package com.bible.reader;

import android.app.ListActivity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DownloadModulesActivity extends ListActivity {

    private List<RegistryManager.BibleModule> modules = new ArrayList<>();
    private Set<String> downloading = new HashSet<>();
    private ModuleAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_modules);

        String langName = getIntent().getStringExtra("language_name");
        ((TextView) findViewById(R.id.header)).setText(langName != null ? langName : "");

        // Reconstruct modules from intent
        ArrayList<String> abbrs = getIntent().getStringArrayListExtra("abbrs");
        ArrayList<String> descs = getIntent().getStringArrayListExtra("descs");
        ArrayList<String> files = getIntent().getStringArrayListExtra("files");
        ArrayList<String> sizes = getIntent().getStringArrayListExtra("sizes");
        ArrayList<String> urlStrs = getIntent().getStringArrayListExtra("urls");

        if (abbrs != null) {
            for (int i = 0; i < abbrs.size(); i++) {
                String urlStr = urlStrs != null ? urlStrs.get(i) : "";
                List<String> urls = Arrays.asList(urlStr.split("\\|"));
                modules.add(new RegistryManager.BibleModule(
                        abbrs.get(i), descs.get(i), files.get(i),
                        sizes != null ? sizes.get(i) : "",
                        urls));
            }
        }

        adapter = new ModuleAdapter();
        setListAdapter(adapter);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        final RegistryManager.BibleModule module = modules.get(position);

        if (ModuleDownloader.isDownloaded(this, module.file)) {
            Toast.makeText(this, "Вже завантажено", Toast.LENGTH_SHORT).show();
            return;
        }

        if (downloading.contains(module.file)) {
            return;
        }

        downloading.add(module.file);
        adapter.notifyDataSetChanged();

        ModuleDownloader.download(this, module, new ModuleDownloader.Callback() {
            @Override
            public void onProgress(int percent) {
            }

            @Override
            public void onSuccess(String filePath) {
                downloading.remove(module.file);
                adapter.notifyDataSetChanged();
                Toast.makeText(DownloadModulesActivity.this,
                        module.abbreviation + " завантажено!", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                downloading.remove(module.file);
                adapter.notifyDataSetChanged();
                Toast.makeText(DownloadModulesActivity.this,
                        "Помилка: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private class ModuleAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return modules.size();
        }

        @Override
        public Object getItem(int position) {
            return modules.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_module, parent, false);
            }
            RegistryManager.BibleModule module = modules.get(position);

            ((TextView) convertView.findViewById(R.id.module_abbr)).setText(module.abbreviation);
            ((TextView) convertView.findViewById(R.id.module_desc)).setText(module.description);
            ((TextView) convertView.findViewById(R.id.module_size)).setText(module.size);

            TextView statusView = (TextView) convertView.findViewById(R.id.module_status);
            if (ModuleDownloader.isDownloaded(DownloadModulesActivity.this, module.file)) {
                statusView.setText("✓");
            } else if (downloading.contains(module.file)) {
                statusView.setText("...");
            } else {
                statusView.setText("↓");
            }

            return convertView;
        }
    }
}
