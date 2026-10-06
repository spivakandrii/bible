package com.bible.reader;

import java.util.Arrays;

/**
 * Maps a verse reference from one module's numbering to another's. Translations disagree on
 * chapter boundaries (Hebrew vs English division: Joel 2:28 vs 3:1, Nahum 1:15 vs 2:1, ...),
 * on psalm numbering (Septuagint/Russian grouping merges Psalms 9+10, 114+115 and splits 116
 * and 147) and on whether psalm titles count as verse 1. Everything here is derived from the
 * per-chapter verse counts of the two modules; the only table is the Masoretic psalm sizes.
 */
final class VerseMapper {

    private static final int PSALMS = 230;

    /** Verses per psalm in Masoretic numbering with unnumbered titles (KJV), index = psalm. */
    private static final int[] PSALM_BASE = {0,
            6, 12, 8, 8, 12, 10, 17, 9, 20, 18, 7, 8, 6, 7, 5, 11, 15, 50, 14, 9,
            13, 31, 6, 10, 22, 12, 14, 9, 11, 12, 24, 11, 22, 22, 28, 12, 40, 22, 13, 17,
            13, 11, 5, 26, 17, 11, 9, 14, 20, 23, 19, 9, 6, 7, 23, 13, 11, 11, 17, 12,
            8, 12, 11, 10, 13, 20, 7, 35, 36, 5, 24, 20, 28, 23, 10, 12, 20, 72, 13, 19,
            16, 8, 18, 12, 13, 17, 7, 18, 52, 17, 16, 15, 5, 23, 11, 13, 12, 9, 9, 5,
            8, 28, 22, 35, 45, 48, 43, 13, 31, 7, 10, 10, 9, 8, 18, 19, 2, 29, 176, 7,
            8, 9, 4, 8, 5, 6, 5, 6, 8, 8, 3, 18, 3, 3, 21, 26, 9, 8, 24, 13,
            10, 7, 12, 15, 21, 10, 20, 14, 9, 6};

    private VerseMapper() {}

    /** {book, chapter, verse} in the numbering of {@code to}, or null if {@code to} lacks the book. */
    static int[] map(DatabaseHelper from, DatabaseHelper to, int book, int chapter, int verse) {
        if (!to.hasBook(book)) return null;
        if (from == to) return new int[]{book, chapter, verse};
        int[] ca = from.getChapterVerseCounts(book), cb = to.getChapterVerseCounts(book);
        if (ca.length < 2 || cb.length < 2 || Arrays.equals(ca, cb)) return clampTo(cb, book, chapter, verse);
        if (book == PSALMS && ca.length >= 151 && cb.length >= 151) return mapPsalm(ca, cb, chapter, verse);
        return mapByCounts(ca, cb, book, chapter, verse);
    }

    static int[] map(DatabaseHelper from, DatabaseHelper to, int[] ref) {
        return map(from, to, ref[0], ref[1], ref[2]);
    }

    // --- Chapter-boundary differences ---

    private static int[] mapByCounts(int[] ca, int[] cb, int book, int chapter, int verse) {
        int na = ca.length - 1, nb = cb.length - 1;
        if (chapter < 1 || chapter > na) return clampTo(cb, book, chapter, verse);
        if (na != nb) {
            // Different chapter count (Joel 3 vs 4, Malachi 4 vs 3): if the book has the same
            // number of verses, only boundaries moved, so the verse ordinal is exact.
            if (sum(ca) == sum(cb)) return byOrdinal(ca, cb, book, chapter, verse, 1, na, 1, nb);
            return clampTo(cb, book, chapter, verse);
        }
        // Same chapter count. Chapters where the cumulative counts agree are anchors; between two
        // anchors the difference is a moved boundary and the ordinal is exact. A run that never
        // balances out is a verse split or merge, where keeping the chapter number is closest.
        int d = 0, start = 1;
        for (int i = 1; i < chapter; i++) {
            d += ca[i] - cb[i];
            if (d == 0) start = i + 1;
        }
        if (d == 0 && ca[chapter] == cb[chapter]) return clampTo(cb, book, chapter, verse);
        int end = -1;
        for (int i = chapter; i <= na; i++) {
            d += ca[i] - cb[i];
            if (d == 0) { end = i; break; }
        }
        if (end == -1) return clampTo(cb, book, chapter, verse);
        return byOrdinal(ca, cb, book, chapter, verse, start, end, start, end);
    }

    /** Maps by verse ordinal inside chapters [sa..ea] of A onto chapters [sb..eb] of B. */
    private static int[] byOrdinal(int[] ca, int[] cb, int book, int chapter, int verse,
                                   int sa, int ea, int sb, int eb) {
        int ordinal = 0;
        for (int i = sa; i < chapter && i <= ea; i++) ordinal += ca[i];
        ordinal += Math.max(1, verse);
        int j = sb;
        while (j < eb && ordinal > cb[j]) { ordinal -= cb[j]; j++; }
        return new int[]{book, j, Math.max(1, Math.min(ordinal, cb[j]))};
    }

    private static int[] clampTo(int[] cb, int book, int chapter, int verse) {
        int nb = cb.length - 1;
        if (nb < 1) return new int[]{book, Math.max(1, chapter), Math.max(1, verse)};
        int ch = Math.max(1, Math.min(chapter, nb));
        int max = cb[ch] > 0 ? cb[ch] : 1;
        return new int[]{book, ch, Math.max(1, Math.min(verse, max))};
    }

    private static int sum(int[] c) {
        int s = 0;
        for (int v : c) s += v;
        return s;
    }

    // --- Psalms ---

    /** Septuagint grouping: Psalm 9 holds Masoretic 9 and 10 together (39 verses vs 20). */
    private static boolean isSeptuagint(int[] c) {
        return c[9] >= 30;
    }

    private static int[] mapPsalm(int[] ca, int[] cb, int chapter, int verse) {
        if (chapter < 1 || chapter > 150) return clampTo(cb, PSALMS, chapter, verse);
        boolean lxxA = isSeptuagint(ca), lxxB = isSeptuagint(cb);
        int[] masA = masoreticCounts(ca, lxxA), masB = masoreticCounts(cb, lxxB);
        // 1. Masoretic psalm number and verse as counted in module A.
        int[] m = lxxA ? lxxToMasoretic(ca, masA, chapter, verse) : new int[]{chapter, verse};
        int psalm = m[0];
        if (psalm < 1 || psalm > 150) return clampTo(cb, PSALMS, chapter, verse);
        // 2. Titles: a module numbering them has 1-2 verses more than the Masoretic base.
        int titlesA = Math.max(0, masA[psalm] - PSALM_BASE[psalm]);
        int titlesB = Math.max(0, masB[psalm] - PSALM_BASE[psalm]);
        int v = m[1] - titlesA + titlesB;
        v = Math.max(1, Math.min(v, Math.max(1, masB[psalm])));
        // 3. Back to module B's own numbering.
        if (lxxB) {
            int[] r = masoreticToLxx(cb, masB, psalm, v);
            return new int[]{PSALMS, r[0], r[1]};
        }
        return new int[]{PSALMS, psalm, v};
    }

    /** Verse count per Masoretic psalm, index 1..150, derived from the module's own chapters. */
    private static int[] masoreticCounts(int[] c, boolean lxx) {
        int[] m = new int[151];
        if (!lxx) {
            for (int i = 1; i <= 150; i++) m[i] = c[i];
            return m;
        }
        for (int i = 1; i <= 8; i++) m[i] = c[i];
        m[10] = PSALM_BASE[10];
        m[9] = c[9] - m[10];
        for (int i = 11; i <= 113; i++) m[i] = c[i - 1];
        m[115] = PSALM_BASE[115];
        m[114] = c[113] - m[115];
        m[116] = c[114] + c[115];
        for (int i = 117; i <= 146; i++) m[i] = c[i - 1];
        m[147] = c[146] + c[147];
        for (int i = 148; i <= 150; i++) m[i] = c[i];
        return m;
    }

    private static int[] lxxToMasoretic(int[] c, int[] m, int ch, int v) {
        if (ch <= 8) return new int[]{ch, v};
        if (ch == 9) return v <= m[9] ? new int[]{9, v} : new int[]{10, v - m[9]};
        if (ch <= 112) return new int[]{ch + 1, v};
        if (ch == 113) return v <= m[114] ? new int[]{114, v} : new int[]{115, v - m[114]};
        if (ch == 114) return new int[]{116, v};
        if (ch == 115) return new int[]{116, v + c[114]};
        if (ch <= 145) return new int[]{ch + 1, v};
        if (ch == 146) return new int[]{147, v};
        if (ch == 147) return new int[]{147, v + c[146]};
        return new int[]{ch, v};
    }

    private static int[] masoreticToLxx(int[] c, int[] m, int psalm, int v) {
        if (psalm <= 8) return new int[]{psalm, v};
        if (psalm == 9) return new int[]{9, v};
        if (psalm == 10) return new int[]{9, v + m[9]};
        if (psalm <= 113) return new int[]{psalm - 1, v};
        if (psalm == 114) return new int[]{113, v};
        if (psalm == 115) return new int[]{113, v + m[114]};
        if (psalm == 116) return v <= c[114] ? new int[]{114, v} : new int[]{115, v - c[114]};
        if (psalm <= 146) return new int[]{psalm - 1, v};
        if (psalm == 147) return v <= c[146] ? new int[]{146, v} : new int[]{147, v - c[146]};
        return new int[]{psalm, v};
    }
}
