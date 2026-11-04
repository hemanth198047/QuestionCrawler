package com.questioncrawler.core.search;

import com.questioncrawler.core.util.AgeSpec;
import java.time.Instant;

/**
 * Filters by AGE, measured from now back to creation time (for example {@code 90d}, {@code 6m}, {@code 1y}).
 *
 * <ul>
 *   <li>{@code maxQuestionAge}: only questions created within this age (newer than)</li>
 *   <li>{@code minQuestionAge}: only questions older than this age</li>
 *   <li>{@code maxAnswerAge} / {@code minAnswerAge}: the same, for answers</li>
 * </ul>
 * A question filter applies to a whole thread (the question and all its answers are excluded together).
 * An answer filter only removes ANSWER evidence; the question itself stays available. Posts whose creation
 * time is unknown are excluded whenever a filter on that kind of post is active (an age is never guessed).
 */
public record AgeFilter(String maxQuestionAge, String minQuestionAge, String maxAnswerAge, String minAnswerAge) {

    public static final AgeFilter NONE = new AgeFilter(null, null, null, null);

    public AgeFilter {
        maxQuestionAge = blankToNull(maxQuestionAge);
        minQuestionAge = blankToNull(minQuestionAge);
        maxAnswerAge = blankToNull(maxAnswerAge);
        minAnswerAge = blankToNull(minAnswerAge);
        for (String spec : new String[] {maxQuestionAge, minQuestionAge, maxAnswerAge, minAnswerAge}) {
            if (spec != null) {
                AgeSpec.before(spec, Instant.EPOCH.plusSeconds(1)); // validates; throws IllegalArgumentException
            }
        }
    }

    public boolean isEmpty() {
        return maxQuestionAge == null && minQuestionAge == null && maxAnswerAge == null && minAnswerAge == null;
    }

    public boolean hasQuestionFilter() {
        return maxQuestionAge != null || minQuestionAge != null;
    }

    public boolean hasAnswerFilter() {
        return maxAnswerAge != null || minAnswerAge != null;
    }

    /** Inclusive lower bound for question creation time in epoch millis (Long.MIN_VALUE = unbounded). */
    public long questionFrom(Instant now) {
        return maxQuestionAge == null ? Long.MIN_VALUE : AgeSpec.before(maxQuestionAge, now).toEpochMilli();
    }

    public long questionTo(Instant now) {
        return minQuestionAge == null ? Long.MAX_VALUE : AgeSpec.before(minQuestionAge, now).toEpochMilli();
    }

    public long answerFrom(Instant now) {
        return maxAnswerAge == null ? Long.MIN_VALUE : AgeSpec.before(maxAnswerAge, now).toEpochMilli();
    }

    public long answerTo(Instant now) {
        return minAnswerAge == null ? Long.MAX_VALUE : AgeSpec.before(minAnswerAge, now).toEpochMilli();
    }

    /** True when a question created at {@code created} passes the question filter (null = unknown). */
    public boolean acceptsQuestion(Instant created, Instant now) {
        if (!hasQuestionFilter()) {
            return true;
        }
        if (created == null) {
            return false;
        }
        long t = created.toEpochMilli();
        return t >= questionFrom(now) && t <= questionTo(now);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
