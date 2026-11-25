package com.questioncrawler.core.searchai;

/** A search-ai call failed; the message says what happened and what to do about it. */
public class SearchAiException extends RuntimeException {

    private final int httpStatus;

    public SearchAiException(String message) {
        this(message, 0, null);
    }

    public SearchAiException(String message, int httpStatus) {
        this(message, httpStatus, null);
    }

    public SearchAiException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** HTTP status of the failed answer, or 0 when no answer was received (curl failed, curl missing). */
    public int httpStatus() {
        return httpStatus;
    }
}
