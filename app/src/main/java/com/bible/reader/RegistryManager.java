package com.bible.reader;

import android.content.Context;
import android.os.AsyncTask;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class RegistryManager {

    private static final String[] REGISTRY_URLS = {
            "https://mybible.zone/repository/registry/registry.zip",
            "https://dl.dropbox.com/s/keg0ptkkalux5fi/registry.zip",
            "http://mybible.infoo.pro/registry.zip",
            "http://myb.1gb.ru/registry.zip",
            "http://mph4.ru/registry.zip",
    };
    private static final String CACHE_FILE = "registry.json";
    private static final int TIMEOUT_MS = 15000;

    public interface Callback {
        void onSuccess(List<Language> languages);
        void onError(String message);
    }

    public static class Language {
        public final String code;
        public final String displayName;
        public final List<BibleModule> modules;

        Language(String code, String displayName, List<BibleModule> modules) {
            this.code = code;
            this.displayName = displayName;
            this.modules = modules;
        }
    }

    public static class BibleModule {
        public final String abbreviation;
        public final String description;
        public final String file;
        public final String size;
        public final List<String> urls;

        BibleModule(String abbreviation, String description, String file, String size, List<String> urls) {
            this.abbreviation = abbreviation;
            this.description = description;
            this.file = file;
            this.size = size;
            this.urls = urls;
        }
    }

    public static void loadRegistry(final Context context, final boolean forceRefresh, final Callback callback) {
        new AsyncTask<Void, Void, Object>() {
            @Override
            protected Object doInBackground(Void... params) {
                try {
                    File cacheFile = new File(context.getFilesDir(), CACHE_FILE);
                    String json;

                    if (!forceRefresh && cacheFile.exists()) {
                        json = readFile(cacheFile);
                    } else {
                        json = downloadAndExtractRegistryWithFallback();
                        writeFile(cacheFile, json);
                    }

                    return parseRegistry(json);
                } catch (Exception e) {
                    return e.getMessage();
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void onPostExecute(Object result) {
                if (result instanceof List) {
                    callback.onSuccess((List<Language>) result);
                } else {
                    callback.onError((String) result);
                }
            }
        }.execute();
    }

    private static String downloadAndExtractRegistryWithFallback() throws Exception {
        Exception lastError = null;
        for (String registryUrl : REGISTRY_URLS) {
            try {
                return downloadAndExtractRegistry(registryUrl);
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new Exception("All mirrors failed");
    }

    private static String downloadAndExtractRegistry(String registryUrl) throws Exception {
        URL url = new URL(registryUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        try {
            InputStream in = new BufferedInputStream(conn.getInputStream());
            ZipInputStream zis = new ZipInputStream(in);
            ZipEntry entry = zis.getNextEntry();
            if (entry == null) throw new Exception("Empty zip");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = zis.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            zis.close();
            String json = bos.toString("UTF-8");
            // Remove BOM if present
            if (json.length() > 0 && json.charAt(0) == '\uFEFF') {
                json = json.substring(1);
            }
            return json;
        } finally {
            conn.disconnect();
        }
    }

    private static List<Language> parseRegistry(String json) throws Exception {
        JSONObject root = new JSONObject(json);

        // Parse hosts
        Map<String, String[]> hosts = new HashMap<>();
        JSONArray hostsArr = root.getJSONArray("hosts");
        for (int i = 0; i < hostsArr.length(); i++) {
            JSONObject h = hostsArr.getJSONObject(i);
            hosts.put(h.getString("alias"), new String[]{
                    h.getString("path"),
                    String.valueOf(h.getInt("priority")),
                    String.valueOf(h.getInt("weight"))
            });
        }

        // Parse downloads, filter bibles only
        JSONArray downloads = root.getJSONArray("downloads");
        Map<String, List<BibleModule>> byLang = new HashMap<>();

        for (int i = 0; i < downloads.length(); i++) {
            JSONObject d = downloads.getJSONObject(i);
            String fil = d.optString("fil", "");
            boolean hidden = d.optBoolean("hid", false);

            // Only bibles (no extension in fil) and not hidden
            if (fil.contains(".") || hidden) continue;

            String abr = d.optString("abr", "");
            String des = d.optString("des", "");
            String lng = d.optString("lng", "");
            String siz = d.optString("siz", "");

            // Resolve URLs
            List<String> resolvedUrls = new ArrayList<>();
            JSONArray urlArr = d.optJSONArray("url");
            if (urlArr != null) {
                for (int j = 0; j < urlArr.length(); j++) {
                    String u = urlArr.getString(j);
                    resolvedUrls.add(resolveUrl(u, fil, hosts));
                }
            }

            BibleModule module = new BibleModule(abr, des, fil, siz, resolvedUrls);

            if (!byLang.containsKey(lng)) {
                byLang.put(lng, new ArrayList<BibleModule>());
            }
            byLang.get(lng).add(module);
        }

        // Build language list
        List<Language> languages = new ArrayList<>();
        for (Map.Entry<String, List<BibleModule>> entry : byLang.entrySet()) {
            String code = entry.getKey();
            String displayName = getLanguageDisplayName(code);
            languages.add(new Language(code, displayName, entry.getValue()));
        }

        // Sort by module count descending
        Collections.sort(languages, new Comparator<Language>() {
            @Override
            public int compare(Language a, Language b) {
                return b.modules.size() - a.modules.size();
            }
        });

        return languages;
    }

    private static String resolveUrl(String urlTemplate, String file, Map<String, String[]> hosts) {
        if (urlTemplate.startsWith("{")) {
            int end = urlTemplate.indexOf('}');
            if (end > 0) {
                String alias = urlTemplate.substring(1, end);
                String suffix = urlTemplate.substring(end + 1);
                String[] host = hosts.get(alias);
                if (host != null) {
                    return host[0].replace("%s", suffix);
                }
            }
        }
        return urlTemplate;
    }

    private static String getLanguageDisplayName(String code) {
        if (code == null || code.isEmpty()) return "Unknown";
        // Handle special codes from MyBible
        if (code.contains(" ")) {
            // e.g. "zh Simplified" -> keep as-is
            return code;
        }
        try {
            Locale locale = new Locale(code);
            String name = locale.getDisplayLanguage(locale);
            if (name.equals(code)) {
                // Fallback: capitalize
                return code.substring(0, 1).toUpperCase() + code.substring(1);
            }
            return name.substring(0, 1).toUpperCase() + name.substring(1);
        } catch (Exception e) {
            return code;
        }
    }

    private static String readFile(File file) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) {
            bos.write(buf, 0, len);
        }
        fis.close();
        return bos.toString("UTF-8");
    }

    private static void writeFile(File file, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }
}
