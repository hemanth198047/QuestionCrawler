package com.questioncrawler.core.sync;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-flight run, persisted after every listing page so an interrupted run resumes where it stopped.
 * Mutable on purpose (simple JSON bean); only touched by the thread that owns the sync lock.
 */
public final class RunState {
    public String runId;
    public String scopeKey;
    public SyncMode mode;
    public String startedAt;
    public String since;
    public String listingCursor;
    public boolean listingComplete;
    public int pagesListed;
    public int duplicateListings;
    /** question id -> modifiedAt (null when the source does not provide it). */
    public Map<String, String> candidates = new LinkedHashMap<>();

    public RunState() {}
}
