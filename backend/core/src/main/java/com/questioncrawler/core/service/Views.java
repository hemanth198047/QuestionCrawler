package com.questioncrawler.core.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.questioncrawler.core.model.CategoryOverride;
import com.questioncrawler.core.model.Classification;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.search.SearchResponse;
import com.questioncrawler.core.sync.Checkpoint;
import java.util.List;
import java.util.Map;

/** Read models returned by {@link KnowledgeBase}; serialized as JSON by the CLI and the web API. */
public final class Views {

    private Views() {}

    /** A thread plus its effective categories and classification provenance (kept apart from original content). */
    public record ThreadView(
            ThreadRecord thread,
            List<String> categories,
            List<String> categoryNames,
            Classification classification,
            CategoryOverride override,
            boolean untrusted,
            String notice) {}

    public record CategorySummary(String id, String name, String description, int questionCount) {}

    public record QuestionRef(String source, String id, String title, String url, String createdAt, String status, int answerCount, boolean hasAcceptedAnswer, List<String> tags) {}

    public record CategoryListing(CategorySummary category, List<QuestionRef> questions) {}

    public record SyncSummary(String runId, String mode, String outcome, String finishedAt, int added, int updated, int unchanged,
                              int failures, int statusChanges, String completeness) {}

    public record StatusReport(
            String storageRoot,
            boolean initialized,
            Integer schemaVersion,
            int threadCount,
            Map<String, Integer> byStatus,
            int answerCount,
            Integer indexedDocuments,
            SyncSummary lastSync,
            List<Checkpoint> checkpoints,
            boolean interruptedRunPending,
            SearchResponse.Freshness freshness,
            int threadsClassifiedWithOlderTaxonomy,
            List<String> notes) {}

    public record Check(String name, String status, String detail) {}

    public record DoctorReport(List<Check> checks) {
        @JsonProperty("ok")
        public boolean ok() {
            return checks.stream().noneMatch(c -> c.status().equals("FAIL"));
        }
    }

    public record ReindexResult(int threads, int indexedDocuments, int skippedNonRetrievable) {}
}
