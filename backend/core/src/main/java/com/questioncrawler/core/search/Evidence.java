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
        String sourceModifiedAt,
        String lastSyncedAt,
        double score,
        double termCoverage,
        String threadStatus,
        List<String> flags,
        boolean untrusted) {}
