package com.questioncrawler.core.source;

import com.questioncrawler.core.util.Sleeper;
import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Retry with exponential backoff. Honours a server-provided Retry-After when it is longer than the
 * computed backoff. Non-retryable failures (authentication, forbidden, gone, ...) surface immediately.
 */
public final class Retrier {

    @FunctionalInterface
    public interface Call<T> {
        T run() throws SourceException;
    }

    /** A server asking us to wait longer than this is treated as a failure instead of stalling the run for hours. */
    static final Duration MAX_HONOURED_RETRY_AFTER = Duration.ofMinutes(5);

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Sleeper sleeper;
    private final DoubleSupplier jitter;

    public Retrier(int maxAttempts, Duration baseDelay, Duration maxDelay, Sleeper sleeper, DoubleSupplier jitter) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    public static Retrier standard(int maxAttempts) {
        return new Retrier(maxAttempts, Duration.ofSeconds(1), Duration.ofSeconds(60), Sleeper.REAL, Math::random);
    }

    public <T> T call(Call<T> call) throws SourceException {
        int attempt = 1;
        while (true) {
            try {
                return call.run();
            } catch (SourceException e) {
                if (!e.kind().isRetryable() || attempt >= maxAttempts) {
                    throw e;
                }
                if (e.retryAfter() != null && e.retryAfter().compareTo(MAX_HONOURED_RETRY_AFTER) > 0) {
                    throw e;
                }
                Duration wait = backoff(attempt);
                if (e.retryAfter() != null && e.retryAfter().compareTo(wait) > 0) {
                    wait = e.retryAfter();
                }
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SourceException(SourceException.Kind.FATAL, "Interrupted while waiting to retry", null, interrupted);
                }
                attempt++;
            }
        }
    }

    Duration backoff(int attempt) {
        double exponential = baseDelay.toMillis() * Math.pow(2, attempt - 1);
        double capped = Math.min(exponential, maxDelay.toMillis());
        double jittered = capped * (1.0 + 0.2 * jitter.getAsDouble());
        return Duration.ofMillis((long) jittered);
    }
}
