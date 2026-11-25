package com.questioncrawler.core.service;

import com.questioncrawler.core.search.Verification;
import com.questioncrawler.core.storage.KnowledgeStore;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * People's confirmations that a stored item is correct ("confirmed by Asha on 2026-11-25: this fixed it"). Kept in one small
 * file next to the knowledge store, so a confirmation survives re-collection and re-indexing. Verified items are shown first.
 */
public final class VerifiedAnswers {

    static final String STATE = "verified-answers";
    public static final int MAX_NOTE = 500;
    public static final int MAX_NAME = 80;

    /** The stored file. */
    public record Data(Map<String, Verification> items) {
        public Data {
            items = items == null ? new LinkedHashMap<>() : new LinkedHashMap<>(items);
        }
    }

    private final KnowledgeStore store;
    private final Clock clock;

    public VerifiedAnswers(KnowledgeStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public static String keyOf(String source, String questionId) {
        return source + ":" + questionId;
    }

    public synchronized Map<String, Verification> all() {
        return store.readState(STATE, Data.class).orElse(new Data(null)).items();
    }

    public synchronized Verification verify(String source, String questionId, String by, String note) {
        if (source == null || source.isBlank() || questionId == null || questionId.isBlank()) {
            throw new IllegalArgumentException("Say which item to confirm.");
        }
        String name = clean(by, MAX_NAME);
        if (name.isEmpty()) {
            name = clean(System.getProperty("user.name"), MAX_NAME);
        }
        if (name.isEmpty()) {
            name = "unknown";
        }
        String text = clean(note, MAX_NOTE);
        Verification v = new Verification(name, clock.instant().toString(), text.isEmpty() ? null : text);
        Map<String, Verification> items = all();
        items.put(keyOf(source, questionId), v);
        store.writeState(STATE, new Data(items));
        return v;
    }

    /** True when a confirmation existed. */
    public synchronized boolean remove(String source, String questionId) {
        Map<String, Verification> items = all();
        boolean had = items.remove(keyOf(source, questionId)) != null;
        if (had) {
            store.writeState(STATE, new Data(items));
        }
        return had;
    }

    private static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
