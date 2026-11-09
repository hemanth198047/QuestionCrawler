package com.questioncrawler.core.source;

import java.util.List;
import java.util.Locale;

/**
 * The Links source: short links or page addresses to read (source.links.list), whether the short links found in items
 * collected from other sources are read too (source.links.fromCollected), and whether the links ON each page are
 * followed: how many clicks deep, how far (same site, same section, anywhere) and at most how many pages.
 */
public record LinksOptions(List<String> links, boolean fromCollected, boolean followLinks, int depth, String scope, int maxPages) {
    public static final LinksOptions NONE = new LinksOptions(List.of(), false);

    public LinksOptions {
        links = links == null ? List.of() : links.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        depth = Math.max(0, Math.min(depth, 5));
        scope = scope == null ? "site" : scope.strip().toLowerCase(Locale.ROOT);
        if (!List.of("site", "section", "any").contains(scope)) {
            scope = "site";
        }
        maxPages = maxPages <= 0 ? 500 : Math.min(maxPages, 100_000);
    }

    /** Only the listed links, no following. */
    public LinksOptions(List<String> links, boolean fromCollected) {
        this(links, fromCollected, false, 0, "site", 500);
    }

    public boolean crawls() {
        return followLinks && depth > 0;
    }

    /** One link per entry; commas, spaces and new lines all separate entries. */
    public static List<String> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split("[,\\s]+")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }
}
