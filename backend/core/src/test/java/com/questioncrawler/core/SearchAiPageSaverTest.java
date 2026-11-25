package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.searchai.SearchAiPageSaver;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.WebPageReader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Saving the pages Search-AI results lead to (page reading is faked). */
class SearchAiPageSaverTest {

    @TempDir
    Path tmp;

    private KnowledgeBase kb() {
        return new KnowledgeBase(CoreConfig.forStorage(tmp.resolve("store")), TestSupport.siteV1(), null, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private static WebPageReader.WebPage page(String url, String html) {
        return new WebPageReader.WebPage(url, url, html, "text/html", List.of(), null);
    }

    private static final String HTML = "<html><head><title>Deploy runbook</title></head><body><main><h1>Deploy runbook</h1>"
            + "<p>To roll back the zebrafish service run the quokka rollback script and then verify health.</p></main></body></html>";

    @Test
    void savedPageIsSearchableAndListedAndSavingAgainUpdatesIt() {
        KnowledgeBase kb = kb();
        kb.init();
        SearchAiPageSaver saver = new SearchAiPageSaver(kb, link -> page(link, HTML));
        var first = saver.save(List.of(new SearchAiPageSaver.Request("https://wiki.example.test/runbook", "Runbook", "rollback")));
        assertNull(first.get(0).error());
        assertFalse(first.get(0).updated());
        assertTrue(first.get(0).characters() > 40);

        assertTrue(kb.search(new SearchRequest("quokka rollback script", null, null, 10, 100_000, 300, com.questioncrawler.core.search.AgeFilter.NONE))
                .evidence().stream().anyMatch(e -> e.questionUrl() != null && e.questionUrl().contains("wiki.example.test/runbook")));
        assertEquals(1, saver.saved().size());
        assertEquals("Deploy runbook", saver.saved().get(0).title());

        assertTrue(saver.save(List.of(new SearchAiPageSaver.Request("https://wiki.example.test/runbook", null, null))).get(0).updated());
        assertEquals(1, saver.saved().size());
    }

    @Test
    void oneUnreadablePageDoesNotStopTheOthersAndDuplicatesAreReadOnce() {
        KnowledgeBase kb = kb();
        kb.init();
        List<String> read = new java.util.ArrayList<>();
        SearchAiPageSaver saver = new SearchAiPageSaver(kb, link -> {
            read.add(link);
            if (link.contains("secret")) {
                throw new SourceException(SourceException.Kind.AUTH_FAILED, "no access");
            }
            return page(link, HTML);
        });
        var out = saver.save(List.of(
                new SearchAiPageSaver.Request("https://wiki.example.test/secret", "S", null),
                new SearchAiPageSaver.Request("https://wiki.example.test/ok", "O", null),
                new SearchAiPageSaver.Request("https://wiki.example.test/ok", "O", null),
                new SearchAiPageSaver.Request("ftp://wiki.example.test/file", "F", null)));
        assertEquals(3, out.size());
        assertEquals("no access", out.get(0).error());
        assertNull(out.get(1).error());
        assertNotNull(out.get(2).error());
        assertEquals(2, read.size());
    }

    @Test
    void emptyAndOversizedRequestsAreRejected() {
        KnowledgeBase kb = kb();
        kb.init();
        SearchAiPageSaver saver = new SearchAiPageSaver(kb, link -> page(link, HTML));
        assertThrows(IllegalArgumentException.class, () -> saver.save(List.of()));
        List<SearchAiPageSaver.Request> many = new java.util.ArrayList<>();
        for (int i = 0; i < 26; i++) {
            many.add(new SearchAiPageSaver.Request("https://x.test/" + i, null, null));
        }
        assertThrows(IllegalArgumentException.class, () -> saver.save(many));
    }
}
