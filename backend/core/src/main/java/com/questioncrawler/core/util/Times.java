package com.questioncrawler.core.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Timestamps are stored as ISO-8601 strings. null means "unknown", never a guessed value. */
public final class Times {

    private Times() {}

    public static Optional<Instant> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            // fall through to offset form
        }
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static String format(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** Returns the later of two timestamps (either may be null/unparseable). */
    public static String max(String a, String b) {
        Optional<Instant> pa = parse(a);
        Optional<Instant> pb = parse(b);
        if (pa.isEmpty()) return pb.isPresent() ? b : a;
        if (pb.isEmpty()) return a;
        return pa.get().isAfter(pb.get()) ? a : b;
    }
}
