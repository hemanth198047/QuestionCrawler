package com.questioncrawler.core.sync;

import com.questioncrawler.core.model.ThreadRecord;

/**
 * Downstream hook for derived data. The sync engine persists the durable thread JSON itself; the
 * publisher then classifies, exports markdown and updates the search index.
 */
public interface ThreadPublisher {

    /** A thread was added or changed and is retrievable (ACTIVE, NO_LONGER_MATCHING, UNAVAILABLE, UNKNOWN). */
    void published(ThreadRecord thread);

    /** A thread became non-retrievable (DELETED / INACCESSIBLE): remove it from search and exports. */
    void removed(ThreadRecord thread);

    /** Retention elapsed: all derived data and the original content are gone. */
    void purged(ThreadRecord thread);

    /** Called once at the end of a run (also after failures) to commit indexes and regenerate listings. */
    void finish();

    ThreadPublisher NOOP = new ThreadPublisher() {
        @Override public void published(ThreadRecord thread) { }
        @Override public void removed(ThreadRecord thread) { }
        @Override public void purged(ThreadRecord thread) { }
        @Override public void finish() { }
    };
}
