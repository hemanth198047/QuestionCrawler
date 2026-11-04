package com.questioncrawler.core.sync;

public enum SyncMode {
    /** Initial collection (no checkpoint yet) or incremental update since the checkpoint minus an overlap window. */
    INCREMENTAL,
    /** Re-reads the whole scope, compares content, and resolves missing items (deleted / no longer matching / inaccessible). */
    FULL
}
