package com.stockmaxxer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;

/**
 * Filing text as pure functions (no database, no network): HTML to normalized text, the Item 1A
 * (Risk Factors) section, and the sentences that are new since the previous filing.
 */
public final class Filings {

    // The section heading: "Item 1A" right before "Risk Factors". A cross-reference such as
    // "Item 1A of our 2024 Form 10-K" is not a heading, so it never starts a span.
    private static final Pattern ITEM_1A = Pattern.compile(
            "item\\s*1a\\s*[.:\\u2013\\u2014-]?\\s*risk\\s+factors", Pattern.CASE_INSENSITIVE);
    // Item 1A ends at Item 1B (or 1C, or 2) in a 10-K, and at Part II Item 2 in a 10-Q.
    private static final Pattern END_10K = Pattern.compile("item\\s*(1b|1c|2)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_10Q = Pattern.compile("item\\s*2\\b", Pattern.CASE_INSENSITIVE);

    private Filings() {}

    /** The whole document as one line of plain text. Every check (diff, quotes) works in this text. */
    public static String normalize(String html) {
        return normalizeText(Jsoup.parse(html).text());
    }

    /** Non-breaking spaces become spaces, curly quotes become straight, and whitespace runs become one space. */
    public static String normalizeText(String s) {
        return s.replace(' ', ' ')
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * The Item 1A section: for each "Item 1A. Risk Factors" heading, the text up to the next end marker. A span
     * that contains another heading is skipped, and so is a span under 10 words: that is a contents line such as
     * "Item 1A. Risk Factors Page 41", not a section. The longest span left wins. Empty if there is no section
     * (Intel's filings, for one, have only the contents line). The shortest real section seen, a 10-Q's "no
     * material changes" paragraph, is about 20 words.
     */
    public static String item1a(String text, String form) {
        Pattern end = form.equals("10-K") ? END_10K : END_10Q;
        String best = "";
        Matcher start = ITEM_1A.matcher(text);
        while (start.find()) {
            Matcher stop = end.matcher(text);
            if (!stop.find(start.end())) {
                continue;
            }
            Matcher inner = ITEM_1A.matcher(text).region(start.end(), stop.start());
            if (inner.find()) {
                continue;
            }
            String span = text.substring(start.start(), stop.start()).trim();
            if (span.split("\\s+").length >= 10 && span.length() > best.length()) {
                best = span;
            }
        }
        return best;
    }

    /** Sentences of {@code newText} where fewer than half of the word 5-grams appear anywhere in {@code oldText}. */
    public static List<String> newSentences(String oldText, String newText) {
        Set<String> old = new HashSet<>(grams(oldText));
        List<String> result = new ArrayList<>();
        for (String sentence : sentences(newText)) {
            List<String> g = grams(sentence);
            if (g.isEmpty()) {
                continue;   // shorter than 5 words: a heading or a fragment
            }
            long seen = g.stream().filter(old::contains).count();
            if (seen * 2 < g.size()) {
                result.add(sentence);
            }
        }
        return result;
    }

    /** Split after . ! or ? when the next sentence starts with a capital letter, a quote or a bracket. */
    static List<String> sentences(String text) {
        return List.of(text.split("(?<=[.!?])\\s+(?=[A-Z\"(])"));
    }

    /** Every run of 5 consecutive lower-case words. */
    static List<String> grams(String text) {
        List<String> words = new ArrayList<>();
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i + 5 <= words.size(); i++) {
            result.add(String.join(" ", words.subList(i, i + 5)));
        }
        return result;
    }
}
