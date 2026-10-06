package com.bible.reader;

import android.text.Html;
import android.text.Spanned;
import android.text.SpannedString;

import java.util.regex.Pattern;

public final class TextCleaner {

    private static final Pattern STRONG = Pattern.compile("<S>.*?</S>");
    private static final Pattern FOOTNOTE = Pattern.compile("<f>.*?</f>");
    private static final Pattern PB = Pattern.compile("<pb/>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextCleaner() {}

    public static String clean(String raw) {
        if (raw == null) return "";
        String s = PB.matcher(raw).replaceAll("");
        s = STRONG.matcher(s).replaceAll("");
        s = FOOTNOTE.matcher(s).replaceAll("");
        // Stripping tags leaves double spaces ("created  the"); collapse them the same
        // way the HTML parser would, so plain text can safely skip Html.fromHtml below.
        s = WHITESPACE.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /**
     * Verse text ready for a TextView. Html.fromHtml spins up an XML parser and is the
     * slowest step on old devices, so text with no remaining tags or entities bypasses it.
     */
    @SuppressWarnings("deprecation")
    public static Spanned toSpanned(String raw) {
        String cleaned = clean(raw);
        if (cleaned.indexOf('<') < 0 && cleaned.indexOf('&') < 0) return new SpannedString(cleaned);
        return Html.fromHtml(cleaned);
    }
}
