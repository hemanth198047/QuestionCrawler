package com.questioncrawler.core.util;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relative ages such as {@code 36h}, {@code 30d}, {@code 2w}, {@code 6m} (calendar months) or {@code 1y}.
 * An age is always measured from "now" back to a post's CREATION time.
 */
public final class AgeSpec {

    /** "all", "everything", "none" or "unlimited": an explicit request for NO time window (as opposed to "use the saved one"). */
    public static boolean isEverything(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip().toLowerCase(java.util.Locale.ROOT);
        return t.equals("all") || t.equals("everything") || t.equals("none") || t.equals("unlimited");
    }


    private static final Pattern SPEC = Pattern.compile("^(\\d{1,5})\\s*([hdwmy])$", Pattern.CASE_INSENSITIVE);

    private AgeSpec() {}

    /** True when the text is a valid age expression. */
    public static boolean isValid(String spec) {
        return spec != null && SPEC.matcher(spec.trim()).matches();
    }

    /** The instant that is exactly {@code spec} before {@code now}. */
    public static Instant before(String spec, Instant now) {
        Matcher m = SPEC.matcher(spec == null ? "" : spec.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid age '" + spec + "'. Use a number and a unit: h (hours), d (days), w (weeks), m (months), y (years), e.g. 30d, 6m, 1y.");
        }
        int n = Integer.parseInt(m.group(1));
        return switch (Character.toLowerCase(m.group(2).charAt(0))) {
            case 'h' -> now.minusSeconds(3600L * n);
            case 'd' -> now.minusSeconds(86_400L * n);
            case 'w' -> now.minusSeconds(7 * 86_400L * n);
            case 'm' -> now.atZone(ZoneOffset.UTC).minus(Period.ofMonths(n)).toInstant();
            default -> now.atZone(ZoneOffset.UTC).minus(Period.ofYears(n)).toInstant();
        };
    }

    public static Optional<Instant> beforeIfPresent(String spec, Instant now) {
        return spec == null || spec.isBlank() ? Optional.empty() : Optional.of(before(spec, now));
    }
}
