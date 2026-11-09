package com.questioncrawler.core.source;

import java.util.List;

/**
 * The Links source: short links or page addresses to read (source.links.list), and whether the short links found in
 * items collected from other sources are read too (source.links.fromCollected).
 */
public record LinksOptions(List<String> links, boolean fromCollected) {
    public static final LinksOptions NONE = new LinksOptions(List.of(), false);

    public LinksOptions {
        links = links == null ? List.of() : links.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** One link per entry; commas, spaces and new lines all separate entries. */
    public static List<String> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split("[,\\s]+")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }
}
