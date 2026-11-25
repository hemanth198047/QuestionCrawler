package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.service.SearchLog;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The gap report: unanswered questions, counted, and cleared once a later search answers them. */
class SearchLogTest {

    @TempDir
    Path tmp;

    private SearchLog log() {
        KnowledgeBase kb = new KnowledgeBase(CoreConfig.forStorage(tmp.resolve("store")), TestSupport.siteV1(), null,
                Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC));
        kb.init();
        return kb.searchLog();
    }

    @Test
    void unansweredQuestionsAreCountedAndAnAnsweredOneIsNotAGap() {
        SearchLog log = log();
        log.record("How to rotate  the VPN cert", "local", 0, "NONE");
        log.record("how to rotate the vpn cert", "local", 2, "LOW");
        log.record("deploy runbook", "local", 5, "HIGH");
        log.record("billing export", "local", 3, "MEDIUM");
        log.record("billing export", "local", 0, "NONE");
        log.record("billing export", "local", 4, "HIGH"); // answered later
        log.record("tableflip", "search-ai", 0, "NONE");

        SearchLog.Report r = log.report(30);
        assertEquals(7, r.searches());
        assertEquals(2, r.answered());
        assertEquals(2, r.gapCount());
        assertEquals("how to rotate the vpn cert", r.gaps().get(0).query().toLowerCase());
        assertEquals(2, r.gaps().get(0).timesAsked());
        assertEquals("LOW", r.gaps().get(0).bestConfidence());
        assertEquals("search-ai", r.gaps().get(1).where());
    }

    @Test
    void blankQueriesAreIgnoredTheLogIsCappedAndCanBeCleared() {
        SearchLog log = log();
        log.record("  ", "local", 0, "NONE");
        for (int i = 0; i < 2100; i++) {
            log.record("q" + i, "local", 0, "NONE");
        }
        assertEquals(2000, log.report(30).searches());
        assertTrue(log.report(30).gaps().size() <= 100);
        log.clear();
        assertEquals(0, log.report(30).searches());
    }
}
