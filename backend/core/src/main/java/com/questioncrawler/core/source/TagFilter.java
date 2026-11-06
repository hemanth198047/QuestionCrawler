package com.questioncrawler.core.source;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Collection scope. Tag matching is EXACT (case-insensitive) by default; partial matching (substring)
 * must be requested explicitly. Whitespace inside a tag is read as a hyphen, so "Artificial Intelligence"
 * and "artificial-intelligence" are the same tag (Stack Exchange style tags never contain spaces).
 *
 * @param createdAfter optional ISO timestamp: adapters should only list questions created at or after it
 */
public record TagFilter(List<String> tags, TagMode mode, boolean partial, String createdAfter) {

    /** The reserved tag meaning "every tag": every question on the site, whatever its tags. Typed as "all" or "*". */
    public static final String ALL = "*";

    public TagFilter {
        tags = tags == null ? List.of() : tags.stream().map(TagFilter::normalize).filter(t -> !t.isEmpty()).distinct().toList();
        if (tags.contains(ALL)) {
            tags = List.of(ALL); // every question: any other tag adds nothing
        }
        mode = mode == null ? TagMode.ANY : mode;
    }

    /** Is this filter "every tag"? */
    public boolean isAll() {
        return tags.size() == 1 && tags.get(0).equals(ALL);
    }

    /** Does a list of typed tags (as in the settings) ask for every tag? */
    public static boolean asksForAll(Collection<String> typed) {
        return typed != null && typed.stream().anyMatch(t -> ALL.equals(normalize(t)));
    }

    public TagFilter(List<String> tags, TagMode mode, boolean partial) {
        this(tags, mode, partial, null);
    }

    public static String normalize(String tag) {
        String t = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        return t.equals("all") || t.equals("*") || t.equals("all-tags") || t.equals("alltags") ? ALL : t;
    }

    public boolean matches(Collection<String> questionTags) {
        if (tags.isEmpty()) {
            return false;
        }
        if (isAll()) {
            return true;
        }
        List<String> normalized = questionTags.stream().map(TagFilter::normalize).toList();
        java.util.function.Predicate<String> has = target ->
                partial ? normalized.stream().anyMatch(t -> t.contains(target)) : normalized.contains(target);
        return mode == TagMode.ALL ? tags.stream().allMatch(has) : tags.stream().anyMatch(has);
    }
}
