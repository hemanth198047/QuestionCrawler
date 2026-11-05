package com.questioncrawler.core.sync;

/**
 * Snapshot of a running synchronization, published to listeners so a UI can show live progress.
 * Counters only ever grow during a run.
 */
public record SyncProgress(
        String runId,
        String phase,
        String message,
        int pagesListed,
        int questionsFound,
        int toFetch,
        int fetched,
        int added,
        int updated,
        int unchanged,
        int failures) {

    public static final String LISTING = "LISTING";
    public static final String FETCHING = "FETCHING";
    public static final String RECONCILING = "RECONCILING";
    public static final String FINISHING = "FINISHING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
}
