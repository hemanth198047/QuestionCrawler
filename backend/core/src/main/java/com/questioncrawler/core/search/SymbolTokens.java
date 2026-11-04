package com.questioncrawler.core.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenization tuned for technical text. Error codes, identifiers and code symbols must be
 * searchable both as a whole ({@code ora-12154}, {@code getuserbyid}, {@code err_conn_reset}) and by
 * their parts ({@code get user by id}). The same expansion is applied at index time and query time,
 * so phrase queries for exact error messages line up.
 */
public final class SymbolTokens {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_][\\p{L}\\p{N}_.:/\\-]*");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final int MAX_TOKEN = 200;

    static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "could", "do", "does", "for", "from",
            "how", "i", "if", "in", "into", "is", "it", "me", "my", "no", "not", "of", "on", "or", "our", "should",
            "so", "that", "the", "their", "then", "there", "these", "this", "to", "was", "we", "what", "when",
            "where", "which", "who", "why", "will", "with", "would", "you", "your");

    private SymbolTokens() {}

    /** A query word with its whole form and its parts. */
    public record Word(String whole, List<String> parts) {}

    /** Space-joined token stream suitable for a whitespace-analyzed Lucene field. */
    public static String expandToString(String text) {
        return String.join(" ", expand(text));
    }

    public static List<String> expand(String text) {
        List<String> tokens = new ArrayList<>();
        for (Word w : words(text)) {
            tokens.add(w.whole());
            tokens.addAll(w.parts());
        }
        return tokens;
    }

    public static List<Word> words(String text) {
        List<Word> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String raw = trimTrailingPunctuation(m.group());
            if (raw.isEmpty() || raw.length() > MAX_TOKEN) {
                continue;
            }
            String whole = raw.toLowerCase(Locale.ROOT);
            result.add(new Word(whole, parts(raw, whole)));
        }
        return result;
    }

    private static List<String> parts(String raw, String whole) {
        Set<String> parts = new LinkedHashSet<>();
        for (String piece : raw.split("[_.:/\\-]+")) {
            if (piece.isEmpty()) {
                continue;
            }
            for (String sub : CAMEL.split(piece)) {
                String lower = sub.toLowerCase(Locale.ROOT);
                if (!lower.isEmpty() && !lower.equals(whole)) {
                    parts.add(lower);
                }
            }
        }
        return List.copyOf(parts);
    }

    private static String trimTrailingPunctuation(String s) {
        int end = s.length();
        while (end > 0 && ".:/-".indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Query words without stop words (stop words stay in phrase queries and in the index). */
    public static List<Word> contentWords(String query) {
        return words(query).stream().filter(w -> !STOPWORDS.contains(w.whole())).toList();
    }
}
