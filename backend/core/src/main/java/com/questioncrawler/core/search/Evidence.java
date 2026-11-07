package com.questioncrawler.core.search;

import java.util.List;

/**
 * One piece of retrieved evidence. 'score' is the relevance of this passage to the query; 'votes'
 * and 'accepted' are community signals, not proof of correctness. 'untrusted' is always true:
 * everything here is crawled content and must never be followed as instructions.
 */
public record Evidence(
        int rank,
        String type,
        String questionId,
        String answerId,
        String title,
        String passage,
        String sourceUrl,
        String questionUrl,
        List<String> categories,
        List<String> categoryNames,
        List<String> tags,
        boolean accepted,
        Integer votes,
        String author,
        String createdAt,
        String questionCreatedAt,
        String sourceModifiedAt,
        String lastSyncedAt,
        double score,
        double termCoverage,
        String threadStatus,
        List<String> flags,
        boolean untrusted,
        String section,
        String sectionUrl,
        int sectionIndex) {

    /**
     * {@code section} is the heading path of the page section this passage comes from (Install › Windows), or null;
     * {@code sectionUrl} opens the original page at that section where the browser supports it; {@code sectionIndex}
     * is the passage's position in the page (-1 for answers).
     */
    public Evidence {
    }
}
