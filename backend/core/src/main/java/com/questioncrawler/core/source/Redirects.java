package com.questioncrawler.core.source;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * How HTTP redirects (301, 302, 303, 307, 308) are followed. Internal sites and API gateways redirect routinely (http to https,
 * a trailing slash, another host), so they are followed by default; a few rules keep it sensible:
 * at most {@value #MAX_HOPS} hops, loops stop, a redirect from https down to plain http is never followed, and when a redirect on
 * the same host leaves out the query string (which holds the API key) the original one is carried over.
 */
public final class Redirects {

    public static final int MAX_HOPS = 5;

    /** all: follow everything; same-host: only redirects that stay on the same host; off: never. */
    public enum Mode {
        ALL, SAME_HOST, OFF;

        public static Mode parse(String text) {
            if (text == null) {
                return ALL;
            }
            return switch (text.strip().toLowerCase(Locale.ROOT).replace('_', '-')) {
                case "off", "false", "no", "never" -> OFF;
                case "same-host", "samehost", "same" -> SAME_HOST;
                default -> ALL;
            };
        }

        public String text() {
            return switch (this) {
                case ALL -> "all";
                case SAME_HOST -> "same-host";
                case OFF -> "off";
            };
        }
    }

    /** Either the next address to request, or the reason for stopping. */
    public record Step(URI target, String stopReason) {
        public boolean follow() {
            return target != null;
        }
    }

    private Redirects() {}

    public static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * @param current  the address that answered with a redirect
     * @param location the Location header
     * @param original the address of the very first request (for the same-host rule)
     * @param visited  addresses already requested in this chain (loop detection); the caller adds {@code target} when it follows
     * @param hops     redirects followed so far
     */
    public static Step next(URI current, String location, URI original, Mode mode, Set<String> visited, int hops) {
        if (mode == Mode.OFF) {
            return new Step(null, "following redirects is switched off (setting 'Follow redirects')");
        }
        if (location == null || location.isBlank()) {
            return new Step(null, "the redirect has no Location header");
        }
        if (hops >= MAX_HOPS) {
            return new Step(null, "more than " + MAX_HOPS + " redirects in a row");
        }
        URI target;
        try {
            target = current.resolve(location.strip());
        } catch (IllegalArgumentException e) {
            return new Step(null, "the redirect address is not valid");
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return new Step(null, "the redirect leads to '" + scheme + "', not a web address");
        }
        if ("https".equalsIgnoreCase(current.getScheme()) && scheme.equals("http")) {
            return new Step(null, "the redirect leads from https down to unencrypted http, which is never followed: " + show(target));
        }
        boolean sameHost = target.getHost() != null && target.getHost().equalsIgnoreCase(current.getHost());
        boolean sameAsOriginal = target.getHost() != null && original.getHost() != null && target.getHost().equalsIgnoreCase(original.getHost());
        if (mode == Mode.SAME_HOST && !sameAsOriginal) {
            return new Step(null, "the redirect leads to another host (" + target.getHost() + ") and 'Follow redirects' is set to same host only");
        }
        // A redirect on the same host that drops the query string (the API key lives there) keeps the original one.
        if (sameHost && target.getRawQuery() == null && current.getRawQuery() != null) {
            String q = current.getRawQuery();
            target = URI.create(target.toString().split("#", 2)[0] + "?" + q);
        }
        if (visited.contains(target.toString())) {
            return new Step(null, "the redirects lead in a circle: " + show(target));
        }
        return new Step(target, null);
    }

    /** An address without its query string and credentials: safe to show and to log. */
    public static String show(URI uri) {
        StringBuilder out = new StringBuilder();
        if (uri.getScheme() != null) {
            out.append(uri.getScheme()).append("://");
        }
        out.append(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getPort() > 0) {
            out.append(':').append(uri.getPort());
        }
        out.append(uri.getRawPath() == null ? "" : uri.getRawPath());
        return out.toString();
    }
}
