package com.bible.reader;

import android.content.Context;
import android.os.AsyncTask;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModuleDownloader {

    private static final int TIMEOUT_MS = 30000;
    private static final String MODULES_DIR = "modules";

    public interface Callback {
        void onProgress(int percent);
        void onSuccess(String filePath);
        void onError(String message);
    }

    public static File getModulesDir(Context context) {
        File dir = new File(context.getFilesDir(), MODULES_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static void download(final Context context, final RegistryManager.BibleModule module, final Callback callback) {
        new AsyncTask<Void, Integer, Object>() {
            @Override
            protected Object doInBackground(Void... params) {
                List<String> urls = module.urls;
                String lastError = "No download URLs";

                for (String urlStr : urls) {
                    try {
                        return tryDownload(context, urlStr, module.file);
                    } catch (Exception e) {
                        lastError = e.getMessage();
                    }
                }
                return lastError;
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                callback.onProgress(values[0]);
            }

            @Override
            protected void onPostExecute(Object result) {
                if (result instanceof File) {
                    callback.onSuccess(((File) result).getPath());
                } else {
                    callback.onError((String) result);
                }
            }
        }.execute();
    }

    private static File tryDownload(Context context, String urlStr, String fileName) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }

            InputStream in = new BufferedInputStream(conn.getInputStream());

            // Check if it's a zip by reading first bytes
            // The response should be a zip file containing the SQLite3 database
            File modulesDir = getModulesDir(context);
            File tempFile = new File(modulesDir, fileName + ".tmp");
            File destFile = new File(modulesDir, fileName + ".SQLite3");

            // Try to unzip
            ZipInputStream zis = new ZipInputStream(in);
            ZipEntry entry = zis.getNextEntry();

            if (entry != null) {
                // It's a zip - extract first file
                FileOutputStream fos = new FileOutputStream(tempFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = zis.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
                fos.close();
                zis.close();
            } else {
                // Not a zip - save directly (raw SQLite3)
                FileOutputStream fos = new FileOutputStream(tempFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
                fos.close();
                in.close();
            }

            // Rename temp to final
            if (destFile.exists()) destFile.delete();
            tempFile.renameTo(destFile);

            return destFile;
        } finally {
            conn.disconnect();
        }
    }

    public static boolean isDownloaded(Context context, String fileName) {
        File file = new File(getModulesDir(context), fileName + ".SQLite3");
        return file.exists();
    }

    public static void deleteModule(Context context, String fileName) {
        File file = new File(getModulesDir(context), fileName + ".SQLite3");
        if (file.exists()) file.delete();
    }
}
