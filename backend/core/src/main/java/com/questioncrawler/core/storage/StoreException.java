package com.questioncrawler.core.storage;

/** Actionable storage problem (missing store, newer schema, concurrent sync, ...). */
public class StoreException extends RuntimeException {
    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
