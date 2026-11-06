package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.search.Evidence;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.search.SearchResponse;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.sync.SyncMode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchTest {

    @TempDir
    Path tmp;

    KnowledgeBase kb;

    @BeforeEach
    void collect() {
        kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
    }

    private SearchResponse search(String q) {
        return kb.search(SearchRequest.of(q));
    }

    @Test
    void exactErrorMessageFindsTheRightQuestionFirst() {
        SearchResponse r = search("ERR_CONN_TIMEOUT: connection timed out after 30 seconds");
        assertFalse(r.evidence().isEmpty());
        assertEquals("1001", r.evidence().get(0).questionId());
        assertEquals("HIGH", r.confidence());
    }

    @Test
    void errorCodesAreSearchable() {
        SearchResponse r = search("ORB-1042");
        assertEquals("1002", r.evidence().get(0).questionId());
    }

    @Test
    void codeIdentifiersAndQualifiedSymbolsAreSearchable() {
        assertEquals("1011", search("ManifestLoader.loadFromPath").evidence().get(0).questionId());
        assertEquals("1007", search("OrbitClient.builder().batchSize(500)").evidence().get(0).questionId());
        assertTrue(search("batchSize").evidence().stream().anyMatch(e -> e.questionId().equals("1007")));
        assertTrue(search("batch size").evidence().stream().anyMatch(e -> e.questionId().equals("1007")),
                "camelCase identifiers are also reachable through their parts");
    }

    @Test
    void naturalLanguageQueriesWork() {
        SearchResponse r = search("how do I configure SSO login");
        assertEquals("1003", r.evidence().get(0).questionId());
    }

    @Test
    void evidenceCarriesCorrectUrlsAndMetadata() {
        SearchResponse r = kb.search(new SearchRequest("raise the client timeout orbit.yaml", null, null, 10, 0, 0));
        Evidence answer = r.evidence().stream().filter(e -> "answer".equals(e.type()) && "2001".equals(e.answerId())).findFirst().orElseThrow();
        assertEquals("https://qa.example.internal/questions/1001#answer-2001", answer.sourceUrl());
        assertEquals("https://qa.example.internal/questions/1001", answer.questionUrl());
        assertEquals("1001", answer.questionId());
        assertTrue(answer.accepted());
        assertEquals(12, answer.votes());
        assertEquals("dana", answer.author());
        assertEquals("2025-01-12T08:00:00Z", answer.sourceModifiedAt());
        assertNotNull(answer.lastSyncedAt());
        assertFalse(answer.categories().isEmpty());
        assertTrue(answer.untrusted());
        assertTrue(answer.passage().contains("timeout: 120s"));

        for (Evidence e : r.evidence()) {
            if ("question".equals(e.type())) {
                assertEquals("https://qa.example.internal/questions/" + e.questionId(), e.sourceUrl());
            } else {
                assertTrue(e.sourceUrl().endsWith("#answer-" + e.answerId()), e.sourceUrl());
            }
        }
    }

    @Test
    void unacceptedAnswersAreNotDiscarded() {
        SearchResponse r = kb.search(new SearchRequest("proxy NO_PROXY ERR_CONN_TIMEOUT", null, null, 10, 0, 0));
        assertTrue(r.evidence().stream().anyMatch(e -> "2002".equals(e.answerId()) && !e.accepted()));
    }

    @Test
    void everyMatchingAnswerForAQuestionIsReturned() {
        // Question 1001 has several answers that mention timeout-related fixes; search must not stop after one.
        SearchResponse r = kb.search(new SearchRequest("timeout", null, null, 20, 0, 0));
        long answersOn1001 = r.evidence().stream()
                .filter(e -> "1001".equals(e.questionId()) && "answer".equals(e.type()))
                .count();
        assertTrue(answersOn1001 >= 2, "expected multiple answers for question 1001, got " + answersOn1001
                + " in " + r.evidence().stream().map(e -> e.type() + ":" + e.answerId()).toList());
        // Each answer keeps its own author so Explore can name who wrote it.
        var authors = r.evidence().stream()
                .filter(e -> "1001".equals(e.questionId()) && "answer".equals(e.type()))
                .map(Evidence::author)
                .toList();
        assertTrue(authors.contains("dana") && authors.contains("li"), "expected distinct answer authors, got " + authors);
    }

    @Test
    void tagAndCategoryFiltersApply() {
        SearchResponse tagged = kb.search(new SearchRequest("timeout", List.of("helm"), null, 10, 0, 0));
        assertTrue(tagged.evidence().stream().allMatch(e -> e.tags().contains("helm")));
        SearchResponse byTag = kb.search(new SearchRequest("timeout", List.of("TIMEOUT"), null, 10, 0, 0));
        assertFalse(byTag.evidence().isEmpty(), "tag filter is case-insensitive");
        assertTrue(byTag.evidence().stream().allMatch(e -> e.tags().contains("timeout")));

        SearchResponse byCategory = kb.search(new SearchRequest("orbit", null, List.of("deployment"), 10, 0, 0));
        assertFalse(byCategory.evidence().isEmpty());
        assertTrue(byCategory.evidence().stream().allMatch(e -> e.categories().contains("deployment")));
    }

    @Test
    void userTextIsDataNotQuerySyntax() {
        for (String hostile : List.of("\"unbalanced quote", "(((", "timeout AND OR NOT", "title:foo^9 *:*", "a\\b[c]{d}", "~~~ ??? ***", "   ", "", "-- ; DROP TABLE x;")) {
            SearchResponse r = search(hostile);   // must not throw
            assertNotNull(r);
        }
        assertEquals(0, search("   ").resultCount());
    }

    @Test
    void topKAndOutputBudgetAreRespected() {
        SearchResponse one = kb.search(new SearchRequest("orbit", null, null, 1, 0, 0));
        assertEquals(1, one.resultCount());

        SearchResponse tight = kb.search(new SearchRequest("orbit timeout", null, null, 20, 400, 300));
        int total = tight.evidence().stream().mapToInt(e -> e.passage().length()).sum();
        assertTrue(tight.resultCount() >= 1);
        assertTrue(tight.evidence().get(0).passage().length() <= 340, "passages are bounded");
        assertTrue(total <= 400 + tight.evidence().get(0).passage().length(), "budget bounds the total output: " + total);
    }

    @Test
    void questionsWithoutAnyEvidenceReturnNothing() {
        SearchResponse r = search("kubernetes pod eviction policy");
        assertEquals(0, r.resultCount());
        assertEquals("NONE", r.confidence());
    }

    @Test
    void contentOutsideTheCollectionScopeIsNotSearchable() {
        assertEquals(0, search("payroll export totals ledger rounding").resultCount());
    }

    @Test
    void searchingNeverTriggersCollection() {
        var adapter = new TestSupport.Instrumented(TestSupport.siteV1());
        KnowledgeBase other = TestSupport.kb(tmp, adapter);
        other.search(SearchRequest.of("timeout"));
        assertEquals(0, adapter.listCalls.get());
        assertEquals(0, adapter.fetchCalls.get());
    }

    @Test
    void evidenceIncludesFreshnessOfTheStore() {
        SearchResponse r = search("timeout");
        assertNotNull(r.freshness());
        assertNotNull(r.freshness().lastSuccessfulSyncAt());
        assertFalse(r.freshness().stale());
    }
}
