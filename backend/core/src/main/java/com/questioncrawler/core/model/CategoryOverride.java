package com.questioncrawler.core.model;

import java.util.List;

/** A manual decision. Stored apart from generated classification so synchronization never erases it. */
public record CategoryOverride(List<String> categories, String note, String setAt) {
    public CategoryOverride {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
