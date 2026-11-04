package com.questioncrawler.core.sync;

import java.util.List;

/**
 * What a synchronization actually did, including what it could NOT verify. The completeness field is
 * deliberately conservative: 'all collected' is never claimed unless every page was read and every
 * item verified in a full reconciliation.
 */
public record SyncReport(
        String runId,
        String scopeKey,
        String sourceSystem,
        List<String> tags,
        String tagMode,
        boolean partialTags,
        String mode,
        String startedAt,
        String finishedAt,
        String outcome,
        boolean resumed,
        String since,
        boolean listingComplete,
        int pagesListed,
        int questionsListed,
        int duplicateListings,
        int added,
        int updated,
        int unchanged,
        int skippedAlreadyCurrent,
        int statusChanged,
        int purged,
        List<Failure> failures,
        List<StatusChange> statusChanges,
        List<String> warnings,
        String completeness,
        List<String> limitations,
        String abortReason) {

    public record Failure(String questionId, String kind, String message) {}

    public record StatusChange(String questionId, String from, String to, String reason) {}

    public static final String COMPLETE = "COMPLETE";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";
}
