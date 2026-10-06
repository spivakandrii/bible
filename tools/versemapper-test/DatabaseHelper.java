package com.bible.reader;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Desktop stand-in for the Android DatabaseHelper with only what VerseMapper needs, fed from a
 * "book chapter verses" text file produced by run.sh.
 */
public class DatabaseHelper {
    final String name;
    final Map<Integer, int[]> counts = new HashMap<>();

    DatabaseHelper(String name, String countsFile) throws IOException {
        this.name = name;
        Map<Integer, Map<Integer, Integer>> raw = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(countsFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 3) continue;
                raw.computeIfAbsent(Integer.parseInt(p[0]), k -> new HashMap<>())
                        .put(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            }
        }
        for (Map.Entry<Integer, Map<Integer, Integer>> e : raw.entrySet()) {
            int max = 0;
            for (int ch : e.getValue().keySet()) max = Math.max(max, ch);
            int[] c = new int[max + 1];
            for (Map.Entry<Integer, Integer> v : e.getValue().entrySet()) c[v.getKey()] = v.getValue();
            counts.put(e.getKey(), c);
        }
    }

    public boolean hasBook(int book) { return counts.containsKey(book); }

    public int[] getChapterVerseCounts(int book) {
        int[] c = counts.get(book);
        return c == null ? new int[0] : c;
    }

    public String getCurrentModule() { return name; }
}
