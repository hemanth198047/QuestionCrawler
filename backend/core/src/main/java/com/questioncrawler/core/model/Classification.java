package com.questioncrawler.core.model;

import java.util.List;

/** Generated, rule-based classification with provenance. Not AI semantic understanding. */
public record Classification(
        String method,
        String taxonomyHash,
        String classifiedAt,
        List<CategoryMatch> categories) {

    public record CategoryMatch(String categoryId, double score, List<String> matchedRules) {
        public CategoryMatch {
            matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
        }
    }

    public Classification {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public List<String> categoryIds() {
        return categories.stream().map(CategoryMatch::categoryId).toList();
    }
}
