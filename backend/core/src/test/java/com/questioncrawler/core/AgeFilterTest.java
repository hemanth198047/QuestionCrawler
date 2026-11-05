package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.search.AgeFilter;
import com.questioncrawler.core.search.Evidence;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.FixtureSourceAdapter;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncReport;
import com.questioncrawler.core.util.AgeSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Age filters. The clock is fixed at 2025-04-10 so ages are deterministic against the fixture dates:
 * question creation dates range from 2025-01-10 (1001) to 2025-04-01 (1011).
 */
class AgeFilterTest {

    static final Instant NOW = Instant.parse("2025-04-10T00:00:00Z");

    @TempDir
    Path tmp;

    private KnowledgeBase collected() {
        KnowledgeBase kb = new KnowledgeBase(CoreConfig.forStorage(tmp.resolve("store")), TestSupport.siteV1(), null, Clock.fixed(NOW, ZoneOffset.UTC));
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        return kb;
    }

    private static Set<String> questionIds(List<Evidence> evidence) {
        return evidence.stream().map(Evidence::questionId).collect(Collectors.toSet());
    }

    private List<Evidence> search(KnowledgeBase kb, String q, AgeFilter age) {
        return kb.search(new SearchRequest(q, null, null, 50, 100_000, 300, age)).evidence();
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void agesAreParsedIntoCutoffs() {
        assertEquals(Instant.parse("2025-04-09T00:00:00Z"), AgeSpec.before("1d", NOW));
        assertEquals(Instant.parse("2025-04-09T12:00:00Z"), AgeSpec.before("12h", NOW));
        assertEquals(Instant.parse("2025-03-27T00:00:00Z"), AgeSpec.before("2w", NOW));
        assertEquals(Instant.parse("2025-01-10T00:00:00Z"), AgeSpec.before("3m", NOW));
        assertEquals(Instant.parse("2024-04-10T00:00:00Z"), AgeSpec.before("1y", NOW));
        assertEquals(Instant.parse("2025-02-28T00:00:00Z"), AgeSpec.before("1m", Instant.parse("2025-03-31T00:00:00Z")), "month arithmetic clamps to the last day");
        assertTrue(AgeSpec.isValid(" 30D "));
        for (String bad : List.of("", "30", "d", "-5d", "1.5d", "30 days", "P30D", "30x")) {
            assertFalse(AgeSpec.isValid(bad), bad);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AgeSpec.before("soon", NOW));
        assertTrue(e.getMessage().contains("30d"));
        assertThrows(IllegalArgumentException.class, () -> new AgeFilter("nonsense", null, null, null));
    }

    // ------------------------------------------------------------------ question age

    @Test
    void maxQuestionAgeKeepsOnlyRecentThreadsIncludingTheirAnswers() {
        KnowledgeBase kb = collected();
        List<Evidence> all = search(kb, "orbit", AgeFilter.NONE);
        assertTrue(questionIds(all).size() >= 9);

        List<Evidence> recent = search(kb, "orbit", new AgeFilter("30d", null, null, null));   // created on/after 2025-03-11
        assertEquals(Set.of("1008", "1009", "1011"), questionIds(recent));
        assertTrue(recent.stream().anyMatch(e -> e.type().equals("answer")), "answers of recent questions stay");
        recent.forEach(e -> assertTrue(Instant.parse(e.questionCreatedAt()).isAfter(Instant.parse("2025-03-10T23:59:59Z")), e.questionCreatedAt()));
    }

    @Test
    void minQuestionAgeKeepsOnlyOlderThreads() {
        KnowledgeBase kb = collected();
        List<Evidence> old = search(kb, "orbit", new AgeFilter(null, "60d", null, null));       // created on/before 2025-02-09
        assertEquals(Set.of("1001", "1002", "1003"), questionIds(old));
    }

    @Test
    void questionAgeRangeCombinesMinAndMax() {
        KnowledgeBase kb = collected();
        List<Evidence> window = search(kb, "orbit", new AgeFilter("70d", "30d", null, null));  // 2025-01-30 .. 2025-03-11
        assertEquals(Set.of("1003", "1004", "1005", "1006", "1007"), questionIds(window));
    }

    // ------------------------------------------------------------------ answer age

    @Test
    void maxAnswerAgeRemovesOldAnswersButKeepsTheQuestion() {
        KnowledgeBase kb = collected();
        // Answers 2001-2003 on question 1001 were created 2025-01-10 .. 2025-02-02; cutoff for 45d is 2025-02-24.
        List<Evidence> hits = search(kb, "ERR_CONN_TIMEOUT proxy timeout orbit.yaml", new AgeFilter(null, null, "45d", null));
        assertTrue(hits.stream().noneMatch(e -> "answer".equals(e.type()) && Set.of("2001", "2002", "2003").contains(e.answerId())), "old answers are filtered out");
        assertTrue(hits.stream().anyMatch(e -> "question".equals(e.type()) && e.questionId().equals("1001")), "the question itself remains");

        // Control: without the filter that same answer is found by a query that targets it.
        assertTrue(search(kb, "raise orbit.yaml timeout 120s", AgeFilter.NONE).stream().anyMatch(e -> "2001".equals(e.answerId())));
        assertTrue(search(kb, "raise orbit.yaml timeout 120s", new AgeFilter(null, null, "45d", null)).stream().noneMatch(e -> "2001".equals(e.answerId())));
    }

    @Test
    void answerFilterAppliesToEveryAnswerIndependentlyOfTheQuestionAge() {
        KnowledgeBase kb = collected();
        List<Evidence> hits = search(kb, "orbit", new AgeFilter(null, null, "45d", null));
        List<Evidence> answers = hits.stream().filter(e -> e.type().equals("answer")).toList();
        assertFalse(answers.isEmpty());
        answers.forEach(e -> assertTrue(Instant.parse(e.createdAt()).isAfter(Instant.parse("2025-02-23T23:59:59Z")), e.answerId() + " " + e.createdAt()));
    }

    @Test
    void minAnswerAgeKeepsOnlyOlderAnswers() {
        KnowledgeBase kb = collected();
        List<Evidence> hits = search(kb, "orbit", new AgeFilter(null, null, null, "45d"));
        hits.stream().filter(e -> e.type().equals("answer")).forEach(e ->
                assertTrue(Instant.parse(e.createdAt()).isBefore(Instant.parse("2025-02-24T00:00:01Z")), e.answerId() + " " + e.createdAt()));
        assertTrue(hits.stream().anyMatch(e -> "2001".equals(e.answerId())));
    }

    @Test
    void questionAndAnswerFiltersWorkTogether() {
        KnowledgeBase kb = collected();
        // Questions from the last 30 days (1008, 1009, 1011) whose answers are newer than 10 days: only 1011's answers (04-02, 04-03).
        List<Evidence> hits = search(kb, "orbit", new AgeFilter("30d", null, "10d", null));
        assertEquals(Set.of("1008", "1009", "1011"), questionIds(hits), "question docs of the three recent threads");
        List<String> answerIds = hits.stream().filter(e -> e.type().equals("answer")).map(Evidence::answerId).toList();
        assertFalse(answerIds.isEmpty());
        // Only answers created within the last 10 days survive (2015 on 04-02, 2016 on 04-03). At most two passages per
        // thread are returned, so we assert on the set of allowed ids rather than on every one being present.
        assertTrue(Set.of("2015", "2016").containsAll(answerIds), answerIds.toString());
    }

    @Test
    void filtersAreEchoedAndAgesAreShownInEvidence() {
        KnowledgeBase kb = collected();
        var response = kb.search(new SearchRequest("OrbitClient batchSize", null, null, 5, 0, 0, new AgeFilter("1y", null, null, null)));
        assertEquals("1y", response.ageFilter().maxQuestionAge());
        Evidence e = response.evidence().get(0);
        assertEquals("2025-03-10T09:00:00Z", e.questionCreatedAt());
        assertTrue(e.createdAt() != null);
    }

    @Test
    void noFilterMeansNoRestriction() {
        KnowledgeBase kb = collected();
        assertEquals(questionIds(search(kb, "orbit", AgeFilter.NONE)), questionIds(search(kb, "orbit", new AgeFilter(" ", "", null, null))));
    }

    // ------------------------------------------------------------------ unknown ages are never guessed

    @Test
    void postsWithUnknownCreationTimeAreExcludedWhileAFilterIsActive() throws Exception {
        Path site = tmp.resolve("undated");
        Files.createDirectories(site.resolve("questions"));
        Files.writeString(site.resolve("manifest.json"), "{\"files\":[\"questions/u1.json\"]}");
        Files.writeString(site.resolve("questions/u1.json"), """
                {"id":"u1","title":"Undated question about caching","body":"How does caching work?","url":"https://x.example/q/u1",
                 "tags":["orbit"],"modifiedAt":"2025-04-01T00:00:00Z",
                 "answers":[{"id":"ua1","body":"Use a cache with an undated answer.","url":"https://x.example/q/u1#ua1","accepted":true}]}""");
        KnowledgeBase kb = new KnowledgeBase(CoreConfig.forStorage(tmp.resolve("store2")),
                FixtureSourceAdapter.fromDirectories(2, 2, site), null, Clock.fixed(NOW, ZoneOffset.UTC));
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));

        assertEquals(Set.of("u1"), questionIds(search(kb, "caching", AgeFilter.NONE)));
        assertEquals(Set.of(), questionIds(search(kb, "caching", new AgeFilter("1y", null, null, null))), "unknown question age is excluded, not guessed");
        List<Evidence> answerFiltered = search(kb, "caching cache", new AgeFilter(null, null, "1y", null));
        assertTrue(answerFiltered.stream().noneMatch(e -> "answer".equals(e.type())), "answer with unknown age excluded");
        assertTrue(answerFiltered.stream().anyMatch(e -> "question".equals(e.type())), "the question is unaffected by an answer filter");
    }

    // ------------------------------------------------------------------ browsing and collection window

    @Test
    void categoryBrowsingHonoursTheQuestionAgeFilter() {
        KnowledgeBase kb = collected();
        var all = kb.category("apis-integrations").orElseThrow().questions().stream().map(q -> q.id()).collect(Collectors.toSet());
        var recent = kb.category("apis-integrations", new AgeFilter("30d", null, null, null)).orElseThrow().questions();
        assertTrue(recent.size() < all.size());
        recent.forEach(q -> assertTrue(Instant.parse(q.createdAt()).isAfter(Instant.parse("2025-03-10T23:59:59Z"))));
    }

    @Test
    void collectionWindowOnlyCollectsRecentQuestionsAndSaysSo() {
        KnowledgeBase kb = new KnowledgeBase(CoreConfig.forStorage(tmp.resolve("window")), TestSupport.siteV1(), null, Clock.fixed(NOW, ZoneOffset.UTC));
        SyncReport report = kb.sync(TestSupport.opts(SyncMode.INCREMENTAL).maxQuestionAge() == null
                ? new com.questioncrawler.core.sync.SyncOptions(List.of("orbit"), null, false, SyncMode.INCREMENTAL, null, 2, 30, "30d") : null);
        assertEquals(3, report.added());
        assertEquals(Set.of("1008", "1009", "1011"), kb.store().allThreads().stream().map(t -> t.questionId()).collect(Collectors.toSet()));
        assertTrue(report.limitations().stream().anyMatch(l -> l.contains("Collection window")), report.limitations().toString());
    }

    @Test
    void fullReconciliationDoesNotProbeStoredQuestionsThatAreOlderThanTheWindow() {
        var everything = new TestSupport.Instrumented(TestSupport.siteV1());
        KnowledgeBase kb = new KnowledgeBase(CoreConfig.forStorage(tmp.resolve("recon")), everything, null, Clock.fixed(NOW, ZoneOffset.UTC));
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        assertEquals(10, kb.store().allThreads().size());
        everything.fetchedIds.clear();

        SyncReport report = kb.sync(new com.questioncrawler.core.sync.SyncOptions(List.of("orbit"), null, false, SyncMode.FULL, null, 2, 30, "30d"));
        assertEquals(0, report.statusChanged());
        assertEquals(Set.of("1008", "1009", "1011"), Set.copyOf(everything.fetchedIds), "older stored questions are out of scope, not 'missing'");
        assertEquals(10, kb.store().allThreads().size());
        assertTrue(kb.store().allThreads().stream().allMatch(t -> t.status().isRetrievable()));
    }

    @Test
    void invalidCollectionWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new com.questioncrawler.core.sync.SyncOptions(List.of("orbit"), null, false, SyncMode.FULL, null, 2, 30, "recently"));
    }

    @Test
    void wideningTheCollectionWindowBackfillsOlderQuestionsOnTheNextIncrementalSync() {
        KnowledgeBase kb = new KnowledgeBase(CoreConfig.forStorage(tmp.resolve("widen")), TestSupport.siteV1(), null, Clock.fixed(NOW, ZoneOffset.UTC));
        var narrow = new com.questioncrawler.core.sync.SyncOptions(List.of("orbit"), null, false, SyncMode.INCREMENTAL, null, 2, 30, "30d");
        assertEquals(3, kb.sync(narrow).added());

        // Same window again: a normal incremental run (nothing new, nothing backfilled).
        SyncReport same = kb.sync(narrow);
        assertEquals(0, same.added());
        assertTrue(same.warnings().stream().noneMatch(w -> w.contains("widened")));

        // Widen to a year: a plain INCREMENTAL sync must now pick up the older questions too.
        var wide = new com.questioncrawler.core.sync.SyncOptions(List.of("orbit"), null, false, SyncMode.INCREMENTAL, null, 2, 30, "1y");
        SyncReport backfill = kb.sync(wide);
        assertEquals(7, backfill.added(), "the 7 older questions");
        assertTrue(backfill.warnings().stream().anyMatch(w -> w.contains("widened")), backfill.warnings().toString());
        assertNull(backfill.since(), "no 'changed since' bound during a backfill");
        assertEquals(10, kb.store().allThreads().size());

        // Afterwards it is incremental again.
        SyncReport after = kb.sync(wide);
        assertEquals(0, after.added());
        assertTrue(after.since() != null);
    }
}
