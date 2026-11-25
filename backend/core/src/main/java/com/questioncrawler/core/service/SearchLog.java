package com.questioncrawler.core.service;

import com.questioncrawler.core.storage.KnowledgeStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What was searched and whether the answer was good, kept on this computer only. The "gaps" are the questions that found nothing
 * (or only weak matches) and were not answered by a later search: they show where the organisation's knowledge is missing.
 */
public final class SearchLog {

    static final String STATE = "search-log";
    static final int MAX_ENTRIES = 2000;
    static final int MAX_QUERY = 200;

    /** One search. {@code where} is "local" (this app's stored knowledge) or "search-ai". */
    public record Entry(String at, String query, String where, int results, String confidence) {}

    public record Data(List<Entry> entries) {
        public Data {
            entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        }
    }

    /** A question that stayed unanswered: how often it was asked, when last, and the best answer it ever got. */
    public record Gap(String query, int timesAsked, String lastAskedAt, String where, String bestConfidence, int bestResults) {}

    public record Report(int days, int searches, int answered, int gapCount, List<Gap> gaps) {}

    private static final List<String> ORDER = List.of("NONE", "LOW", "MEDIUM", "HIGH");

    private final KnowledgeStore store;
    private final Clock clock;

    public SearchLog(KnowledgeStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Never throws: a log problem must not break a search. */
    public synchronized void record(String query, String where, int results, String confidence) {
        try {
            String q = clean(query);
            if (q.isEmpty()) {
                return;
            }
            List<Entry> entries = store.readState(STATE, Data.class).orElse(new Data(null)).entries();
            entries.add(new Entry(clock.instant().toString(), q, where, results, confidence == null ? "NONE" : confidence));
            if (entries.size() > MAX_ENTRIES) {
                entries = new ArrayList<>(entries.subList(entries.size() - MAX_ENTRIES, entries.size()));
            }
            store.writeState(STATE, new Data(entries));
        } catch (RuntimeException ignored) {
            // the log is a convenience
        }
    }

    public synchronized void clear() {
        store.deleteState(STATE);
    }

    public synchronized Report report(int days) {
        int window = Math.min(Math.max(days, 1), 365);
        Instant since = clock.instant().minus(Duration.ofDays(window));
        Map<String, List<Entry>> byQuestion = new LinkedHashMap<>();
        int searches = 0;
        for (Entry e : store.readState(STATE, Data.class).orElse(new Data(null)).entries()) {
            if (Instant.parse(e.at()).isBefore(since)) {
                continue;
            }
            searches++;
            byQuestion.computeIfAbsent(key(e), k -> new ArrayList<>()).add(e);
        }
        List<Gap> gaps = new ArrayList<>();
        int answered = 0;
        for (List<Entry> asked : byQuestion.values()) {
            Entry last = asked.get(asked.size() - 1);
            Entry best = asked.stream().max((a, b) -> Integer.compare(rank(a.confidence()), rank(b.confidence()))).orElse(last);
            if (isGood(last)) {
                answered++; // a later search found it: not a gap any more
                continue;
            }
            gaps.add(new Gap(last.query(), asked.size(), last.at(), last.where(), best.confidence(), best.results()));
        }
        gaps.sort((a, b) -> a.timesAsked() != b.timesAsked() ? Integer.compare(b.timesAsked(), a.timesAsked()) : b.lastAskedAt().compareTo(a.lastAskedAt()));
        return new Report(window, searches, answered, gaps.size(), gaps.size() > 100 ? gaps.subList(0, 100) : gaps);
    }

    private static boolean isGood(Entry e) {
        return e.results() > 0 && rank(e.confidence()) >= 2;
    }

    private static int rank(String confidence) {
        int i = ORDER.indexOf(confidence == null ? "NONE" : confidence);
        return Math.max(i, 0);
    }

    private static String key(Entry e) {
        return e.where() + "|" + e.query().toLowerCase(Locale.ROOT);
    }

    private static String clean(String q) {
        if (q == null) {
            return "";
        }
        String t = q.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return t.length() > MAX_QUERY ? t.substring(0, MAX_QUERY) : t;
    }
}
