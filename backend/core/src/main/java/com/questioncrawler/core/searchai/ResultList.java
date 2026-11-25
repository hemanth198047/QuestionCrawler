package com.questioncrawler.core.searchai;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.util.Json;
import java.util.List;

/**
 * Finds the list of hits inside a /search answer so paging can tell when to stop. The answer's shape is not
 * documented, so this is deliberately tolerant: a top-level array, or an array of objects under a usual name
 * (results, docs, hits, items, data, response, documents), searched a few levels deep. When no list is found the
 * result is "unknown" and paging stops after the first page instead of guessing.
 */
final class ResultList {

    private static final List<String> NAMES = List.of("results", "docs", "hits", "items", "data", "response", "documents", "chunks");
    private static final int MAX_DEPTH = 3;

    private ResultList() {}

    /** Number of hits, or -1 when no list of hits can be found. */
    static int count(JsonNode answer) {
        JsonNode list = find(answer, 0);
        return list == null ? -1 : list.size();
    }

    /** Stable fingerprint of the hits, to notice a server that ignores {@code start} and repeats a page. */
    static String fingerprint(JsonNode answer) {
        JsonNode list = find(answer, 0);
        return list == null ? "" : com.questioncrawler.core.util.Hashes.sha256(Json.writeCompact(list));
    }

    private static JsonNode find(JsonNode node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        if (!node.isObject()) {
            return null;
        }
        for (String name : NAMES) {
            JsonNode child = node.get(name);
            if (child != null) {
                JsonNode found = find(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        for (JsonNode child : node) {
            if (child.isArray() && child.size() > 0 && child.get(0).isObject()) {
                return child;
            }
        }
        return null;
    }
}
