package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.search.Evidence;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.searchai.SearchAiPageSaver;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.WebPageReader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Results say which source they come from, and confirmed results come first. */
class VerifiedAnswersTest {

    @TempDir
    Path tmp;

    private static WebPageReader.WebPage page(String url, String text) {
        return new WebPageReader.WebPage(url, url, "<html><head><title>" + text.split("\\.")[0] + "</title></head><body><main><p>" + text + "</p></main></body></html>",
                "text/html", List.of(), null);
    }

    private List<Evidence> search(KnowledgeBase kb, String q) {
        return kb.search(new SearchRequest(q, null, null, 10, 100_000, 300, com.questioncrawler.core.search.AgeFilter.NONE)).evidence();
    }

    @Test
    void resultsNameTheirSourceAndAConfirmedResultMovesFirstAndCanBeTakenBack() {
        KnowledgeBase kb = new KnowledgeBase(CoreConfig.forStorage(tmp.resolve("store")), TestSupport.siteV1(), null,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        kb.init();
        SearchAiPageSaver saver = new SearchAiPageSaver(kb, link -> page(link, link.contains("good")
                ? "Rotating the quokka certificate. Run the quokka certificate rotation script on the gateway host and restart it."
                : "Old quokka certificate notes. The quokka certificate is rotated by hand, see the gateway host runbook for the quokka steps and more quokka text."));
        saver.save(List.of(new SearchAiPageSaver.Request("https://wiki.example.test/old", null, null), new SearchAiPageSaver.Request("https://wiki.example.test/good", null, null)));

        List<Evidence> before = search(kb, "quokka certificate rotation");
        assertTrue(before.size() >= 2);
        assertTrue(before.stream().allMatch(e -> SearchAiPageSaver.SYSTEM.equals(e.source())));
        assertTrue(before.stream().allMatch(e -> e.verified() == null));

        Evidence last = before.get(before.size() - 1);
        var v = kb.confirm(last.source(), last.questionId(), "Asha", "Worked on the gateway in November");
        assertEquals("Asha", v.by());
        List<Evidence> after = search(kb, "quokka certificate rotation");
        assertEquals(last.questionId(), after.get(0).questionId());
        assertEquals("Worked on the gateway in November", after.get(0).verified().note());
        assertEquals(1, after.get(0).rank());
        assertEquals(1, kb.confirmedItems().size());

        assertTrue(kb.unconfirm(last.source(), last.questionId()));
        assertFalse(kb.unconfirm(last.source(), last.questionId()));
        assertTrue(search(kb, "quokka certificate rotation").stream().allMatch(e -> e.verified() == null));
    }

    @Test
    void anUnknownItemCannotBeConfirmedAndTheNameDefaultsToTheComputerUser() {
        KnowledgeBase kb = new KnowledgeBase(CoreConfig.forStorage(tmp.resolve("store")), TestSupport.siteV1(), null,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        kb.init();
        assertThrows(IllegalArgumentException.class, () -> kb.confirm("nowhere", "1", "x", null));
        new SearchAiPageSaver(kb, link -> page(link, "Some page. About the walrus protocol and its handshake in detail.")).save(List.of(new SearchAiPageSaver.Request("https://wiki.example.test/w", null, null)));
        String id = kb.store().allThreads().get(0).questionId();
        var v = kb.confirm(SearchAiPageSaver.SYSTEM, id, "  ", "  ");
        assertNotNull(v.by());
        assertNull(v.note());
    }
}
