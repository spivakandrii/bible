package com.bible.reader;

import android.text.Html;
import android.text.Spanned;

import java.util.regex.Pattern;

public final class TextCleaner {

    private static final Pattern STRONG = Pattern.compile("<S>.*?</S>");
    private static final Pattern FOOTNOTE = Pattern.compile("<f>.*?</f>");
    private static final Pattern PB = Pattern.compile("<pb/>");

    private TextCleaner() {}

    public static String clean(String raw) {
        if (raw == null) return "";
        String s = PB.matcher(raw).replaceAll("");
        s = STRONG.matcher(s).replaceAll("");
        s = FOOTNOTE.matcher(s).replaceAll("");
        return s.trim();
    }

    @SuppressWarnings("deprecation")
    public static Spanned toSpanned(String raw) {
        String cleaned = clean(raw);
        return Html.fromHtml(cleaned);
    }
}
