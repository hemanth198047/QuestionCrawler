package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.model.ThreadStatus;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.Page;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.storage.StoreException;
import com.questioncrawler.core.sync.Checkpoint;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncServiceTest {

    @TempDir
    Path tmp;

    private ThreadRecord thread(KnowledgeBase kb, String id) {
        return kb.store().loadThread("fixture", id).orElseThrow();
    }

    @Test
    void initialSyncPaginatesQuestionsAndAnswersAndKeepsAcceptedAndUnaccepted() {
        var adapter = new TestSupport.Instrumented(TestSupport.siteV1());
        KnowledgeBase kb = TestSupport.kb(tmp, adapter);
        SyncReport report = kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));

        assertEquals(SyncReport.COMPLETE, report.outcome());
        assertEquals(10, report.added());                 // 10 questions carry the tag; the payroll one does not
        assertEquals(5, report.pagesListed());            // page size 2 -> five listing pages
        assertTrue(report.listingComplete());
        assertEquals(10, kb.store().allThreads().size());

        List<Answer> answers = thread(kb, "1001").question().answers();
        assertEquals(3, answers.size());                  // 3 answers over 2 answer pages
        assertEquals(1, answers.stream().filter(Answer::accepted).count());
        assertEquals(2, answers.stream().filter(a -> !a.accepted()).count());
        assertTrue(answers.stream().allMatch(a -> a.url().contains("#answer-" + a.id())));
        assertTrue(adapter.answerCalls.get() > 10);       // answers were paged separately from questions
    }

    @Test
    void unansweredQuestionsAreStored() {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        ThreadRecord t = thread(kb, "1005");
        assertTrue(t.question().answers().isEmpty());
        assertEquals(ThreadStatus.ACTIVE, t.status());
        assertFalse(t.question().hasAcceptedAnswer());
    }

    @Test
    void codeBlocksAndLinksSurviveNormalization() {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        String body = thread(kb, "1001").question().body();
        assertTrue(body.contains("```text\nERR_CONN_TIMEOUT: connection timed out after 30 seconds\n\tat com.example"), body);
        assertTrue(body.contains("[the slow CSV import question](https://qa.example.internal/questions/1007)"), body);
        String installer = thread(kb, "1002").question().body();
        assertFalse(installer.contains("track('view')"), "script chrome must be removed");
        assertFalse(installer.contains("Share | Edit | Flag"), "navigation chrome must be removed");
        assertTrue(installer.contains("| JDK | 17+ |"), installer);
    }

    @Test
    void duplicateIdsInListingAndAnswersAreStoredOnce() {
        var adapter = new TestSupport.Instrumented(TestSupport.siteV1());
        adapter.listTransform = page -> {
            List<com.questioncrawler.core.source.QuestionSummary> doubled = new ArrayList<>(page.items());
            doubled.addAll(page.items());
            return new Page<>(doubled, page.nextCursor());
        };
        adapter.answerTransform = page -> {
            List<Answer> doubled = new ArrayList<>(page.items());
            doubled.addAll(page.items());
            return new Page<>(doubled, page.nextCursor());
        };
        KnowledgeBase kb = TestSupport.kb(tmp, adapter);
        SyncReport report = kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        assertEquals(10, report.added());
        assertEquals(10, report.questionsListed());
        assertTrue(report.duplicateListings() >= 10);
        assertEquals(3, thread(kb, "1001").question().answers().size());
        assertEquals(10, kb.store().allThreads().size());
    }

    @Test
    void incrementalSyncOnlyFetchesChangedQuestionsAndUpdatesEdits() {
        KnowledgeBase first = TestSupport.kb(tmp, TestSupport.siteV1());
        first.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        String hashBefore = thread(first, "1001").contentHash();

        var v2 = new TestSupport.Instrumented(TestSupport.siteV2());
        KnowledgeBase second = TestSupport.kb(tmp, v2);
        SyncReport report = second.sync(TestSupport.opts(SyncMode.INCREMENTAL));

        assertEquals(SyncReport.COMPLETE, report.outcome());
        assertEquals(1, report.added());      // 1012
        assertEquals(2, report.updated());    // 1001 (edit + new answer), 1005 (first answer)
        assertTrue(report.since() != null, "incremental run must use the checkpoint");
        // Only changed questions were fetched: 1001, 1005, 1012 (+ 1011 inside the overlap window, unchanged).
        Set<String> fetched = Set.copyOf(v2.fetchedIds);
        assertTrue(fetched.containsAll(Set.of("1001", "1005", "1012")), fetched.toString());
        assertFalse(fetched.contains("1002"));
        assertFalse(fetched.contains("1004"));

        assertTrue(!hashBefore.equals(thread(second, "1001").contentHash()));
        assertEquals(4, thread(second, "1001").question().answers().size());
        assertEquals(1, thread(second, "1005").question().answers().size());
    }

    @Test
    void incrementalSyncDoesNotTreatMissingItemsAsDeleted() {
        KnowledgeBase first = TestSupport.kb(tmp, TestSupport.siteV1());
        first.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        KnowledgeBase second = TestSupport.kb(tmp, TestSupport.siteV2());
        SyncReport report = second.sync(TestSupport.opts(SyncMode.INCREMENTAL));

        assertEquals(0, report.statusChanged());
        assertEquals(ThreadStatus.ACTIVE, thread(second, "1007").status());   // deleted on the site, but unknown to an incremental sync
        assertEquals(ThreadStatus.ACTIVE, thread(second, "1009").status());
        assertTrue(report.completeness().startsWith("INCREMENTAL"), report.completeness());
        assertFalse(report.completeness().contains("COMPLETE_FOR_ACCESSIBLE_SCOPE"));
    }

    @Test
    void fullReconciliationDistinguishesDeletedInaccessibleAndNoLongerMatching() {
        KnowledgeBase first = TestSupport.kb(tmp, TestSupport.siteV1());
        first.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        KnowledgeBase second = TestSupport.kb(tmp, TestSupport.siteV2());
        SyncReport report = second.sync(TestSupport.opts(SyncMode.FULL));

        assertEquals(SyncReport.COMPLETE, report.outcome());
        assertEquals(ThreadStatus.DELETED, thread(second, "1007").status());
        assertEquals(ThreadStatus.INACCESSIBLE, thread(second, "1009").status());
        assertEquals(ThreadStatus.NO_LONGER_MATCHING, thread(second, "1003").status());
        assertEquals(3, report.statusChanged());
        assertTrue(report.completeness().startsWith("COMPLETE_FOR_ACCESSIBLE_SCOPE"), report.completeness());

        // Deleted / inaccessible content leaves active retrieval; content that merely changed tags stays.
        assertTrue(second.search(SearchRequest.of("OrbitClient setBatchSize batch size")).evidence().stream()
                .noneMatch(e -> e.questionId().equals("1007")));
        assertTrue(second.search(SearchRequest.of("403 Forbidden service token dataset-writer")).evidence().stream()
                .noneMatch(e -> e.questionId().equals("1009")));
        assertTrue(second.search(SearchRequest.of("configure SSO login OIDC")).evidence().stream()
                .anyMatch(e -> e.questionId().equals("1003")));
        assertEquals(0, second.categories().stream().filter(c -> c.id().equals("performance")).findFirst().orElseThrow().questionCount());
    }

    @Test
    void retentionPurgesRemovedContentAfterConfiguredPeriod() {
        KnowledgeBase first = TestSupport.kb(tmp, TestSupport.siteV1());
        first.sync(TestSupport.opts(SyncMode.INCREMENTAL, 0));
        KnowledgeBase second = TestSupport.kb(tmp, TestSupport.siteV2());
        SyncReport report = second.sync(TestSupport.opts(SyncMode.FULL, 0));

        assertEquals(2, report.purged());      // 1007 deleted, 1009 inaccessible, retention 0 days
        ThreadRecord purged = thread(second, "1007");
        assertTrue(purged.isPurged());
        assertEquals(ThreadStatus.DELETED, purged.status());
        assertTrue(second.store().loadClassification("fixture", "1007").isEmpty());
        assertFalse(Files.exists(second.store().paths().markdownQuestionFile("fixture", "1007")));
    }

    @Test
    void retentionKeepsTombstoneContentUntilPeriodElapses() {
        KnowledgeBase first = TestSupport.kb(tmp, TestSupport.siteV1());
        first.sync(TestSupport.opts(SyncMode.INCREMENTAL, 30));
        KnowledgeBase second = TestSupport.kb(tmp, TestSupport.siteV2());
        SyncReport report = second.sync(TestSupport.opts(SyncMode.FULL, 30));
        assertEquals(0, report.purged());
        assertFalse(thread(second, "1007").isPurged());
        assertEquals(ThreadStatus.DELETED, thread(second, "1007").status());
    }

    @Test
    void interruptedSyncResumesWithoutRepeatingFinishedWork() {
        var crashing = new TestSupport.Instrumented(TestSupport.siteV1());
        // Deterministic crash point: listing order is by modification time, so 1002, 1001, 1003, 1004, 1005 are stored first.
        crashing.hook = (op, id) -> {
            if (op.equals("fetch") && id.equals("1006")) {
                throw new IllegalStateException("simulated crash (process killed)");
            }
        };
        KnowledgeBase kb = TestSupport.kb(tmp, crashing);
        assertThrows(IllegalStateException.class, () -> kb.sync(TestSupport.opts(SyncMode.INCREMENTAL)));
        int storedBeforeResume = kb.store().allThreads().size();
        assertEquals(5, storedBeforeResume);
        assertTrue(kb.status().interruptedRunPending());

        var healthy = new TestSupport.Instrumented(TestSupport.siteV1());
        KnowledgeBase resumed = TestSupport.kb(tmp, healthy);
        SyncReport report = resumed.sync(TestSupport.opts(SyncMode.INCREMENTAL));

        assertTrue(report.resumed());
        assertEquals(0, healthy.listCalls.get(), "listing was finished before the crash and must not be repeated");
        assertEquals(SyncReport.COMPLETE, report.outcome());
        assertEquals(10, resumed.store().allThreads().size());
        assertEquals(storedBeforeResume, report.skippedAlreadyCurrent(), "already fetched threads are skipped");
        assertFalse(resumed.status().interruptedRunPending());
        assertEquals(10, healthy.fetchedIds.size() + report.skippedAlreadyCurrent());
    }

    @Test
    void rateLimitsAreRetriedWithBackoffAndRetryAfter() {
        List<Duration> waits = Collections.synchronizedList(new ArrayList<>());
        var adapter = new TestSupport.Instrumented(TestSupport.siteV1());
        adapter.hook = new TestSupport.Instrumented.Hook() {
            int failures;

            @Override
            public synchronized void run(String op, String id) throws SourceException {
                if (op.equals("list") && failures < 2) {
                    failures++;
                    throw new SourceException(SourceException.Kind.RATE_LIMITED, "slow down", Duration.ofSeconds(7), null);
                }
            }
        };
        KnowledgeBase kb = TestSupport.kb(tmp, adapter, TestSupport.fastRetrier(5, waits));
        SyncReport report = kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));

        assertEquals(SyncReport.COMPLETE, report.outcome());
        assertEquals(2, waits.size());
        assertTrue(waits.stream().allMatch(w -> w.compareTo(Duration.ofSeconds(7)) >= 0), "Retry-After must be honoured: " + waits);
    }

    @Test
    void exponentialBackoffGrowsWhenNoRetryAfterIsGiven() throws Exception {
        List<Duration> waits = new ArrayList<>();
        var retrier = TestSupport.fastRetrier(4, waits);
        int[] calls = {0};
        assertThrows(SourceException.class, () -> retrier.call(() -> {
            calls[0]++;
            throw new SourceException(SourceException.Kind.TRANSIENT, "boom");
        }));
        assertEquals(4, calls[0]);
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), waits);
    }

    @Test
    void exhaustedRetriesProducePartialRunAndKeepCheckpointBack() {
        var adapter = new TestSupport.Instrumented(TestSupport.siteV1());
        adapter.hook = (op, id) -> {
            if (op.equals("fetch") && id.equals("1004")) {
                throw new SourceException(SourceException.Kind.TRANSIENT, "server error");
            }
        };
        KnowledgeBase kb = TestSupport.kb(tmp, adapter);
        SyncReport report = kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));

        assertEquals(SyncReport.PARTIAL, report.outcome());
        assertEquals(1, report.failures().size());
        assertEquals("1004", report.failures().get(0).questionId());
        assertEquals(9, kb.store().allThreads().size());
        Checkpoint cp = kb.store().listStateNames("checkpoint-").stream()
                .map(n -> kb.store().readState(n, Checkpoint.class).orElseThrow()).findFirst().orElseThrow();
        assertEquals(null, cp.highWaterMark(), "high-water mark must not advance past a failed item");
        assertEquals("PARTIAL", cp.lastOutcome());

        // The next run (source healthy again) retries exactly the failed item.
        var healthy = new TestSupport.Instrumented(TestSupport.siteV1());
        KnowledgeBase retry = TestSupport.kb(tmp, healthy);
        SyncReport second = retry.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        assertEquals(SyncReport.COMPLETE, second.outcome());
        assertEquals(1, second.added());
        assertEquals(10, retry.store().allThreads().size());
    }

    @Test
    void authenticationFailureAbortsTheRunWithoutStoringAnything() {
        var adapter = new TestSupport.Instrumented(TestSupport.siteV1());
        adapter.hook = (op, id) -> {
            throw new SourceException(SourceException.Kind.AUTH_FAILED, "HTTP 401: credentials missing, expired or rejected");
        };
        KnowledgeBase kb = TestSupport.kb(tmp, adapter);
        SyncReport report = kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));

        assertEquals(SyncReport.FAILED, report.outcome());
        assertTrue(report.abortReason().contains("AUTH_FAILED"));
        assertEquals(1, adapter.listCalls.get(), "authentication failures must not be retried");
        assertTrue(kb.store().allThreads().isEmpty());
        assertTrue(report.completeness().startsWith("NOT_COMPLETE"));
        assertTrue(kb.store().listStateNames("checkpoint-").isEmpty());
    }

    @Test
    void secondConcurrentSyncIsRejectedByTheLock() {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.init();
        try (var lock = kb.store().acquireSyncLock()) {
            assertNotNull(lock);
            StoreException e = assertThrows(StoreException.class, () -> kb.sync(TestSupport.opts(SyncMode.INCREMENTAL)));
            assertTrue(e.getMessage().contains("already running"));
        }
    }

    @Test
    void syncReportsAreWrittenAndNeverClaimCompletenessAfterFailures() {
        var adapter = new TestSupport.Instrumented(TestSupport.siteV1());
        adapter.hook = (op, id) -> {
            if (op.equals("fetch") && id.equals("1004")) {
                throw new SourceException(SourceException.Kind.FATAL, "bad payload");
            }
        };
        KnowledgeBase kb = TestSupport.kb(tmp, adapter);
        SyncReport report = kb.sync(TestSupport.opts(SyncMode.FULL));
        assertTrue(report.completeness().startsWith("PARTIAL"), report.completeness());
        assertEquals(1, kb.store().listReportIds().size());
        assertEquals(report.runId(), kb.store().listReportIds().get(0));
        assertTrue(kb.store().listReportIds().stream().map(id -> kb.store().readReport(id, SyncReport.class).orElseThrow())
                .allMatch(r -> !r.limitations().isEmpty()));
        assertEquals(Set.of("ACTIVE"), kb.store().allThreads().stream().map(t -> t.status().name()).collect(Collectors.toSet()));
    }
}
