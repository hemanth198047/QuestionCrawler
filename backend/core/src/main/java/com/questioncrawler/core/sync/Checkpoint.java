package com.questioncrawler.core.sync;

import java.util.List;

/**
 * Durable progress marker for one collection scope (source + tags + combination + partial flag).
 * highWaterMark only advances after a run finished with no failures, so failed items are retried.
 */
public record Checkpoint(
        String scopeKey,
        String sourceSystem,
        List<String> tags,
        String tagMode,
        boolean partialTags,
        String highWaterMark,
        String lastRunAt,
        String lastSuccessfulRunAt,
        String lastFullReconcileAt,
        String lastRunId,
        String lastOutcome,
        String coveredFrom) {}
