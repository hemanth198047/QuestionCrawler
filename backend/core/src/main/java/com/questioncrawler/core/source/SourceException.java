package com.questioncrawler.core.source;

import java.time.Duration;

/** Failure reported by a source adapter, classified so callers can react correctly. */
public class SourceException extends Exception {

    public enum Kind {
        /** Back off and retry; honour retryAfter when present. */
        RATE_LIMITED(true),
        /** Network error, timeout or 5xx. Retry with backoff. */
        TRANSIENT(true),
        /** Credentials missing, expired or rejected. Never retried; the run must stop. */
        AUTH_FAILED(false),
        /** Item explicitly reported as deleted (for example HTTP 410). */
        GONE(false),
        /** Access explicitly refused for this item (for example HTTP 403). */
        FORBIDDEN(false),
        /** Item not found; the source does not say whether it was deleted or hidden. */
        NOT_FOUND(false),
        /** The source's request quota is used up for a long time (hours). Retrying now is pointless; the run stops. */
        QUOTA_EXHAUSTED(false),
        /** The live adapter lacks verified endpoint details. See docs/LIVE_INTEGRATION.md. */
        NOT_CONFIGURED(false),
        /** Unexpected response shape or other non-retryable failure. */
        FATAL(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    private final Kind kind;
    private final Duration retryAfter;

    public SourceException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public SourceException(Kind kind, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    /** Server-suggested wait, or null. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
