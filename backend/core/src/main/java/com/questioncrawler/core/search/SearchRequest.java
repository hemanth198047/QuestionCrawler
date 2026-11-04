package com.questioncrawler.core.search;

import java.util.List;

/** Everything about a query is data: the text is never interpreted as query syntax. */
public record SearchRequest(
        String query,
        List<String> tags,
        List<String> categories,
        int topK,
        int maxTotalChars,
        int maxPassageChars) {

    public static final int DEFAULT_TOP_K = 5;
    public static final int DEFAULT_MAX_TOTAL_CHARS = 8000;
    public static final int DEFAULT_MAX_PASSAGE_CHARS = 1200;

    public SearchRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
        categories = categories == null ? List.of() : List.copyOf(categories);
        topK = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, 50);
        maxTotalChars = maxTotalChars <= 0 ? DEFAULT_MAX_TOTAL_CHARS : maxTotalChars;
        maxPassageChars = maxPassageChars <= 0 ? DEFAULT_MAX_PASSAGE_CHARS : maxPassageChars;
    }

    public static SearchRequest of(String query) {
        return new SearchRequest(query, null, null, 0, 0, 0);
    }
}
