package com.questioncrawler.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * The persisted unit: one question thread plus bookkeeping. The 'question' holds ORIGINAL normalized
 * content only; generated data (categories, summaries) is stored separately.
 * After a retention purge 'question' is null and only the tombstone fields remain.
 */
public record ThreadRecord(
        int schemaVersion,
        String sourceSystem,
        Question question,
        String questionId,
        String retrievedAt,
        String contentHash,
        ThreadStatus status,
        String statusChangedAt,
        String statusReason,
        String syncRunId,
        String firstSeenAt,
        List<String> scopes) {

    public static final int SCHEMA_VERSION = 1;

    public ThreadRecord {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        status = status == null ? ThreadStatus.ACTIVE : status;
    }

    public String key() {
        return sourceSystem + ":" + questionId;
    }

    @JsonIgnore
    public boolean isPurged() {
        return question == null;
    }

    public ThreadRecord withStatus(ThreadStatus newStatus, String at, String reason) {
        return new ThreadRecord(schemaVersion, sourceSystem, question, questionId, retrievedAt, contentHash,
                newStatus, at, reason, syncRunId, firstSeenAt, scopes);
    }

    public ThreadRecord withScopes(List<String> newScopes) {
        return new ThreadRecord(schemaVersion, sourceSystem, question, questionId, retrievedAt, contentHash,
                status, statusChangedAt, statusReason, syncRunId, firstSeenAt, newScopes);
    }

    public ThreadRecord purged(String at) {
        return new ThreadRecord(schemaVersion, sourceSystem, null, questionId, retrievedAt, contentHash,
                status, at, "content purged by retention policy", syncRunId, firstSeenAt, scopes);
    }
}
