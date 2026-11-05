package com.questioncrawler.core.service;

import java.util.List;

/**
 * Result of checking whether, and how, a site can be collected from. Written for people: every stage has a
 * plain-language summary, the facts we observed, and what is needed (and from whom) if something is in the way.
 */
public record ConnectionReport(
        String checkedAt,
        String target,
        String sourceType,
        /** READY, NEEDS_ATTENTION, BLOCKED or NOT_SUPPORTED_YET */
        String verdict,
        String headline,
        String detail,
        /** 0-100: how much of the path to the data is open. */
        int score,
        List<Stage> stages,
        Route route,
        List<Requirement> requirements) {

    /** status: PASS, WARN, FAIL, INFO (informational), SKIPPED. */
    public record Stage(String id, String title, String question, String status, String summary,
                        List<Fact> facts, List<Need> needs, long millis) {}

    public record Fact(String label, String value) {}

    /** Something that must be provided or changed, why, and who can do it. */
    public record Need(String what, String why, String who) {}

    /** kind: OFFLINE_DEMO, OFFICIAL_API, API_FOUND, SIGN_IN_REQUIRED, WEB_PAGES, WEB_PAGES_SCRIPTED, DISCOURAGED, NONE. */
    public record Route(String kind, String label, String explanation, boolean supported, List<String> nextSteps) {}

    /** status: MET, MISSING, UNKNOWN. */
    public record Requirement(String id, String label, String status, String detail) {}
}
