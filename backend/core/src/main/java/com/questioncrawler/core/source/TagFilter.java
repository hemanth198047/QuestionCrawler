package com.questioncrawler.core.source;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Tag matching rules. Matching is EXACT (case-insensitive) by default; partial matching (substring)
 * must be requested explicitly.
 */
public record TagFilter(List<String> tags, TagMode mode, boolean partial) {

    public TagFilter {
        tags = tags == null ? List.of() : tags.stream().map(t -> t.trim().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty()).distinct().toList();
        mode = mode == null ? TagMode.ANY : mode;
    }

    public boolean matches(Collection<String> questionTags) {
        if (tags.isEmpty()) {
            return false;
        }
        List<String> normalized = questionTags.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
        java.util.function.Predicate<String> has = target ->
                partial ? normalized.stream().anyMatch(t -> t.contains(target)) : normalized.contains(target);
        return mode == TagMode.ALL ? tags.stream().allMatch(has) : tags.stream().anyMatch(has);
    }
}
