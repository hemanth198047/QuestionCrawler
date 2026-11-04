package com.questioncrawler.core.model;

/**
 * Lifecycle state of a stored thread. Absence from a tag-filtered listing is NOT deletion; a status
 * other than ACTIVE is only set from positive evidence gathered during reconciliation.
 */
public enum ThreadStatus {
    /** Present and matching the collection scope. */
    ACTIVE(true),
    /** Fetchable but its tags no longer match the scope. Content is still valid, kept retrievable. */
    NO_LONGER_MATCHING(true),
    /** Last attempt failed transiently. Previously collected content is kept, flagged. */
    UNAVAILABLE(true),
    /** The source answered "not found" without saying whether it was deleted or hidden. */
    UNKNOWN(true),
    /** The source explicitly reported the post as deleted (for example HTTP 410). */
    DELETED(false),
    /** The source explicitly refused access (for example HTTP 403) with valid credentials. */
    INACCESSIBLE(false);

    private final boolean retrievable;

    ThreadStatus(boolean retrievable) {
        this.retrievable = retrievable;
    }

    /** Whether the thread participates in search and browsing. */
    public boolean isRetrievable() {
        return retrievable;
    }
}
