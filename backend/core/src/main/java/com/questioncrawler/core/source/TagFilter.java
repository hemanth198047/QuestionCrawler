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

    public TagFilter {
        tags = tags == null ? List.of() : tags.stream().map(TagFilter::normalize).filter(t -> !t.isEmpty()).distinct().toList();
        mode = mode == null ? TagMode.ANY : mode;
    }

    public TagFilter(List<String> tags, TagMode mode, boolean partial) {
        this(tags, mode, partial, null);
    }

    public static String normalize(String tag) {
        return tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    public boolean matches(Collection<String> questionTags) {
        if (tags.isEmpty()) {
            return false;
        }
        List<String> normalized = questionTags.stream().map(TagFilter::normalize).toList();
        java.util.function.Predicate<String> has = target ->
                partial ? normalized.stream().anyMatch(t -> t.contains(target)) : normalized.contains(target);
        return mode == TagMode.ALL ? tags.stream().allMatch(has) : tags.stream().anyMatch(has);
    }
}
