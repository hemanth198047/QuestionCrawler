package com.questioncrawler.core.service;

import com.questioncrawler.core.search.SearchResponse;
import java.util.List;
import java.util.Map;

/** Everything the home screen needs in one call. */
public record Overview(
        String configFile,
        boolean configExists,
        String sourceType,
        String sourceLabel,
        List<String> tags,
        String window,
        List<SetupStep> steps,
        String nextStep,
        Totals totals,
        List<CategorySlice> categories,
        List<RunBrief> recentRuns,
        SearchResponse.Freshness freshness,
        boolean syncRunning,
        List<String> notes,
        String scope) {

    /*
     * scope: what bounds the collection besides tags, in plain words ("Spaces: DGF", "Pages: 123", "Places: 1006"),
     * or null when nothing does (then "all" needs a time window on wiki sources).
     */

    /** status: DONE, TODO, ATTENTION, READY (can be used now), LOCKED (waiting for an earlier step). */
    public record SetupStep(String id, String title, String description, String status, String action) {}

    public record Totals(int questions, int answers, int accepted, int unanswered, int hidden, Map<String, Integer> byStatus) {}

    public record CategorySlice(String id, String name, int count) {}

    public record RunBrief(String runId, String mode, String outcome, String finishedAt, int added, int updated, int failures) {}
}
