package com.bible.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Runs VerseMapper against the real verse counts of the bundled modules. */
public class VerseMapperTest {

    static int failures = 0;

    static void expect(DatabaseHelper from, DatabaseHelper to, int book, int ch, int v, int ech, int ev) {
        int[] r = VerseMapper.map(from, to, book, ch, v);
        boolean ok = r != null && r[0] == book && r[1] == ech && r[2] == ev;
        if (!ok) failures++;
        System.out.printf("%s %-5s %s %d %d:%d -> %s   expected %d:%d%n", ok ? "ok  " : "FAIL",
                from.name, to.name, book, ch, v, r == null ? "null" : r[1] + ":" + r[2], ech, ev);
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        DatabaseHelper cuv = new DatabaseHelper("CUV", dir + "/counts_CUV23.txt");
        DatabaseHelper kjv = new DatabaseHelper("KJV", dir + "/counts_KJV.txt");
        DatabaseHelper ubio = new DatabaseHelper("UBIO", dir + "/counts_UBIO88.txt");
        DatabaseHelper bdc = new DatabaseHelper("BDC", dir + "/counts_BDC24.txt");
        DatabaseHelper cslu = new DatabaseHelper("CSLU", dir + "/counts_CSLU.txt");

        System.out.println("== chapter boundaries ==");
        expect(cuv, kjv, 410, 2, 1, 1, 15);     // Nahum
        expect(kjv, cuv, 410, 1, 15, 2, 1);
        expect(cuv, kjv, 410, 2, 5, 2, 4);
        expect(cuv, kjv, 360, 4, 1, 3, 1);      // Joel
        expect(kjv, cuv, 360, 2, 28, 3, 1);
        expect(cuv, kjv, 360, 3, 5, 2, 32);
        expect(cuv, kjv, 460, 3, 19, 4, 1);     // Malachi
        expect(ubio, cuv, 460, 4, 1, 3, 19);
        expect(cuv, kjv, 390, 2, 1, 1, 17);     // Jonah
        expect(cuv, kjv, 390, 2, 2, 2, 1);
        expect(cuv, kjv, 90, 24, 1, 23, 29);    // 1 Samuel boundary after the split chapter

        System.out.println("== psalms ==");
        expect(cuv, ubio, 230, 10, 1, 9, 22);
        expect(ubio, cuv, 230, 9, 22, 10, 1);
        expect(ubio, kjv, 230, 9, 22, 10, 1);
        expect(cuv, kjv, 230, 3, 2, 3, 1);      // numbered title
        expect(kjv, cuv, 230, 3, 1, 3, 2);
        expect(cuv, kjv, 230, 3, 1, 3, 1);      // title itself clamps to verse 1
        expect(ubio, kjv, 230, 113, 9, 115, 1);
        expect(kjv, ubio, 230, 116, 10, 115, 1);
        expect(kjv, ubio, 230, 147, 12, 147, 1);
        expect(ubio, kjv, 230, 147, 1, 147, 12);
        expect(ubio, kjv, 230, 146, 1, 147, 1);
        expect(ubio, kjv, 230, 51, 3, 52, 1);   // UBIO 51 is Masoretic 52, whose Hebrew title is two verses
        expect(cuv, ubio, 230, 51, 3, 50, 3);
        expect(cuv, kjv, 230, 51, 3, 51, 1);    // Ps 51 has a two-verse title in Hebrew numbering

        System.out.println("== other books with mixed conventions ==");
        expect(ubio, cuv, 40, 25, 19, 26, 1);   // Numbers: Hebrew 25:19 starts English 26:1
        expect(ubio, cuv, 40, 30, 17, 30, 16);
        expect(cuv, ubio, 40, 30, 16, 30, 17);
        expect(ubio, cuv, 40, 30, 1, 29, 40);
        expect(ubio, cuv, 300, 5, 30, 5, 30);   // Jeremiah: UBIO joins 5:30-31
        expect(cuv, ubio, 300, 5, 31, 5, 30);
        expect(ubio, kjv, 300, 9, 25, 9, 26);   // Jeremiah 8:23/9:1 boundary after the join
        expect(kjv, ubio, 300, 9, 26, 9, 25);
        expect(cuv, kjv, 350, 2, 1, 1, 10);     // Hosea 1:10-11 / 2:1-2
        expect(cuv, kjv, 350, 12, 1, 11, 12);   // Hosea 11:12 / 12:1
        expect(cuv, kjv, 350, 14, 1, 13, 16);   // Hosea 13:16 / 14:1
        expect(kjv, cuv, 350, 13, 16, 14, 1);
        expect(kjv, cuv, 230, 23, 1, 23, 1);    // Ps 23 title: "A Psalm of David" counts as verse 1 in CUV
        expect(cuv, kjv, 230, 150, 6, 150, 6);

        System.out.println("== verse splits ==");
        expect(cuv, kjv, 90, 21, 1, 20, 42);
        expect(cuv, kjv, 90, 21, 2, 21, 1);
        expect(kjv, cuv, 90, 21, 1, 21, 2);
        expect(kjv, cuv, 90, 20, 42, 20, 42);
        expect(cuv, kjv, 110, 22, 44, 22, 43);
        expect(cuv, kjv, 110, 22, 45, 22, 44);
        expect(kjv, cuv, 110, 22, 44, 22, 45);
        expect(cuv, kjv, 130, 12, 5, 12, 4);
        expect(cuv, kjv, 130, 12, 6, 12, 5);
        expect(cuv, ubio, 130, 12, 6, 12, 5);
        expect(kjv, cuv, 160, 7, 69, 7, 68);
        expect(kjv, cuv, 160, 7, 70, 7, 69);
        expect(cuv, kjv, 160, 7, 69, 7, 70);
        expect(kjv, cuv, 290, 64, 1, 63, 19);
        expect(kjv, cuv, 290, 64, 2, 64, 1);
        expect(cuv, kjv, 290, 64, 1, 64, 2);
        expect(cuv, ubio, 290, 64, 1, 64, 2);
        expect(kjv, cuv, 510, 19, 41, 19, 40);
        expect(kjv, cuv, 510, 20, 1, 20, 1);
        expect(kjv, cuv, 540, 13, 14, 13, 13);
        expect(kjv, cuv, 540, 13, 13, 13, 12);
        expect(cuv, kjv, 540, 13, 13, 13, 14);
        expect(cuv, kjv, 710, 1, 15, 1, 14);
        expect(cuv, kjv, 730, 12, 18, 13, 1);
        expect(kjv, cuv, 730, 13, 1, 13, 1);
        expect(cuv, kjv, 730, 22, 21, 22, 21);

        System.out.println("== Church Slavonic (Septuagint psalms, mixed chapter breaks) ==");
        expect(cslu, kjv, 230, 9, 22, 10, 1);
        expect(cslu, cuv, 230, 10, 1, 11, 1);
        expect(cuv, cslu, 230, 11, 1, 10, 1);
        expect(cslu, bdc, 230, 23, 1, 24, 1);   // Psalm 23 LXX is Masoretic 24
        expect(cslu, cuv, 410, 2, 1, 2, 2);     // CSLU Nahum follows the English break
        expect(cslu, kjv, 410, 2, 1, 2, 1);
        expect(cslu, cuv, 390, 2, 1, 2, 1);     // but Jonah follows the Hebrew one
        expect(cslu, kjv, 390, 2, 1, 1, 17);
        expect(cslu, kjv, 360, 3, 1, 3, 1);     // Joel: 3 chapters like English
        expect(cslu, cuv, 360, 3, 1, 4, 1);

        System.out.println("== identity ==");
        expect(cuv, cuv, 410, 2, 5, 2, 5);
        expect(kjv, bdc, 230, 23, 1, 23, 1);
        expect(cuv, kjv, 470, 5, 3, 5, 3);

        System.out.println("== round trips over the whole Bible ==");
        DatabaseHelper[] mods = {cuv, kjv, ubio, bdc, cslu};
        for (DatabaseHelper a : mods) for (DatabaseHelper b : mods) {
            if (a == b) continue;
            int total = 0, bad = 0;
            List<String> examples = new ArrayList<>();
            for (Map.Entry<Integer, int[]> e : new LinkedHashMap<>(a.counts).entrySet()) {
                int book = e.getKey(); int[] c = e.getValue();
                if (!b.hasBook(book)) continue;
                for (int ch = 1; ch < c.length; ch++) for (int v = 1; v <= c[ch]; v++) {
                    total++;
                    int[] m = VerseMapper.map(a, b, book, ch, v);
                    int[] back = m == null ? null : VerseMapper.map(b, a, book, m[1], m[2]);
                    if (back == null || back[1] != ch || back[2] != v) {
                        bad++;
                        if (examples.size() < 8) examples.add(book + " " + ch + ":" + v + " -> " + (m == null ? "null" : m[1] + ":" + m[2]) + " -> " + (back == null ? "null" : back[1] + ":" + back[2]));
                    }
                }
            }
            System.out.printf("%s -> %s -> %s: %d verses, %d not round-tripping %s%n", a.name, b.name, a.name, total, bad, examples);
        }
        System.out.println(failures == 0 ? "ALL EXPECTATIONS PASSED" : failures + " EXPECTATION(S) FAILED");
    }
}
